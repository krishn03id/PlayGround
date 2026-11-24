package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

final class DocumentSession implements Closeable {
    private final File source;
    private final Book book;
    private final LruCache<Integer, TextPaginator.Page> textCache = new LruCache<>(24);
    private PdfRenderer renderer;
    private PDDocument textDocument;
    private JSONArray pages;
    private JSONArray chapterCache;
    private boolean closed;

    DocumentSession(Context context, Book book) throws Exception {
        this.book = book;
        source = BookImporter.source(context, book);
        if ("pdf".equals(book.format)) {
            PDFBoxResourceLoader.init(context.getApplicationContext());
            renderer = new PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY));
        } else {
            pages = new JSONArray(BookImporter.readText(new File(BookImporter.directory(context, book.id), "pages.json"), 40 * 1024 * 1024));
        }
    }

    synchronized TextPaginator.Page text(int page) throws Exception {
        ensureOpen();
        TextPaginator.Page cached = textCache.get(page);
        if (cached != null) return cached;
        TextPaginator.Page result;
        if (pages != null) {
            JSONObject json = pages.getJSONObject(page);
            result = new TextPaginator.Page(json.optString("heading"), json.optString("text"));
        } else {
            if (textDocument == null) textDocument = PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(page + 1);
            stripper.setEndPage(page + 1);
            String text = stripper.getText(textDocument).trim();
            if (text.length() > 150000) text = text.substring(0, 150000);
            result = new TextPaginator.Page("", text);
        }
        textCache.put(page, result);
        return result;
    }

    synchronized Bitmap render(int index, int targetWidth) throws IOException {
        ensureOpen();
        if (renderer == null) throw new IOException("This is a text document");
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int width = Math.max(300, Math.min(2800, targetWidth));
            int height = Math.max(1, Math.round(width * (float)page.getHeight() / page.getWidth()));
            double factor = Math.min(1.0, Math.sqrt(6_000_000.0 / ((long)width * height)));
            width = Math.max(1, (int)(width * factor));
            height = Math.max(1, (int)(height * factor));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    synchronized JSONArray chapters() throws Exception {
        ensureOpen();
        if (chapterCache != null) return chapterCache;
        JSONArray result = new JSONArray();
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                String heading = pages.getJSONObject(i).optString("heading");
                if (!heading.isEmpty()) result.put(new JSONObject().put("title", heading).put("page", i));
            }
        } else {
            if (textDocument == null) textDocument = PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly());
            PDDocumentOutline outline = textDocument.getDocumentCatalog().getDocumentOutline();
            if (outline != null) collectOutline(outline.getFirstChild(), result, 0, new int[]{0});
        }
        chapterCache = result;
        return result;
    }

    private void collectOutline(PDOutlineItem item, JSONArray result, int depth, int[] visited) throws Exception {
        while (item != null && result.length() < 200 && visited[0]++ < 400) {
            PDPage destination = item.findDestinationPage(textDocument);
            if (destination != null) {
                int index = textDocument.getPages().indexOf(destination);
                if (index >= 0) result.put(new JSONObject().put("title", item.getTitle()).put("page", index));
            }
            if (depth < 3) collectOutline(item.getFirstChild(), result, depth + 1, visited);
            item = item.getNextSibling();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Book has been closed");
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        if (renderer != null) { renderer.close(); renderer = null; }
        if (textDocument != null) { textDocument.close(); textDocument = null; }
        pages = null;
        textCache.evictAll();
    }
}
