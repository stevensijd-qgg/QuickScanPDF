package com.quickscan.pdf;

import java.util.Arrays;

/**
 * Cleans a scanned page in place: wipes the strips of desk / shadow that the auto crop left
 * along the edges, then flattens the paper background to pure white while keeping ink colours.
 * Works on ARGB ints with no Android dependency, so it can be tested on a desktop JVM.
 */
final class PaperCleaner {

    private static final int WHITE = 0xFFFFFFFF;
    /** How far in from each edge leftover background is searched for, as a fraction of the page. */
    private static final double MAX_BORDER = 0.10;
    /** Outer frame that is always wiped: thin page-edge lines and shadows sit in here. */
    private static final double FRAME = 0.008;
    /** Extra margin wiped past a detected strip. */
    private static final double PAD = 0.006;
    /** How far past the wiped strip a thin edge-parallel line is still chased. */
    private static final double CHASE = 0.015;
    /** Each edge is measured in this many pieces so a tilted (wedge-shaped) strip is followed. */
    private static final int SEGMENTS = 16;
    /** Side of the square searched at each corner, as a fraction of the shorter page side. */
    private static final double CORNER = 0.08;
    /** Pixels non-paper specks are grown by so they join up while flooding a corner. */
    private static final int CORNER_GROW = 4;

    private PaperCleaner() {
    }

    static void clean(int[] px, int w, int h) {
        int paper = estimatePaperColor(px, w, h);
        wipeBorders(px, w, h, paper);
        flattenBackground(px, w, h);
    }

    /** Average colour of the brightest quarter of the page centre. */
    static int estimatePaperColor(int[] px, int w, int h) {
        int[] hist = new int[256];
        int x0 = w / 5, x1 = w - w / 5, y0 = h / 5, y1 = h - h / 5;
        int n = 0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                hist[lum(px[y * w + x])]++;
                n++;
            }
        }
        int threshold = percentile(hist, n, 0.75);
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                int c = px[y * w + x];
                if (lum(c) >= threshold) {
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    count++;
                }
            }
        }
        if (count == 0) {
            return WHITE;
        }
        return rgb((int) (r / count), (int) (g / count), (int) (b / count));
    }

    // Sides: 0 = left, 1 = right, 2 = top, 3 = bottom
    static void wipeBorders(int[] px, int w, int h, int paper) {
        int paperLum = lum(paper);
        int paperChroma = chroma(paper);
        int[][] depths = new int[4][];
        for (int side = 0; side < 4; side++) {
            depths[side] = measureSide(px, w, h, side, paperLum, paperChroma);
        }
        boolean[][] corners = new boolean[4][];
        int size = Math.max(8, (int) (Math.min(w, h) * CORNER));
        for (int corner = 0; corner < 4; corner++) {
            corners[corner] = traceCorner(px, w, h, corner, size, depths, paperLum, paperChroma);
        }
        for (int side = 0; side < 4; side++) {
            wipeSide(px, w, h, side, depths[side]);
        }
        for (int corner = 0; corner < 4; corner++) {
            boolean[] mask = corners[corner];
            for (int v = 0; v < size; v++) {
                for (int u = 0; u < size; u++) {
                    if (mask[v * size + u]) {
                        px[cornerIndex(w, h, corner, u, v)] = WHITE;
                    }
                }
            }
        }
    }

    /**
     * Rounded paper corners leave a curved patch of background deeper than the straight strip.
     * Inside a square at each corner, flood from the corner through the strips that will be wiped
     * and through (slightly grown) non-paper pixels, so the patch connected to the edge goes too.
     * Corners: 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right.
     */
    private static boolean[] traceCorner(int[] px, int w, int h, int corner, int size, int[][] depths,
                                         int paperLum, int paperChroma) {
        boolean right = corner == 1 || corner == 3;
        boolean bottom = corner >= 2;
        int[] sideDepth = depths[right ? 1 : 0];
        int[] edgeDepth = depths[bottom ? 3 : 2];

        boolean[] open = new boolean[size * size];
        boolean[] ink = new boolean[size * size];
        for (int v = 0; v < size; v++) {
            for (int u = 0; u < size; u++) {
                ink[v * size + u] = isNonPaper(px[cornerIndex(w, h, corner, u, v)], paperLum, paperChroma);
            }
        }
        boolean[] grown = grow(ink, size, CORNER_GROW);
        for (int v = 0; v < size; v++) {
            int y = bottom ? h - 1 - v : v;
            for (int u = 0; u < size; u++) {
                int x = right ? w - 1 - u : u;
                int i = v * size + u;
                open[i] = grown[i] || u < sideDepth[y] || v < edgeDepth[x];
            }
        }

        boolean[] seen = new boolean[size * size];
        if (!open[0]) {
            return seen;
        }
        int[] queue = new int[size * size];
        int head = 0, tail = 0;
        queue[tail++] = 0;
        seen[0] = true;
        while (head < tail) {
            int i = queue[head++];
            int u = i % size, v = i / size;
            if (u > 0) tail = visit(i - 1, open, seen, queue, tail);
            if (u < size - 1) tail = visit(i + 1, open, seen, queue, tail);
            if (v > 0) tail = visit(i - size, open, seen, queue, tail);
            if (v < size - 1) tail = visit(i + size, open, seen, queue, tail);
        }
        return seen;
    }

    private static int visit(int i, boolean[] open, boolean[] seen, int[] queue, int tail) {
        if (open[i] && !seen[i]) {
            seen[i] = true;
            queue[tail++] = i;
        }
        return tail;
    }

    /** Square dilation, so the speckled texture of a desk becomes one connected patch. */
    private static boolean[] grow(boolean[] mask, int size, int radius) {
        boolean[] horizontal = new boolean[mask.length];
        for (int v = 0; v < size; v++) {
            int last = -radius - 1;
            for (int u = 0; u < size; u++) {
                if (mask[v * size + u]) last = u;
                horizontal[v * size + u] = u - last <= radius;
            }
            last = size + radius + 1;
            for (int u = size - 1; u >= 0; u--) {
                if (mask[v * size + u]) last = u;
                if (last - u <= radius) horizontal[v * size + u] = true;
            }
        }
        boolean[] out = new boolean[mask.length];
        for (int u = 0; u < size; u++) {
            int last = -radius - 1;
            for (int v = 0; v < size; v++) {
                if (horizontal[v * size + u]) last = v;
                out[v * size + u] = v - last <= radius;
            }
            last = size + radius + 1;
            for (int v = size - 1; v >= 0; v--) {
                if (horizontal[v * size + u]) last = v;
                if (last - v <= radius) out[v * size + u] = true;
            }
        }
        return out;
    }

    private static int cornerIndex(int w, int h, int corner, int u, int v) {
        int x = (corner == 1 || corner == 3) ? w - 1 - u : u;
        int y = corner >= 2 ? h - 1 - v : v;
        return y * w + x;
    }

    /** Per position along the side: how many pixels from the edge to wipe. */
    private static int[] measureSide(int[] px, int w, int h, int side, int paperLum, int paperChroma) {
        boolean vertical = side < 2;
        int length = vertical ? h : w;
        int across = vertical ? w : h;
        int band = Math.max(4, (int) (across * MAX_BORDER));
        int frame = Math.max(2, (int) Math.round(across * FRAME));
        int pad = (int) Math.round(across * PAD);
        int chase = (int) Math.round(across * CHASE);
        int segLen = (length + SEGMENTS - 1) / SEGMENTS;

        int[] segDepth = new int[SEGMENTS];
        for (int k = 0; k < SEGMENTS; k++) {
            int start = k * segLen;
            int end = Math.min(length, start + segLen);
            if (end <= start) {
                continue;
            }
            double[] frac = new double[band];
            double sum = 0;
            int depth = 0;
            for (int i = 0; i < band; i++) {
                int hits = 0;
                for (int p = start; p < end; p++) {
                    if (isNonPaper(px[index(w, h, side, p, i)], paperLum, paperChroma)) {
                        hits++;
                    }
                }
                frac[i] = hits / (double) (end - start);
                sum += frac[i];
                // a real leftover strip starts at the edge and stays mostly non-paper; a ruled line
                // or handwriting near the edge has clean paper between it and the edge
                if (frac[i] >= 0.35 && sum / (i + 1) >= 0.45) {
                    depth = i + 1;
                }
            }
            int base = depth > 0 ? depth + pad : frame;
            // just inside a strip there is often a thin line parallel to the edge (the paper's own
            // edge, the facing page, a shadow): chase it a little further in
            int last = 0;
            for (int i = base; i < Math.min(band, base + chase); i++) {
                if (frac[i] >= 0.12) {
                    last = i + 1;
                }
            }
            segDepth[k] = last > 0 ? Math.max(base, last + pad) : base;
        }

        int[] depth = new int[length];
        for (int p = 0; p < length; p++) {
            double f = (p + 0.5) / segLen - 0.5;
            int k0 = Math.max(0, Math.min(SEGMENTS - 1, (int) Math.floor(f)));
            int k1 = Math.min(SEGMENTS - 1, k0 + 1);
            double t = Math.max(0, Math.min(1, f - k0));
            double d = segDepth[k0] * (1 - t) + segDepth[k1] * t;
            // never undercut the segment the pixel sits in, so corners stay covered
            d = Math.max(d, segDepth[Math.min(SEGMENTS - 1, p / segLen)]);
            depth[p] = Math.min(band + pad, Math.max(frame, (int) Math.ceil(d)));
        }
        return depth;
    }

    private static void wipeSide(int[] px, int w, int h, int side, int[] depth) {
        for (int p = 0; p < depth.length; p++) {
            for (int i = 0; i < depth[p]; i++) {
                px[index(w, h, side, p, i)] = WHITE;
            }
        }
    }

    private static int index(int w, int h, int side, int along, int inward) {
        switch (side) {
            case 0:
                return along * w + inward;
            case 1:
                return along * w + (w - 1 - inward);
            case 2:
                return inward * w + along;
            default:
                return (h - 1 - inward) * w + along;
        }
    }

    private static boolean isNonPaper(int c, int paperLum, int paperChroma) {
        return paperLum - lum(c) > 45 || chroma(c) - paperChroma > 30;
    }

    /**
     * Divides every pixel by a smooth estimate of the local paper colour, so tinted paper,
     * uneven light and leftover shadows become white, then snaps near-white pixels to white.
     */
    static void flattenBackground(int[] px, int w, int h) {
        int cell = Math.max(16, Math.min(w, h) / 40);
        int gw = (w + cell - 1) / cell;
        int gh = (h + cell - 1) / cell;
        int[] bgR = new int[gw * gh];
        int[] bgG = new int[gw * gh];
        int[] bgB = new int[gw * gh];
        int[] hist = new int[256];

        for (int cy = 0; cy < gh; cy++) {
            int y0 = cy * cell, y1 = Math.min(h, y0 + cell);
            for (int cx = 0; cx < gw; cx++) {
                int x0 = cx * cell, x1 = Math.min(w, x0 + cell);
                Arrays.fill(hist, 0);
                int n = 0;
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        hist[lum(px[y * w + x])]++;
                        n++;
                    }
                }
                int threshold = percentile(hist, n, 0.9);
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        int c = px[y * w + x];
                        if (lum(c) >= threshold) {
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            count++;
                        }
                    }
                }
                int i = cy * gw + cx;
                bgR[i] = count > 0 ? (int) (r / count) : 255;
                bgG[i] = count > 0 ? (int) (g / count) : 255;
                bgB[i] = count > 0 ? (int) (b / count) : 255;
            }
        }
        // max first so cells full of ink take their neighbours' paper colour, then soften
        bgR = blur3(max3(bgR, gw, gh), gw, gh);
        bgG = blur3(max3(bgG, gw, gh), gw, gh);
        bgB = blur3(max3(bgB, gw, gh), gw, gh);

        int[] xi0 = new int[w];
        int[] xi1 = new int[w];
        int[] xw = new int[w];
        for (int x = 0; x < w; x++) {
            double f = (x + 0.5) / cell - 0.5;
            int k = Math.max(0, Math.min(gw - 1, (int) Math.floor(f)));
            xi0[x] = k;
            xi1[x] = Math.min(gw - 1, k + 1);
            xw[x] = (int) Math.round(Math.max(0, Math.min(1, f - k)) * 256);
        }

        for (int y = 0; y < h; y++) {
            double f = (y + 0.5) / cell - 0.5;
            int k = Math.max(0, Math.min(gh - 1, (int) Math.floor(f)));
            int row0 = k * gw;
            int row1 = Math.min(gh - 1, k + 1) * gw;
            int wy = (int) Math.round(Math.max(0, Math.min(1, f - k)) * 256);
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[base + x];
                if (c == WHITE) {
                    continue;
                }
                int a = row0 + xi0[x], b = row0 + xi1[x], cc = row1 + xi0[x], d = row1 + xi1[x];
                int wx = xw[x];
                int r = scale((c >> 16) & 0xFF, bilerp(bgR, a, b, cc, d, wx, wy));
                int g = scale((c >> 8) & 0xFF, bilerp(bgG, a, b, cc, d, wx, wy));
                int bl = scale(c & 0xFF, bilerp(bgB, a, b, cc, d, wx, wy));
                int max = Math.max(r, Math.max(g, bl));
                int min = Math.min(r, Math.min(g, bl));
                int l = (r * 77 + g * 150 + bl * 29) >> 8;
                px[base + x] = (l >= 220 && max - min < 40) ? WHITE : rgb(r, g, bl);
            }
        }
    }

    private static int bilerp(int[] v, int a, int b, int c, int d, int wx, int wy) {
        int top = v[a] * (256 - wx) + v[b] * wx;
        int bottom = v[c] * (256 - wx) + v[d] * wx;
        return (top * (256 - wy) + bottom * wy) >> 16;
    }

    private static int scale(int value, int background) {
        int bg = Math.max(64, background);
        return Math.min(255, value * 255 / bg);
    }

    private static int[] max3(int[] v, int gw, int gh) {
        int[] out = new int[v.length];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int m = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= gh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= gw) continue;
                        m = Math.max(m, v[yy * gw + xx]);
                    }
                }
                out[y * gw + x] = m;
            }
        }
        return out;
    }

    private static int[] blur3(int[] v, int gw, int gh) {
        int[] out = new int[v.length];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int s = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= gh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= gw) continue;
                        s += v[yy * gw + xx];
                        n++;
                    }
                }
                out[y * gw + x] = s / n;
            }
        }
        return out;
    }

    private static int percentile(int[] hist, int total, double q) {
        int target = (int) (total * q);
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc > target) {
                return i;
            }
        }
        return 255;
    }

    private static int lum(int c) {
        return (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
    }

    private static int chroma(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
