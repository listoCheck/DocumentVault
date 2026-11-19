package ru.documentvault.android;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.List;

/** Extracts selectable word coordinates from a PDF page without sending it off-device. */
final class PdfTextLayer {
    private PdfTextLayer() { }
    static synchronized void init(Context context) { PDFBoxResourceLoader.init(context.getApplicationContext()); }
    static JSONObject page(PDDocument document, int index) throws Exception {
        com.tom_roush.pdfbox.pdmodel.PDPage page = document.getPage(index);
        com.tom_roush.pdfbox.pdmodel.common.PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        final double width = rotation == 90 || rotation == 270 ? box.getHeight() : box.getWidth();
        final double height = rotation == 90 || rotation == 270 ? box.getWidth() : box.getHeight();
        JSONArray words = new JSONArray();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
                StringBuilder value = new StringBuilder(); double l = 0, t = 0, r = 0, b = 0; TextPosition previous = null;
                for (int i = 0; i <= positions.size(); i++) {
                    TextPosition p = i == positions.size() ? null : positions.get(i);
                    String current = p == null || p.getUnicode() == null ? "" : p.getUnicode();
                    boolean gap = p != null && previous != null && (p.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj()) > Math.max(1, p.getWidthOfSpace() * .35) || Math.abs(p.getYDirAdj() - previous.getYDirAdj()) > Math.max(1, p.getHeightDir()));
                    if (p == null || current.trim().isEmpty() || gap) {
                        if (value.length() > 0 && width > 0 && height > 0 && words.length() < 3000) {
                            double[] coords;
                            switch (rotation) {
                                case 90: coords = new double[]{box.getHeight() - b, l, box.getHeight() - t, r}; break;
                                case 180: coords = new double[]{box.getWidth() - r, box.getHeight() - b, box.getWidth() - l, box.getHeight() - t}; break;
                                case 270: coords = new double[]{t, box.getWidth() - r, b, box.getWidth() - l}; break;
                                default: coords = new double[]{l, t, r, b};
                            }
                            double x1 = Math.max(0, Math.min(1, coords[0] / width)), y1 = Math.max(0, Math.min(1, coords[1] / height)), x2 = Math.max(0, Math.min(1, coords[2] / width)), y2 = Math.max(0, Math.min(1, coords[3] / height));
                            if (x2 > x1 && y2 > y1) try { words.put(new JSONArray().put(value.toString()).put(x1).put(y1).put(x2).put(y2)); } catch (org.json.JSONException error) { throw new IOException(error); }
                        }
                        value.setLength(0); previous = null;
                        if (p == null || current.trim().isEmpty()) continue;
                    }
                    double x = p.getXDirAdj(), y = p.getYDirAdj(), h = Math.max(1, p.getHeightDir());
                    if (value.length() == 0) { l = x; t = y - h; r = x + p.getWidthDirAdj(); b = y; }
                    else { l = Math.min(l, x); t = Math.min(t, y - h); r = Math.max(r, x + p.getWidthDirAdj()); b = Math.max(b, y); }
                    value.append(current); previous = p;
                }
            }
        };
        stripper.setSortByPosition(true); stripper.setStartPage(index + 1); stripper.setEndPage(index + 1); stripper.getText(document);
        return new JSONObject().put("version", 1).put("words", words);
    }
    static String text(PDDocument document, int index) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper(); stripper.setSortByPosition(true); stripper.setStartPage(index + 1); stripper.setEndPage(index + 1); return stripper.getText(document).trim();
    }
}
