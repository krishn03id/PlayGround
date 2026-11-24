package io.github.krishn03id.eyesafe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LibraryStore extends SQLiteOpenHelper {
    LibraryStore(Context context) {
        super(context.getApplicationContext(), "eyesafe.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL, last_read INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE annotations (id TEXT PRIMARY KEY, book_id TEXT NOT NULL, page INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX annotations_book ON annotations(book_id,page)");
        db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY, book_id TEXT NOT NULL, due INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX cards_due ON cards(due)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported library schema migration");
    }

    synchronized void saveBook(Book book) {
        try {
            ContentValues values = new ContentValues();
            values.put("id", book.id);
            values.put("title", book.title);
            values.put("category", book.category);
            values.put("last_read", book.lastRead);
            values.put("json", book.toJson().toString());
            getWritableDatabase().insertWithOnConflict("books", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid book metadata", e);
        }
    }

    synchronized List<Book> books(String search, String category) {
        List<Book> result = new ArrayList<>();
        String query = "SELECT json FROM books WHERE title LIKE ?";
        List<String> args = new ArrayList<>();
        args.add("%" + search.replace("%", "").replace("_", "") + "%");
        if (!"all".equals(category)) {
            query += " AND category = ?";
            args.add(category);
        }
        query += " ORDER BY last_read DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(query, args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                try { result.add(Book.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (JSONException ignored) { }
            }
        }
        return result;
    }

    synchronized Book book(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT json FROM books WHERE id = ?", new String[]{id})) {
            if (cursor.moveToFirst()) return Book.fromJson(new JSONObject(cursor.getString(0)));
        } catch (JSONException ignored) { }
        return null;
    }

    synchronized void deleteBook(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("annotations", "book_id=?", new String[]{id});
            db.delete("cards", "book_id=?", new String[]{id});
            db.delete("books", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized List<JSONObject> annotations(String bookId, int page) {
        String where = page < 0 ? "book_id=?" : "book_id=? AND page=?";
        String[] args = page < 0 ? new String[]{bookId} : new String[]{bookId, String.valueOf(page)};
        return rows("annotations", where, args, "page ASC, rowid ASC");
    }

    synchronized JSONObject annotate(Book book, int page, int start, int end, String quote,
                                     String note, String color, String type) throws JSONException {
        JSONObject annotation = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("bookId", book.id).put("page", page).put("start", start).put("end", end)
                .put("quote", quote).put("note", note).put("color", color).put("type", type);
        saveAnnotation(annotation);
        return annotation;
    }

    synchronized void saveAnnotation(JSONObject annotation) {
        ContentValues values = new ContentValues();
        values.put("id", annotation.optString("id"));
        values.put("book_id", annotation.optString("bookId"));
        values.put("page", annotation.optInt("page"));
        values.put("json", annotation.toString());
        getWritableDatabase().insertWithOnConflict("annotations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void deleteAnnotation(String id) {
        getWritableDatabase().delete("annotations", "id=?", new String[]{id});
    }

    synchronized void saveCard(JSONObject card) {
        ContentValues values = new ContentValues();
        values.put("id", card.optString("id"));
        values.put("book_id", card.optString("bookId"));
        values.put("due", card.optLong("due"));
        values.put("json", card.toString());
        getWritableDatabase().insertWithOnConflict("cards", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized List<JSONObject> cards(boolean dueOnly) {
        return rows("cards", dueOnly ? "due<=?" : null,
                dueOnly ? new String[]{String.valueOf(System.currentTimeMillis())} : null, "due ASC");
    }

    synchronized void deleteCard(String id) {
        getWritableDatabase().delete("cards", "id=?", new String[]{id});
    }

    private List<JSONObject> rows(String table, String where, String[] args, String order) {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(table, new String[]{"json"}, where, args, null, null, order)) {
            while (cursor.moveToNext()) {
                try { result.add(new JSONObject(cursor.getString(0))); } catch (JSONException ignored) { }
            }
        }
        return result;
    }

    synchronized JSONObject snapshot() throws JSONException {
        JSONArray books = new JSONArray();
        for (Book book : books("", "all")) books.put(book.toJson());
        JSONArray notes = new JSONArray();
        for (JSONObject annotation : rows("annotations", null, null, "rowid ASC")) notes.put(annotation);
        JSONArray cards = new JSONArray();
        for (JSONObject card : cards(false)) cards.put(card);
        return new JSONObject().put("app", "EyeSafeAndroid").put("version", 1)
                .put("books", books).put("annotations", notes).put("cards", cards);
    }

    synchronized void restore(JSONObject snapshot) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray books = snapshot.getJSONArray("books");
            for (int i = 0; i < books.length(); i++) saveBook(Book.fromJson(books.getJSONObject(i)));
            JSONArray notes = snapshot.optJSONArray("annotations");
            if (notes != null) for (int i = 0; i < notes.length(); i++) saveAnnotation(notes.getJSONObject(i));
            JSONArray cards = snapshot.optJSONArray("cards");
            if (cards != null) for (int i = 0; i < cards.length(); i++) saveCard(cards.getJSONObject(i));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized String markdown(Book book) {
        StringBuilder result = new StringBuilder("# Study notes: ").append(book.title).append("\n\n");
        if (book.endPage >= 0) result.append("Resume: page ").append(book.endPage + 1)
                .append(" — ").append(book.endSnippet).append("\n\n");
        for (JSONObject note : annotations(book.id, -1)) {
            result.append("## Page ").append(note.optInt("page") + 1).append("\n\n");
            result.append("> ").append(note.optString("quote").replace("\n", "\n> ")).append("\n\n");
            if (!note.optString("note").isEmpty()) result.append(note.optString("note")).append("\n\n");
        }
        return result.toString();
    }
}
