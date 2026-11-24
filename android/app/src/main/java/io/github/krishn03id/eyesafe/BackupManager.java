package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class BackupManager {
    private static final long MAX_BACKUP = 1024L * 1024 * 1024;

    static void export(Context context, LibraryStore store, AppSettings settings, Uri destination) throws Exception {
        JSONObject snapshot = store.snapshot().put("preferences", settings.backup());
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("Destination is unavailable");
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("library.json"));
                zip.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                JSONArray books = snapshot.getJSONArray("books");
                for (int i = 0; i < books.length(); i++) {
                    Book book = Book.fromJson(books.getJSONObject(i));
                    File[] files = BookImporter.directory(context, book.id).listFiles();
                    if (files == null) continue;
                    for (File file : files) {
                        String path = "books/" + book.id + "/" + file.getName();
                        if (!ArchivePaths.isAllowed(path) || !file.isFile()) continue;
                        zip.putNextEntry(new ZipEntry(path));
                        try (InputStream input = new FileInputStream(file)) { BookImporter.copy(input, zip, BookImporter.MAX_FILE); }
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    static int restore(Context context, LibraryStore store, AppSettings settings, Uri source) throws Exception {
        File staging = new File(context.getCacheDir(), "restore-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IOException("Temporary storage unavailable");
        try {
            long total = 0;
            int entries = 0;
            try (InputStream input = context.getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("Backup is unavailable");
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        if (++entries > 10000 || !ArchivePaths.isAllowed(entry.getName()))
                            throw new IOException("Invalid backup entry");
                        File destination = new File(staging, entry.getName());
                        File parent = destination.getParentFile();
                        if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new IOException("Storage unavailable");
                        if (destination.exists()) throw new IOException("Duplicate backup entry");
                        try (OutputStream output = new FileOutputStream(destination)) {
                            long limit = "library.json".equals(entry.getName()) ? 32L * 1024 * 1024 : BookImporter.MAX_FILE;
                            total += BookImporter.copy(zip, output, Math.min(limit, MAX_BACKUP - total));
                        }
                        zip.closeEntry();
                    }
                }
            }
            JSONObject snapshot = new JSONObject(BookImporter.readText(new File(staging, "library.json"), 32 * 1024 * 1024));
            if (!"EyeSafeAndroid".equals(snapshot.optString("app")) || snapshot.optInt("version") != 1)
                throw new IOException("Choose an EyeSafe Android backup ZIP");
            JSONArray books = snapshot.getJSONArray("books");
            if (books.length() > 3000) throw new IOException("Backup contains too many books");
            for (int i = 0; i < books.length(); i++) {
                Book book = Book.fromJson(books.getJSONObject(i));
                if (!ArchivePaths.validBookId(book.id) || !book.format.matches("pdf|epub|txt|md"))
                    throw new IOException("Invalid book metadata");
                File saved = new File(new File(staging, "books"), book.id);
                if (!new File(saved, "source." + book.format).isFile()) throw new IOException("Backup is missing a book file");
                if (!"pdf".equals(book.format) && !new File(saved, "pages.json").isFile())
                    throw new IOException("Backup is missing parsed book pages");
            }
            for (int i = 0; i < books.length(); i++) {
                Book book = Book.fromJson(books.getJSONObject(i));
                File destination = BookImporter.directory(context, book.id);
                if (destination.exists()) continue;
                File parent = destination.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new IOException("Storage unavailable");
                File saved = new File(new File(staging, "books"), book.id);
                if (!saved.renameTo(destination)) throw new IOException("Could not restore book files");
            }
            store.restore(snapshot);
            settings.restore(snapshot.optJSONObject("preferences"));
            return books.length();
        } finally { BookImporter.deleteDirectory(staging); }
    }
}
