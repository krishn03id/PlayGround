package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    final Context context;
    int background, surface, elevated, foreground, muted, accent;
    boolean light;

    Ui(Context context, AppSettings settings) {
        this.context = context;
        String theme = settings.text("theme", "dark");
        light = "paper".equals(theme) || "sepia".equals(theme);
        if ("sepia".equals(theme)) {
            background = Color.rgb(244,236,216); surface = Color.rgb(236,225,198);
            elevated = Color.rgb(227,213,180); foreground = Color.rgb(59,47,33); muted = Color.rgb(110,92,71); accent = Color.rgb(156,94,36);
        } else if ("paper".equals(theme)) {
            background = Color.rgb(246,246,244); surface = Color.WHITE;
            elevated = Color.rgb(235,235,232); foreground = Color.rgb(31,34,38); muted = Color.rgb(92,98,107); accent = Color.rgb(166,100,35);
        } else {
            background = "oled".equals(theme) ? Color.BLACK : Color.rgb(20,21,23);
            surface = Color.rgb(30,32,36); elevated = Color.rgb(40,43,48);
            foreground = Color.rgb(228,226,221); muted = Color.rgb(156,160,168); accent = Color.rgb(217,155,82);
        }
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView label(String text, float size, int color) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setFontFeatureSettings("kern");
        return label;
    }

    TextView heading(String text, float size) {
        TextView label = label(text, size, foreground);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    GradientDrawable shape(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), light ? 0x18000000 : 0x18FFFFFF);
        return drawable;
    }

    Button button(String text, boolean primary, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setContentDescription(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? (light ? Color.WHITE : Color.rgb(20,21,23)) : foreground);
        button.setBackground(shape(primary ? accent : elevated, 12));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setOnClickListener(listener);
        return button;
    }

    EditText input(String hint, boolean multiline) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setTextColor(foreground);
        input.setHintTextColor(muted);
        input.setTextSize(15);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(shape(elevated, 12));
        input.setSingleLine(!multiline);
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMinLines(3);
            input.setGravity(Gravity.TOP);
        }
        return input;
    }

    void add(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        parent.addView(child, params);
    }

    void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMarginEnd(dp(6));
        row.addView(button, params);
    }
}
