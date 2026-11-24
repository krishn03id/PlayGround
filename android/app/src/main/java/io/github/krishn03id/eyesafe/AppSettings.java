package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

final class AppSettings {
    private final SharedPreferences preferences;
    private int secondsRead;
    private String day;

    AppSettings(Context context) {
        preferences = context.getSharedPreferences("eyesafe", Context.MODE_PRIVATE);
        secondsRead = preferences.getInt("seconds", 0);
        day = preferences.getString("date", "");
        rollDay();
    }

    String text(String key, String fallback) { return preferences.getString(key, fallback); }
    int number(String key, int fallback) { return "seconds".equals(key) ? secondsRead : preferences.getInt(key, fallback); }
    boolean flag(String key, boolean fallback) { return preferences.getBoolean(key, fallback); }
    void put(String key, String value) { if ("date".equals(key)) day = value; preferences.edit().putString(key, value).apply(); }
    void put(String key, int value) { if ("seconds".equals(key)) secondsRead = value; preferences.edit().putInt(key, value).apply(); }
    void put(String key, boolean value) { preferences.edit().putBoolean(key, value).apply(); }

    private String today(int delta) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, delta);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.getTime());
    }

    private void rollDay() {
        String today = today(0);
        if (!today.equals(day)) {
            if (!today(-1).equals(text("lastReadingDate", ""))) put("streak", 0);
            day = today;
            secondsRead = 0;
            preferences.edit().putString("date", today).putInt("seconds", 0).putInt("breaks", 0).apply();
        }
    }

    void readingSecond() {
        rollDay();
        secondsRead++;
        if (secondsRead >= 60 && !day.equals(text("lastReadingDate", "")))
            preferences.edit().putInt("streak", number("streak", 0) + 1).putString("lastReadingDate", day).apply();
        if (secondsRead % 15 == 0) flush();
    }

    void flush() { preferences.edit().putInt("seconds", secondsRead).putString("date", day).apply(); }

    JSONArray profiles() {
        try { return new JSONArray(text("aiProfiles", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    JSONObject backup() throws Exception {
        JSONObject result = new JSONObject();
        String[] strings = {"theme", "font", "date", "lastReadingDate", "ttsVoice"};
        String[] numbers = {"fontSize", "lineSpacing", "letterSpacing", "warmth", "dimmer", "interval", "goal", "seconds", "breaks", "streak", "width", "ttsRate"};
        String[] flags = {"awake", "ruler", "bionic", "justify", "aiFallback"};
        for (String key : strings) if (preferences.contains(key)) result.put(key, text(key, ""));
        for (String key : numbers) if (preferences.contains(key)) result.put(key, number(key, 0));
        for (String key : flags) if (preferences.contains(key)) result.put(key, flag(key, false));
        return result;
    }

    void restore(JSONObject json) {
        if (json == null) return;
        java.util.Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!key.matches("theme|font|date|lastReadingDate|ttsVoice|fontSize|lineSpacing|letterSpacing|warmth|dimmer|interval|goal|seconds|breaks|streak|width|ttsRate|awake|ruler|bionic|justify|aiFallback")) continue;
            Object value = json.opt(key);
            if (value instanceof Boolean) put(key, (Boolean)value);
            else if (value instanceof Number) put(key, ((Number)value).intValue());
            else if (value instanceof String) put(key, (String)value);
        }
        rollDay();
    }
}
