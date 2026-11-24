package io.github.krishn03id.eyesafe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.Holder> {
    interface Listener {
        void open(Book book);
        void manage(Book book, View anchor);
        void cover(Book book, ImageView image);
    }

    private final List<Book> books = new ArrayList<>();
    private final Ui ui;
    private final Listener listener;

    LibraryAdapter(Ui ui, Listener listener) {
        this.ui = ui;
        this.listener = listener;
        setHasStableIds(true);
    }

    void books(List<Book> values) {
        books.clear(); books.addAll(values); notifyDataSetChanged();
    }

    @Override public long getItemId(int position) { return books.get(position).id.hashCode(); }
    @Override public int getItemCount() { return books.size(); }

    @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout card = ui.column();
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(8));
        card.setLayoutParams(params);
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        card.setBackground(ui.shape(ui.surface, 18));
        FrameLayout cover = new FrameLayout(ui.context);
        cover.setBackground(ui.shape(ui.elevated, 10));
        ImageView image = new ImageView(ui.context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView generated = ui.heading("", 20);
        generated.setGravity(Gravity.CENTER);
        generated.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        generated.setMaxLines(4);
        cover.addView(generated, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(160)));
        TextView title = ui.heading("", 15);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMinHeight(ui.dp(48));
        title.setPadding(0, ui.dp(10), 0, 0);
        ui.add(card, title);
        TextView progress = ui.label("", 12, ui.muted);
        ui.add(card, progress);
        LinearLayout actions = ui.row();
        TextView category = ui.label("", 12, ui.accent);
        actions.addView(category, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Button more = ui.button("•••", false, null);
        actions.addView(more, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        card.addView(actions);
        return new Holder(card, image, generated, title, progress, category, more);
    }

    @Override public void onBindViewHolder(Holder holder, int position) {
        Book book = books.get(position);
        holder.image.setImageBitmap((Bitmap)null);
        holder.image.setTag(book.id);
        holder.generated.setText(book.title);
        holder.generated.setVisibility(View.VISIBLE);
        holder.image.setVisibility(View.VISIBLE);
        holder.image.setContentDescription("Cover of " + book.title);
        holder.title.setText(book.title);
        holder.category.setText(book.category.toUpperCase(Locale.ROOT) + " · " + book.format.toUpperCase(Locale.ROOT));
        holder.progress.setText("Page " + (book.page + 1) + " / " + book.pageCount + (book.endPage >= 0 ? " · ⚑ resume saved" : ""));
        holder.itemView.setOnClickListener(view -> listener.open(book));
        holder.image.setOnClickListener(view -> listener.open(book));
        holder.generated.setOnClickListener(view -> listener.open(book));
        holder.more.setContentDescription("Manage " + book.title);
        holder.more.setOnClickListener(view -> listener.manage(book, view));
        holder.image.setTag(io.github.krishn03id.eyesafe.R.id.library_list, holder.generated);
        listener.cover(book, holder.image);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView generated, title, progress, category;
        final View more;
        Holder(View root, ImageView image, TextView generated, TextView title, TextView progress, TextView category, View more) {
            super(root); this.image = image; this.generated = generated; this.title = title;
            this.progress = progress; this.category = category; this.more = more;
        }
    }
}
