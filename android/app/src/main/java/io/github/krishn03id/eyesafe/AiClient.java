package io.github.krishn03id.eyesafe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class AiClient {
    static final class Reply {
        final String text;
        final String model;
        Reply(String text, String model) { this.text = text; this.model = model; }
    }

    static Reply ask(AppSettings settings, Book book, String context, String question, boolean quiz) throws Exception {
        JSONArray profiles = settings.profiles();
        if (profiles.length() == 0) throw new IOException("Add an AI profile in Eye & Text → AI models first");
        int active = Math.max(0, Math.min(profiles.length() - 1, settings.number("activeAi", 0)));
        String instruction = "You are a concise reading and study companion. Use only the supplied reading context. "
                + "If the passage does not contain an answer, say so. Book: " + book.title + ", page " + (book.page + 1)
                + ".\nREADING CONTEXT:\n" + context.substring(0, Math.min(12000, context.length()));
        if (quiz) question = "Return only valid JSON with a questions array containing two multiple-choice questions and one flashcard from the context. "
                + "Each MCQ has type=mcq, question, options (four strings), answerIndex (zero-based), and explanation. "
                + "The flashcard has type=flashcard, question, answer. No markdown fences.";
        List<String> failures = new ArrayList<>();
        int attempts = settings.flag("aiFallback", true) ? profiles.length() : 1;
        for (int i = 0; i < attempts; i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled");
            int index = (active + i) % profiles.length();
            JSONObject profile = profiles.getJSONObject(index);
            try {
                String response = call(profile, instruction, question);
                settings.put("activeAi", index);
                return new Reply(response, profile.optString("name", profile.optString("model")));
            } catch (Exception error) {
                // Provider response bodies can echo credentials; only expose the profile name.
                failures.add(profile.optString("name", "AI provider"));
            }
        }
        throw new IOException("Could not reach " + join(failures) + ". Check the connection, endpoint, model and API key.");
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(", "); result.append(value); }
        return result.toString();
    }

    private static String call(JSONObject profile, String instruction, String question) throws Exception {
        boolean gemini = "gemini".equals(profile.optString("provider"));
        String key = SecretVault.decrypt(profile.optString("key"));
        String model = profile.optString("model").trim();
        String endpoint = gemini ? "https://generativelanguage.googleapis.com/v1beta/models/"
                + java.net.URLEncoder.encode(model, "UTF-8") + ":generateContent" : profile.optString("endpoint").trim();
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol())) throw new IOException("Use an HTTPS endpoint");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(35000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!key.isEmpty()) connection.setRequestProperty(gemini ? "x-goog-api-key" : "Authorization", gemini ? key : "Bearer " + key);
        JSONObject payload;
        if (gemini) {
            payload = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instruction))))
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", question)))));
        } else {
            payload = new JSONObject().put("model", model).put("messages", new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", instruction))
                    .put(new JSONObject().put("role", "user").put("content", question)));
        }
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                throw new IOException("Provider returned HTTP " + connection.getResponseCode());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) { BookImporter.copy(input, output, 2 * 1024 * 1024); }
            JSONObject result = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            String text = gemini ? result.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
                    : result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            if (text.trim().isEmpty()) throw new IOException("Provider returned an empty response");
            return text.trim();
        } finally { connection.disconnect(); }
    }

    static JSONArray questions(String response) throws Exception {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("The model did not return question JSON");
        JSONArray questions = new JSONObject(response.substring(start, end + 1)).getJSONArray("questions");
        if (questions.length() == 0 || questions.length() > 20) throw new IOException("Invalid quiz response");
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.getJSONObject(i);
            if (question.optString("question").isEmpty()) throw new IOException("Invalid question");
            if ("mcq".equals(question.optString("type"))) {
                JSONArray options = question.getJSONArray("options");
                int answer = question.optInt("answerIndex", -1);
                if (options.length() < 2 || options.length() > 8 || answer < 0 || answer >= options.length())
                    throw new IOException("Invalid quiz answer key");
            }
        }
        return questions;
    }
}
