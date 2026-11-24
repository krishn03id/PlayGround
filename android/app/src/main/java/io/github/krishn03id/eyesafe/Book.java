package io.github.krishn03id.eyesafe;

import org.json.JSONException;
import org.json.JSONObject;

final class Book {
    String id;
    String title;
    String fileName;
    String format;
    String category = "study";
    int pageCount = 1;
    int page = 0;
    int endPage = -1;
    int endOffset = -1;
    String endSnippet = "";
    long lastRead;
    long size;

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("fileName", fileName)
                .put("format", format).put("category", category).put("pageCount", pageCount)
                .put("page", page).put("endPage", endPage).put("endOffset", endOffset)
                .put("endSnippet", endSnippet).put("lastRead", lastRead).put("size", size);
    }

    static Book fromJson(JSONObject json) {
        Book book = new Book();
        book.id = json.optString("id");
        book.title = json.optString("title", "Untitled book");
        book.fileName = json.optString("fileName");
        book.format = json.optString("format", "txt");
        book.category = json.optString("category", "study");
        book.pageCount = Math.max(1, json.optInt("pageCount", 1));
        book.page = Math.max(0, Math.min(book.pageCount - 1, json.optInt("page")));
        book.endPage = json.optInt("endPage", -1);
        book.endOffset = json.optInt("endOffset", -1);
        book.endSnippet = json.optString("endSnippet");
        book.lastRead = json.optLong("lastRead");
        book.size = json.optLong("size");
        return book;
    }
}
