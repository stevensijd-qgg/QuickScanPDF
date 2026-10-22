package com.quickscan.pdf;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal streaming PDF writer: one JPEG per page, embedded as-is (DCTDecode), page width A4.
 * Keeps file size equal to the JPEGs and needs no PDF library.
 */
final class JpegPdfWriter {

    private static final float PAGE_WIDTH = 595.28f;

    private final CountingStream out;
    /** Byte offset of object n is at index n - 1. Objects 1 (catalog) and 2 (pages) are written last. */
    private final List<Long> offsets = new ArrayList<>();
    private final List<Integer> pageObjects = new ArrayList<>();

    JpegPdfWriter(OutputStream stream) throws IOException {
        out = new CountingStream(stream);
        write("%PDF-1.4\n");
        out.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
        offsets.add(0L);
        offsets.add(0L);
    }

    void addPage(byte[] jpeg, int width, int height) throws IOException {
        addPage(jpeg, width, height, PAGE_WIDTH);
    }

    /** @param pageWidth page width in points; the height follows the image's shape */
    void addPage(byte[] jpeg, int width, int height, float pageWidth) throws IOException {
        float pageHeight = pageWidth * height / width;

        int image = beginObject();
        write("<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
        out.write(jpeg);
        write("\nendstream\nendobj\n");

        byte[] content = String.format(Locale.US, "q %.2f 0 0 %.2f 0 0 cm /Im0 Do Q", pageWidth, pageHeight)
                .getBytes(StandardCharsets.US_ASCII);
        int contents = beginObject();
        write("<< /Length " + content.length + " >>\nstream\n");
        out.write(content);
        write("\nendstream\nendobj\n");

        int page = beginObject();
        write(String.format(Locale.US,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << /XObject << /Im0 %d 0 R >> >> /Contents %d 0 R >>\nendobj\n",
                pageWidth, pageHeight, image, contents));
        pageObjects.add(page);
    }

    void finish() throws IOException {
        offsets.set(1, out.count);
        StringBuilder kids = new StringBuilder();
        for (int page : pageObjects) {
            kids.append(page).append(" 0 R ");
        }
        write("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + pageObjects.size() + " >>\nendobj\n");

        offsets.set(0, out.count);
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        long xref = out.count;
        StringBuilder table = new StringBuilder();
        table.append("xref\n0 ").append(offsets.size() + 1).append('\n');
        table.append("0000000000 65535 f \n");
        for (long offset : offsets) {
            table.append(String.format(Locale.US, "%010d 00000 n \n", offset));
        }
        table.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\n");
        table.append("startxref\n").append(xref).append("\n%%EOF\n");
        write(table.toString());
        out.flush();
    }

    /** Bytes written so far; after {@link #finish()} this is the file size. */
    long size() {
        return out.count;
    }

    private int beginObject() throws IOException {
        offsets.add(out.count);
        int number = offsets.size();
        write(number + " 0 obj\n");
        return number;
    }

    private void write(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
