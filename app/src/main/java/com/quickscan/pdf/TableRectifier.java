package com.quickscan.pdf;

import java.util.Arrays;
import java.util.Random;

/**
 * Straightens a photographed form: finds the table (the largest connected block of ruled lines),
 * fits a straight line to each of its four outer edges, and maps that quadrilateral back to a
 * rectangle with a perspective transform. The whole page goes through the same transform, so the
 * title and header lines above the table come out straight too. The result is laid out on an A4
 * canvas with even margins. Pure Java on ARGB ints so it can run on a desktop JVM.
 */
final class TableRectifier {

    /** Long side of the working copy used for detection. */
    private static final int DETECT_SIZE = 1200;
    static final double A4_RATIO = 297.0 / 210.0;
    /** Content may reach this far past the table's left and right edges (see contentBox). */
    private static final double SIDE_SPILL = 0.06;
    private static final int WHITE = 0xFFFFFFFF;

    /** How the straightened page is laid out. */
    static final class Layout {
        /** Page height / width; 0 = follow the content's own shape. */
        final double ratio;
        /** Margins as fractions of the page width (side) and height (top and bottom). */
        final double side, top;
        /** Centre the content vertically instead of starting it at the top margin. */
        final boolean center;
        /** Size every page by its table, so tables come out the same width on every page. */
        final boolean uniform;
        /** Output height cap in pixels. */
        final int maxHeight;

        Layout(double ratio, double side, double top, boolean center, boolean uniform, int maxHeight) {
            this.ratio = ratio;
            this.side = side;
            this.top = top;
            this.center = center;
            this.uniform = uniform;
            this.maxHeight = maxHeight;
        }

        Layout withMaxHeight(int height) {
            return new Layout(ratio, side, top, center, uniform, height);
        }

        /** A4, 6 % side margins, top-aligned, capped at roughly 300 dpi. */
        static final Layout DEFAULT = new Layout(A4_RATIO, 0.06, 0.045, false, false, 3508);
    }
    /** An outer edge fit is trusted when it lies on table ink along this much of the span. */
    private static final double MIN_COVERAGE = 0.6;
    /** A line counts as a full ruled line when it lies on ink along this much of the span. */
    private static final double RULE_COVERAGE = 0.7;
    /** Slopes tried around the reference slope when searching for a ruled line (±0.06 ≈ ±3.4°). */
    private static final int SLOPE_STEPS = 20;
    private static final double SLOPE_STEP = 0.003;

    /** Result of a successful straightening. */
    static final class Result {
        final int[] pixels;
        final int width;
        final int height;
        /** Detected table corners in the source image: TL, TR, BR, BL as x,y pairs. */
        final double[] corners;

        Result(int[] pixels, int width, int height, double[] corners) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.corners = corners;
        }
    }

    private TableRectifier() {
    }

    /** Returns the straightened page, or {@code null} when no table frame was found. */
    static Result rectify(int[] px, int w, int h) {
        return rectify(px, w, h, Layout.DEFAULT);
    }

    static Result rectify(int[] px, int w, int h, Layout layout) {
        double[] corners = detectTable(px, w, h);
        if (corners == null) {
            return null;
        }
        return warp(px, w, h, corners, layout);
    }

    /** Straightens using corners chosen elsewhere (detected earlier, or dragged by the user). */
    static Result rectifyWith(int[] px, int w, int h, double[] corners, Layout layout) {
        return warp(px, w, h, corners, layout);
    }

    // ---------------------------------------------------------------- detection

    /** Corners TL, TR, BR, BL (x0,y0,...,x3,y3) in source pixels, or null. */
    static double[] detectTable(int[] px, int w, int h) {
        int ds = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) DETECT_SIZE));
        int sw = w / ds, sh = h / ds;
        if (sw < 50 || sh < 50) {
            return null;
        }
        // min-pool keeps thin ruled lines dark when shrinking
        int[] gray = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int m = 255;
                for (int dy = 0; dy < ds; dy++) {
                    int row = (y * ds + dy) * w + x * ds;
                    for (int dx = 0; dx < ds; dx++) {
                        m = Math.min(m, lum(px[row + dx]));
                    }
                }
                gray[y * sw + x] = m;
            }
        }
        int threshold = Math.max(90, Math.min(200, otsu(gray)));
        boolean[] dark = new boolean[sw * sh];
        for (int i = 0; i < dark.length; i++) {
            dark[i] = gray[i] < threshold;
        }

        int[] labels = new int[sw * sh];
        int[] best = largestComponent(dark, labels, sw, sh);
        if (best == null) {
            return null;
        }
        int label = best[0];
        int bx0 = best[1], by0 = best[2], bx1 = best[3], by1 = best[4];
        if (bx1 - bx0 < sw * 0.45 || by1 - by0 < sh * 0.25) {
            return null;
        }

        // outermost component pixel per column / row = points on the frame lines
        int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
        double[] topX = new double[bw], topY = new double[bw], botX = new double[bw], botY = new double[bw];
        int nTop = 0, nBot = 0;
        for (int x = bx0; x <= bx1; x++) {
            for (int y = by0; y <= by1; y++) {
                if (labels[y * sw + x] == label) {
                    topX[nTop] = x;
                    topY[nTop++] = y;
                    break;
                }
            }
            for (int y = by1; y >= by0; y--) {
                if (labels[y * sw + x] == label) {
                    botX[nBot] = x;
                    botY[nBot++] = y;
                    break;
                }
            }
        }
        double[] leftX = new double[bh], leftY = new double[bh], rightX = new double[bh], rightY = new double[bh];
        int nLeft = 0, nRight = 0;
        for (int y = by0; y <= by1; y++) {
            for (int x = bx0; x <= bx1; x++) {
                if (labels[y * sw + x] == label) {
                    leftX[nLeft] = x;
                    leftY[nLeft++] = y;
                    break;
                }
            }
            for (int x = bx1; x >= bx0; x--) {
                if (labels[y * sw + x] == label) {
                    rightX[nRight] = x;
                    rightY[nRight++] = y;
                    break;
                }
            }
        }
        // y = a*x + b for top/bottom, x = a*y + b for left/right
        double[] top = robustLine(topX, topY, nTop);
        double[] bottom = robustLine(botX, botY, nBot);
        double[] left = robustLine(leftY, leftX, nLeft);
        double[] right = robustLine(rightY, rightX, nRight);
        if (top == null || bottom == null || left == null || right == null) {
            return null;
        }
        // an edge cut off by the photo (or hidden by a stamp) gives scattered boundary points: fall
        // back to the outermost ruled line that really runs across the table in that direction
        Grid grid = new Grid(labels, label, sw, bx0, by0, bx1, by1);
        boolean topOk = coverage(grid, top, true) >= MIN_COVERAGE;
        boolean bottomOk = coverage(grid, bottom, true) >= MIN_COVERAGE;
        boolean leftOk = coverage(grid, left, false) >= MIN_COVERAGE;
        boolean rightOk = coverage(grid, right, false) >= MIN_COVERAGE;
        if (!topOk) top = outermostRule(grid, bottomOk ? bottom[0] : top[0], true, true);
        if (!bottomOk) bottom = outermostRule(grid, topOk ? top[0] : bottom[0], true, false);
        if (!leftOk) left = outermostRule(grid, rightOk ? right[0] : left[0], false, true);
        if (!rightOk) right = outermostRule(grid, leftOk ? left[0] : right[0], false, false);
        if (top == null || bottom == null || left == null || right == null) {
            return null;
        }

        double[] tl = intersect(top, left), tr = intersect(top, right);
        double[] br = intersect(bottom, right), bl = intersect(bottom, left);
        if (tl == null || tr == null || br == null || bl == null) {
            return null;
        }
        double[] c = {tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]};
        for (int i = 0; i < 8; i++) {
            c[i] = (c[i] + 0.5) * ds;
        }
        return plausible(c, w, h) ? c : null;
    }

    /** Label 8-connected components; returns {label, x0, y0, x1, y1} of the widest-spanning one. */
    private static int[] largestComponent(boolean[] dark, int[] labels, int sw, int sh) {
        int[] queue = new int[sw * sh];
        int next = 0;
        int[] best = null;
        long bestArea = 0;
        for (int start = 0; start < dark.length; start++) {
            if (!dark[start] || labels[start] != 0) {
                continue;
            }
            next++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            labels[start] = next;
            int x0 = sw, y0 = sh, x1 = -1, y1 = -1;
            while (head < tail) {
                int i = queue[head++];
                int x = i % sw, y = i / sw;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= sh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= sw) continue;
                        int j = yy * sw + xx;
                        if (dark[j] && labels[j] == 0) {
                            labels[j] = next;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
            if (area > bestArea) {
                bestArea = area;
                best = new int[]{next, x0, y0, x1, y1};
            }
        }
        return best;
    }

    /**
     * Theil–Sen fit of v = a*u + b (robust to handwriting poking out past the frame), refined
     * by least squares on the points close to that line. Returns {a, b} or null.
     */
    private static double[] robustLine(double[] u, double[] v, int n) {
        if (n < 20) {
            return null;
        }
        double uMin = Double.MAX_VALUE, uMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            uMin = Math.min(uMin, u[i]);
            uMax = Math.max(uMax, u[i]);
        }
        double minGap = (uMax - uMin) / 4;
        Random random = new Random(42);
        double[] slopes = new double[3000];
        int ns = 0;
        for (int t = 0; t < 20000 && ns < slopes.length; t++) {
            int i = random.nextInt(n), j = random.nextInt(n);
            double du = u[j] - u[i];
            if (Math.abs(du) >= minGap) {
                slopes[ns++] = (v[j] - v[i]) / du;
            }
        }
        if (ns < 10) {
            return null;
        }
        double a = median(slopes, ns);
        double[] intercepts = new double[n];
        for (int i = 0; i < n; i++) {
            intercepts[i] = v[i] - a * u[i];
        }
        double b = median(intercepts, n);

        for (int iter = 0; iter < 2; iter++) {
            double su = 0, sv = 0, suu = 0, suv = 0;
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (Math.abs(v[i] - (a * u[i] + b)) <= 2.5) {
                    su += u[i];
                    sv += v[i];
                    suu += u[i] * u[i];
                    suv += u[i] * v[i];
                    m++;
                }
            }
            double det = m * suu - su * su;
            if (m < 10 || Math.abs(det) < 1e-9) {
                break;
            }
            a = (m * suv - su * sv) / det;
            b = (sv - a * su) / m;
        }
        return new double[]{a, b};
    }

    /** The table component inside the detection image. */
    private static final class Grid {
        final int[] labels;
        final int label, sw, x0, y0, x1, y1;

        Grid(int[] labels, int label, int sw, int x0, int y0, int x1, int y1) {
            this.labels = labels;
            this.label = label;
            this.sw = sw;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        /** Is there table ink within 1 px across the line at (along, offset)? */
        boolean hit(boolean horizontal, int along, double across) {
            int c = (int) Math.round(across);
            for (int d = -1; d <= 1; d++) {
                int x = horizontal ? along : c + d;
                int y = horizontal ? c + d : along;
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && labels[y * sw + x] == label) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Fraction of the table span along which the line actually lies on table ink. */
    private static double coverage(Grid g, double[] line, boolean horizontal) {
        int from = horizontal ? g.x0 : g.y0, to = horizontal ? g.x1 : g.y1;
        int hits = 0;
        for (int t = from; t <= to; t++) {
            if (g.hit(horizontal, t, line[0] * t + line[1])) {
                hits++;
            }
        }
        return hits / (double) (to - from + 1);
    }

    /**
     * Sweeps a line inward from one side of the table and returns the first position that lies on
     * ink along most of the span (a full ruled line), refit to that ink. Perspective tilts rules
     * differently across the page, so slopes around the opposite edge's slope are tried as well.
     */
    private static double[] outermostRule(Grid g, double slope, boolean horizontal, boolean fromLow) {
        int from = horizontal ? g.x0 : g.y0, to = horizontal ? g.x1 : g.y1;
        int acrossLow = horizontal ? g.y0 : g.x0, acrossHigh = horizontal ? g.y1 : g.x1;
        double mid = (from + to) / 2.0;
        double stepC = 0.5;
        int steps = (int) ((acrossHigh - acrossLow) / stepC);
        for (int s = 0; s <= steps; s++) {
            // line position where it crosses the middle of the span
            double centre = fromLow ? acrossLow + s * stepC : acrossHigh - s * stepC;
            double[] bestLine = null;
            double bestCoverage = RULE_COVERAGE;
            for (int k = -SLOPE_STEPS; k <= SLOPE_STEPS; k++) {
                double a = slope + k * SLOPE_STEP;
                double[] line = {a, centre - a * mid};
                double cov = coverage(g, line, horizontal);
                if (cov >= bestCoverage) {
                    bestCoverage = cov;
                    bestLine = line;
                }
            }
            if (bestLine != null) {
                return refit(g, bestLine, horizontal);
            }
        }
        return null;
    }

    /** Least-squares refit to the ink found within 2 px of the line. */
    private static double[] refit(Grid g, double[] line, boolean horizontal) {
        int from = horizontal ? g.x0 : g.y0, to = horizontal ? g.x1 : g.y1;
        double su = 0, sv = 0, suu = 0, suv = 0;
        int m = 0;
        for (int t = from; t <= to; t++) {
            double centre = line[0] * t + line[1];
            for (int d = -2; d <= 2; d++) {
                int c = (int) Math.round(centre) + d;
                int x = horizontal ? t : c, y = horizontal ? c : t;
                if (x >= g.x0 && x <= g.x1 && y >= g.y0 && y <= g.y1 && g.labels[y * g.sw + x] == g.label) {
                    su += t;
                    sv += c;
                    suu += (double) t * t;
                    suv += (double) t * c;
                    m++;
                }
            }
        }
        double det = m * suu - su * su;
        if (m < 10 || Math.abs(det) < 1e-9) {
            return line;
        }
        double a = (m * suv - su * sv) / det;
        return new double[]{a, (sv - a * su) / m};
    }

    /** h = {a, b}: y = a*x + b ; v = {c, d}: x = c*y + d. */
    private static double[] intersect(double[] hLine, double[] vLine) {
        double a = hLine[0], b = hLine[1], c = vLine[0], d = vLine[1];
        double den = 1 - a * c;
        if (Math.abs(den) < 1e-9) {
            return null;
        }
        double y = (a * d + b) / den;
        double x = c * y + d;
        return new double[]{x, y};
    }

    /** Convex, roughly rectangular, big enough. */
    private static boolean plausible(double[] c, int w, int h) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += c[2 * i] * c[2 * j + 1] - c[2 * j] * c[2 * i + 1];
        }
        area = Math.abs(area) / 2;
        if (area < 0.2 * w * h) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            double[] p = {c[2 * i], c[2 * i + 1]};
            double[] prev = {c[2 * ((i + 3) % 4)], c[2 * ((i + 3) % 4) + 1]};
            double[] next = {c[2 * ((i + 1) % 4)], c[2 * ((i + 1) % 4) + 1]};
            double ax = prev[0] - p[0], ay = prev[1] - p[1], bx = next[0] - p[0], by = next[1] - p[1];
            double cos = (ax * bx + ay * by) / (Math.hypot(ax, ay) * Math.hypot(bx, by));
            if (Math.abs(cos) > Math.cos(Math.toRadians(70))) {
                return false; // corner angle outside 70°–110°
            }
            if (p[0] < -0.05 * w || p[0] > 1.05 * w || p[1] < -0.05 * h || p[1] > 1.05 * h) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- warping

    private static Result warp(int[] px, int w, int h, double[] c, Layout layout) {
        double tableW = (dist(c, 0, 1) + dist(c, 3, 2)) / 2;
        double tableH = (dist(c, 0, 3) + dist(c, 1, 2)) / 2;
        double[] rect = {0, 0, tableW, 0, tableW, tableH, 0, tableH};
        double[] toSource = homography(rect, c);
        double[] toTable = homography(c, rect);
        if (toSource == null || toTable == null) {
            return null;
        }

        // extent of everything on the page (title, header, notes) in table coordinates
        double[] box = contentBox(px, w, h, toTable, tableW, tableH);
        double contentW = box[2] - box[0], contentH = box[3] - box[1];

        // uniform: the page is sized by the table (plus the widest spill content may have), so the
        // table takes the same share of the page width on every page and sits centred
        double spanW = layout.uniform ? tableW * (1 + 2 * SIDE_SPILL) : contentW;
        double outW = spanW / (1 - 2 * layout.side);
        double outH;
        if (layout.ratio > 0) {
            outH = outW * layout.ratio;
            if (contentH > outH * (1 - 2 * layout.top)) {
                outH = contentH / (1 - 2 * layout.top);
                outW = outH / layout.ratio;
            }
        } else {
            outH = contentH + 2 * layout.top * outW;
        }
        double scale = Math.min(1, layout.maxHeight / outH);
        int ow = Math.max(1, (int) Math.round(outW * scale)), oh = Math.max(1, (int) Math.round(outH * scale));
        double offX = layout.uniform ? (outW - tableW) / 2 : (outW - contentW) / 2 - box[0];
        double topGap = layout.ratio > 0 ? outH * layout.top : layout.top * outW;
        double offY = (layout.center ? (outH - contentH) / 2 : topGap) - box[1];

        int[] out = new int[ow * oh];
        double[] m = toSource;
        for (int v = 0; v < oh; v++) {
            double ty = (v + 0.5) / scale - offY;
            // projective numerators/denominator are linear in tx: step them along the row
            double tx0 = 0.5 / scale - offX, step = 1 / scale;
            double nx = m[0] * tx0 + m[1] * ty + m[2], dnx = m[0] * step;
            double ny = m[3] * tx0 + m[4] * ty + m[5], dny = m[3] * step;
            double dd = m[6] * tx0 + m[7] * ty + 1, ddd = m[6] * step;
            int base = v * ow;
            for (int u = 0; u < ow; u++) {
                double sx = nx / dd - 0.5, sy = ny / dd - 0.5;
                out[base + u] = sample(px, w, h, sx, sy);
                nx += dnx;
                ny += dny;
                dd += ddd;
            }
        }
        return new Result(out, ow, oh, c);
    }

    /** Bounding box {x0, y0, x1, y1} in table coordinates of the dark content on the page. */
    private static double[] contentBox(int[] px, int w, int h, double[] m, double tableW, double tableH) {
        int step = Math.max(2, Math.max(w, h) / 900);
        int bandX = w / 100, bandY = h / 100;
        int n = 0;
        double[] xs = new double[(w / step + 1) * (h / step + 1)];
        double[] ys = new double[xs.length];
        for (int y = bandY; y < h - bandY; y += step) {
            for (int x = bandX; x < w - bandX; x += step) {
                if (lum(px[y * w + x]) < 140) {
                    double d = m[6] * x + m[7] * y + 1;
                    xs[n] = (m[0] * x + m[1] * y + m[2]) / d;
                    ys[n] = (m[3] * x + m[4] * y + m[5]) / d;
                    n++;
                }
            }
        }
        double x0 = 0, y0 = 0, x1 = tableW, y1 = tableH;
        if (n > 50) {
            double[] sx = Arrays.copyOf(xs, n), sy = Arrays.copyOf(ys, n);
            Arrays.sort(sx);
            Arrays.sort(sy);
            int lo = (int) (n * 0.002), hi = n - 1 - lo;
            x0 = Math.min(x0, sx[lo]);
            x1 = Math.max(x1, sx[hi]);
            y0 = Math.min(y0, sy[lo]);
            y1 = Math.max(y1, sy[hi]);
        }
        // never let stray specks blow the page up: at most a modest band around the table
        x0 = Math.max(x0, -SIDE_SPILL * tableW);
        x1 = Math.min(x1, (1 + SIDE_SPILL) * tableW);
        y0 = Math.max(y0, -0.3 * tableH);
        y1 = Math.min(y1, 1.4 * tableH);
        return new double[]{x0, y0, x1, y1};
    }

    private static int sample(int[] px, int w, int h, double x, double y) {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) {
            return WHITE;
        }
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        int fx = (int) ((x - x0) * 256), fy = (int) ((y - y0) * 256);
        int a = px[y0 * w + x0], b = px[y0 * w + x1], c = px[y1 * w + x0], d = px[y1 * w + x1];
        int r = mix((a >> 16) & 0xFF, (b >> 16) & 0xFF, (c >> 16) & 0xFF, (d >> 16) & 0xFF, fx, fy);
        int g = mix((a >> 8) & 0xFF, (b >> 8) & 0xFF, (c >> 8) & 0xFF, (d >> 8) & 0xFF, fx, fy);
        int bl = mix(a & 0xFF, b & 0xFF, c & 0xFF, d & 0xFF, fx, fy);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int mix(int a, int b, int c, int d, int fx, int fy) {
        int top = a * (256 - fx) + b * fx;
        int bottom = c * (256 - fx) + d * fx;
        return (top * (256 - fy) + bottom * fy) >> 16;
    }

    /** 3x3 homography (row-major, h33 = 1) mapping the 4 points {@code from} onto {@code to}. */
    static double[] homography(double[] from, double[] to) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = from[2 * i], y = from[2 * i + 1], u = to[2 * i], v = to[2 * i + 1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int r = col + 1; r < 8; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int r = 0; r < 8; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                for (int k = col; k < 9; k++) {
                    a[r][k] -= f * a[col][k];
                }
            }
        }
        double[] hm = new double[8];
        for (int i = 0; i < 8; i++) {
            hm[i] = a[i][8] / a[i][i];
        }
        return hm;
    }

    // ---------------------------------------------------------------- helpers

    private static double dist(double[] c, int i, int j) {
        return Math.hypot(c[2 * i] - c[2 * j], c[2 * i + 1] - c[2 * j + 1]);
    }

    private static double median(double[] values, int n) {
        double[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy);
        return copy[n / 2];
    }

    private static int otsu(int[] gray) {
        int[] hist = new int[256];
        for (int g : gray) hist[g]++;
        long total = gray.length, sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * hist[i];
        long sumB = 0, wB = 0;
        double best = -1;
        int threshold = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = sumB / (double) wB, mF = (sum - sumB) / (double) wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                threshold = t;
            }
        }
        return threshold;
    }

    private static int lum(int c) {
        return (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
    }
}
