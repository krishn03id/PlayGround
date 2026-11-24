package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class BookImporter {
    static final long MAX_FILE = 512L * 1024 * 1024;
    static final int MAX_TEXT = 24 * 1024 * 1024;

    static File directory(Context context, String id) {
        if (!ArchivePaths.validBookId(id)) throw new IllegalArgumentException("Invalid book ID");
        return new File(new File(context.getFilesDir(), "books"), id);
    }

    static File source(Context context, Book book) {
        return new File(directory(context, book.id), "source." + book.format);
    }

    static Book importUri(Context context, LibraryStore store, Uri uri) throws Exception {
        String name = "Imported book";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        String format = format(name, context.getContentResolver().getType(uri));
        Book book = new Book();
        book.id = UUID.randomUUID().toString();
        book.fileName = name;
        book.title = name.replaceFirst("(?i)\\.(pdf|epub|txt|md|markdown)$", "").replace('_', ' ').trim();
        if (book.title.isEmpty()) book.title = "Untitled book";
        book.format = format;
        book.category = "epub".equals(format) ? "novels" : "study";
        book.lastRead = System.currentTimeMillis();
        File directory = directory(context, book.id);
        if (!directory.mkdirs()) throw new IOException("Could not create book storage");
        try {
            File source = source(context, book);
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(source)) {
                if (input == null) throw new IOException("File cannot be read");
                book.size = copy(input, output, MAX_FILE);
            }
            if ("pdf".equals(format)) {
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(descriptor)) {
                    book.pageCount = renderer.getPageCount();
                    try (PdfRenderer.Page page = renderer.openPage(0)) {
                        Bitmap cover = Bitmap.createBitmap(220, Math.max(1, Math.min(720, (int)(220f * page.getHeight() / page.getWidth()))), Bitmap.Config.ARGB_8888);
                        cover.eraseColor(Color.WHITE);
                        page.render(cover, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        saveCover(directory, cover);
                        cover.recycle();
                    }
                }
            } else {
                List<TextPaginator.Page> pages;
                if ("epub".equals(format)) {
                    EpubReader.Result result = EpubReader.read(source, book.title);
                    pages = result.pages;
                    book.title = result.title;
                    if (result.cover != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(result.cover, 0, result.cover.length, options);
                        options.inSampleSize = 1;
                        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 600) options.inSampleSize *= 2;
                        options.inJustDecodeBounds = false;
                        Bitmap cover = BitmapFactory.decodeByteArray(result.cover, 0, result.cover.length, options);
                        if (cover != null) { saveCover(directory, cover); cover.recycle(); }
                    }
                } else {
                    if (source.length() > MAX_TEXT) throw new IOException("Text files must be smaller than 24 MB");
                    pages = TextPaginator.paginate(readText(source, MAX_TEXT), "md".equals(format));
                }
                writePages(directory, pages);
                book.pageCount = pages.size();
            }
            store.saveBook(book);
            return book;
        } catch (Exception error) {
            deleteDirectory(directory);
            throw error;
        }
    }

    static Book sample(Context context, LibraryStore store) throws Exception {
        Book existing = store.book("sample-quiet-discipline");
        if (existing != null) return existing;
        Book book = new Book();
        book.id = "sample-quiet-discipline";
        book.title = "The Quiet Discipline";
        book.fileName = "Built-in sample.md";
        book.format = "md";
        book.category = "study";
        book.lastRead = System.currentTimeMillis();
        String content = "# Before Dawn\n\nAt five in the morning, the world belongs to the people awake before the noise begins. Outside the narrow window, the sky was still a deep slate blue. The lamp on the desk felt like a small hearth.\n\n"
                + "Ideas move slowly and deliberately here. Open Eye & Text to adjust your font, reading theme, line spacing and page width. On a tablet, your index and notes stay beside the page.\n\n"
                + "Long-press any passage to highlight, take a margin note, copy, create a flashcard or ask your optional AI companion. Tap a sentence to save an End Line and return to it later.\n\n"
                + "# The 20-20-20 Rhythm\n\nThe amber timer counts active reading time. Every twenty minutes, take a twenty-second break and look at an object about twenty feet away. Choose a different interval in Eye & Text whenever you need it.\n\n"
                + "Use Listen to hear the current page with Android text-to-speech. Your downloaded voices can work offline. Scroll starts a gentle teleprompter. Tap the page to pause or resume it.\n\n"
                + "# Active Study\n\nActive recall helps turn reading into lasting understanding. Save a question and answer as a flashcard, then revisit it in the Study Deck. Choose Again, Good or Easy to schedule your next review.\n\n"
                + "Your PDFs, EPUBs, text files and notes stay on this device. Use Backup to export your library, including original files, and Restore to bring it to another device. AI is optional and only sends the context you ask it to use.\n\n"
                + "A quiet reading habit is built one page at a time.";
        File directory = directory(context, book.id);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Storage unavailable");
        try (OutputStream output = new FileOutputStream(source(context, book))) { output.write(content.getBytes(StandardCharsets.UTF_8)); }
        List<TextPaginator.Page> pages = TextPaginator.paginate(content, true);
        writePages(directory, pages);
        book.pageCount = pages.size();
        book.size = source(context, book).length();
        store.saveBook(book);
        return book;
    }

    static String format(String name, String mime) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") || "application/pdf".equals(mime)) return "pdf";
        if (lower.endsWith(".epub") || "application/epub+zip".equals(mime)) return "epub";
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || "text/markdown".equals(mime)) return "md";
        if (lower.endsWith(".txt") || "text/plain".equals(mime)) return "txt";
        throw new IOException("Choose a PDF, EPUB, TXT or Markdown file");
    }

    static void writePages(File directory, List<TextPaginator.Page> pages) throws Exception {
        JSONArray array = new JSONArray();
        for (TextPaginator.Page page : pages) array.put(new JSONObject().put("heading", page.heading).put("text", page.text));
        try (OutputStream output = new FileOutputStream(new File(directory, "pages.json"))) {
            output.write(array.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void saveCover(File directory, Bitmap bitmap) throws IOException {
        try (OutputStream output = new FileOutputStream(new File(directory, "cover.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("File exceeds the supported size limit");
            if (Thread.currentThread().isInterrupted()) throw new IOException("Operation cancelled");
            output.write(buffer, 0, read);
        }
        return total;
    }

    static String readText(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output, limit);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void deleteDirectory(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteDirectory(child);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
}
