package io.github.krishn03id.eyesafe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int OPEN_BOOKS = 10, OPEN_FOLDER = 11, EXPORT_NOTES = 12, EXPORT_BACKUP = 13, RESTORE_BACKUP = 14;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private LibraryStore store;
    private AppSettings settings;
    private Ui ui;
    private FrameLayout root, readerFrame;
    private CareOverlay care, ruler;
    private LibraryAdapter library;
    private TextView libraryStats, emptyLibrary, pageLabel, timerLabel, readingText;
    private LinearLayout sidebar;
    private View readerHeader, readerToolbar, readerFooter, readerNavigation;
    private Button exitImmersive;
    private ComfortScrollView textScroll;
    private PdfPageView pdf;
    private Book current;
    private DocumentSession session;
    private String pageText = "", search = "", category = "all", pendingMarkdown = "";
    private boolean textMode, destroyed, resumed, speaking, autoScroll, immersive, busy;
    private int renderTicket, libraryTicket, timerSeconds, restRemaining, ttsPosition;
    private int sleepMinutes;
    private float autoScrollSpeed = 1f;
    private TextToSpeech speech;
    private boolean speechReady;
    private AlertDialog restDialog;
    private TextView restLabel;
    private final List<int[]> utterances = new ArrayList<>();
    private Future<?> networkRequest;
    private String activeUtterance = "";
    private final Runnable sleep = () -> { stopSpeaking(); toast("Read-aloud sleep timer finished"); };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (resumed && current != null && !busy) {
                if (restDialog != null && restDialog.isShowing()) {
                    if (restRemaining > 0) restRemaining--;
                    restLabel.setText(restRemaining > 0 ? "Look at an object about 20 feet away.\n\n" + restRemaining + " seconds" : "Break complete. Your next reading session is ready.");
                    if (restRemaining == 0) restDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                } else {
                    settings.readingSecond();
                    if (--timerSeconds <= 0) showRest();
                    updateTimer();
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable scroller = new Runnable() {
        @Override public void run() {
            if (!autoScroll || !resumed || current == null || textScroll == null) return;
            if (restDialog == null || !restDialog.isShowing()) {
                textScroll.smoothScrollBy(0, Math.max(1, ui.dp(autoScrollSpeed * 3)));
                View content = textScroll.getChildAt(0);
                if (content != null && content.getHeight() > textScroll.getHeight()
                        && textScroll.getScrollY() + textScroll.getHeight() >= content.getHeight() - ui.dp(3)) {
                    if (current.page < current.pageCount - 1) { turnPage(1); main.postDelayed(this, 1200); return; }
                    autoScroll = false; toast("End of book"); return;
                }
            }
            main.postDelayed(this, 100);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new AppSettings(this);
        store = new LibraryStore(this);
        ui = new Ui(this, settings);
        timerSeconds = settings.number("interval", 20) * 60;
        showLibrary();
        initSpeech();
        main.postDelayed(ticker, 1000);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        if (state != null && state.getString("book") != null) {
            String id = state.getString("book");
            io.execute(() -> { Book book = store.book(id); if (book != null) main.post(() -> openBook(book)); });
        } else handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    @Override protected void onResume() { super.onResume(); resumed = true; setAwake(); }
    @Override protected void onPause() { resumed = false; settings.flush(); stopSpeaking(); stopScroll(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (current != null) state.putString("book", current.id);
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (current == null) showLibrary(); else { showReader(); renderPage(false); }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (networkRequest != null) networkRequest.cancel(true);
        network.shutdownNow();
        if (speech != null) { speech.stop(); speech.shutdown(); }
        if (restDialog != null) restDialog.dismiss();
        if (pdf != null) pdf.clear();
        DocumentSession old = session;
        session = null;
        io.execute(() -> { closeSession(old); store.close(); });
        io.shutdown();
        covers.evictAll();
        super.onDestroy();
    }

    private boolean tablet() { return getResources().getConfiguration().screenWidthDp >= 600; }
    private void toast(String message) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @SuppressWarnings("deprecation")
    private void install(LinearLayout content) {
        root = new FrameLayout(this);
        root.setId(R.id.main_root);
        root.setBackgroundColor(ui.background);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        care = new CareOverlay(this, settings, false);
        root.addView(care, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(ui.background);
        getWindow().setNavigationBarColor(ui.background);
        int flags = ui.light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (Build.VERSION.SDK_INT >= 26 && ui.light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        exitImmersive = null;
        if (current != null) {
            exitImmersive = ui.button("Exit full mode", true, view -> toggleImmersive());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48), Gravity.BOTTOM | Gravity.END);
            params.setMargins(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            root.addView(exitImmersive, params);
        }
        applyImmersive();
    }

    private void showLibrary() {
        renderTicket++;
        if (pdf != null) { pdf.clear(); pdf = null; }
        current = null;
        immersive = false;
        stopSpeaking(); stopScroll();
        if (restDialog != null) { restDialog.dismiss(); restDialog = null; }
        DocumentSession old = session; session = null;
        if (old != null) io.execute(() -> closeSession(old));
        setAwake();
        ui = new Ui(this, settings);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(tablet() ? 24 : 14), ui.dp(12), ui.dp(tablet() ? 24 : 14), 0);
        LinearLayout header = ui.row();
        TextView title = ui.heading("EyeSafe Reader", tablet() ? 27 : 23);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(ui.button("Eye & Text", false, view -> showSettings()));
        ui.add(content, header);
        ui.add(content, ui.label("Your quiet reading sanctuary", 14, ui.muted));
        LinearLayout hero = ui.column();
        hero.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(12));
        hero.setBackground(ui.shape(ui.surface, 20));
        ui.add(hero, ui.heading("Make room for one more chapter.", tablet() ? 23 : 19));
        libraryStats = ui.label("Loading your local shelf…", 13, ui.muted);
        ui.add(hero, libraryStats);
        LinearLayout imports = ui.row();
        ui.addButton(imports, ui.button("Add books", true, view -> chooseBooks()));
        ui.addButton(imports, ui.button("Folder", false, view -> chooseFolder()));
        ui.addButton(imports, ui.button("Sample book", false, view -> job("Opening sample…", () -> BookImporter.sample(this, store), this::openBook)));
        hero.addView(imports);
        ui.add(content, hero);
        LinearLayout tools = ui.row();
        ui.addButton(tools, ui.button("Study Deck", false, view -> showDeck(false)));
        ui.addButton(tools, ui.button("Backup", false, view -> chooseBackupExport()));
        ui.addButton(tools, ui.button("Restore", false, view -> chooseRestore()));
        ui.add(content, tools);
        EditText searchInput = ui.input("Search your shelf", false);
        searchInput.setText(search);
        searchInput.addTextChangedListener(new TextWatcher() {
            private final Runnable refresh = MainActivity.this::refreshLibrary;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                search = s.toString(); main.removeCallbacks(refresh); main.postDelayed(refresh, 200);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        ui.add(content, searchInput);
        LinearLayout filters = ui.row();
        String[] cats = {"all", "study", "novels", "finished"};
        for (String value : cats) ui.addButton(filters, ui.button(value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1), category.equals(value), view -> {
            category = value;
            for (int i = 0; i < filters.getChildCount(); i++) filters.getChildAt(i).setBackground(ui.shape(cats[i].equals(value) ? ui.accent : ui.elevated, 12));
            refreshLibrary();
        }));
        ui.add(content, filters);
        emptyLibrary = ui.label("Add a PDF, EPUB, TXT or Markdown file, or try the sample book.", 15, ui.muted);
        emptyLibrary.setGravity(Gravity.CENTER);
        emptyLibrary.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        ui.add(content, emptyLibrary);
        RecyclerView grid = new RecyclerView(this);
        grid.setId(R.id.library_list);
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = Math.max(2, Math.min(5, (width - 32) / (tablet() ? 220 : 168)));
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, ui.dp(16));
        library = new LibraryAdapter(ui, new LibraryAdapter.Listener() {
            @Override public void open(Book book) { openBook(book); }
            @Override public void manage(Book book, View anchor) { manageBook(book, anchor); }
            @Override public void cover(Book book, ImageView image) { loadCover(book, image); }
        });
        grid.setAdapter(library);
        content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        install(content);
        refreshLibrary();
    }

    private void refreshLibrary() {
        if (destroyed || current != null) return;
        int ticket = ++libraryTicket;
        String query = search, filter = category;
        io.execute(() -> {
            try {
                List<Book> books = store.books(query, filter);
                int total = store.books("", "all").size();
                main.post(() -> {
                    if (destroyed || current != null || ticket != libraryTicket) return;
                    library.books(books);
                    emptyLibrary.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
                    libraryStats.setText(total + " books · " + settings.number("seconds", 0) / 60 + "m today / "
                            + settings.number("goal", 30) + "m goal · " + settings.number("streak", 0) + " day streak");
                });
            } catch (Exception error) { main.post(() -> toast("Local storage could not be opened")); }
        });
    }

    private void loadCover(Book book, ImageView image) {
        Bitmap cached = covers.get(book.id);
        if (cached != null) { setCover(book.id, image, cached); return; }
        io.execute(() -> {
            File file = new File(BookImporter.directory(this, book.id), "cover.png");
            if (!file.isFile()) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 600) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) { covers.put(book.id, bitmap); main.post(() -> { if (!destroyed) setCover(book.id, image, bitmap); }); }
        });
    }

    private void setCover(String id, ImageView image, Bitmap bitmap) {
        if (!id.equals(image.getTag())) return;
        image.setImageBitmap(bitmap);
        Object generated = image.getTag(R.id.library_list);
        if (generated instanceof View) ((View)generated).setVisibility(View.GONE);
    }

    private void manageBook(Book book, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] actions = {"Rename", "Move to Study", "Move to Novels", "Move to Finished", "Remove book"};
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, i, actions[i]);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) {
                EditText name = ui.input("Book title", false); name.setText(book.title);
                show(dialog("Rename book", name).setPositiveButton("Save", (d, w) -> {
                    if (!name.getText().toString().trim().isEmpty()) { book.title = name.getText().toString().trim(); store.saveBook(book); refreshLibrary(); }
                }).setNegativeButton("Cancel", null));
            } else if (item.getItemId() < 4) {
                book.category = new String[]{"", "study", "novels", "finished"}[item.getItemId()];
                store.saveBook(book); refreshLibrary();
            } else {
                show(dialog("Remove " + book.title + "?", ui.label("The local file, notes and cards for this book will be removed.", 15, ui.foreground))
                        .setPositiveButton("Remove", (d, w) -> job("Removing book…", () -> {
                            store.deleteBook(book.id); BookImporter.deleteDirectory(BookImporter.directory(this, book.id)); covers.remove(book.id); return true;
                        }, value -> refreshLibrary())).setNegativeButton("Cancel", null));
            }
            return true;
        });
        menu.show();
    }

    private void openBook(Book book) {
        if (destroyed || busy) return;
        stopSpeaking(); stopScroll();
        current = book;
        book.page = Math.max(0, Math.min(book.pageCount - 1, book.endPage >= 0 ? book.endPage : book.page));
        book.lastRead = System.currentTimeMillis();
        textMode = !"pdf".equals(book.format);
        timerSeconds = Math.max(1, settings.number("interval", 20)) * 60;
        DocumentSession old = session; session = null;
        showReader();
        ++renderTicket;
        io.execute(() -> {
            closeSession(old);
            try {
                DocumentSession opened = new DocumentSession(this, book);
                main.post(() -> {
                    if (destroyed || current != book) { closeSession(opened); return; }
                    session = opened; store.saveBook(book); renderPage(true); refreshSidebar();
                });
            } catch (Exception error) { main.post(() -> { if (current == book && !destroyed) { toast("Could not open book: " + safe(error)); showLibrary(); } }); }
        });
        setAwake();
    }

    private void showReader() {
        renderTicket++;
        if (pdf != null) { pdf.clear(); pdf = null; }
        ui = new Ui(this, settings);
        LinearLayout body = ui.column();
        LinearLayout top = ui.row();
        top.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        top.setBackgroundColor(ui.surface);
        top.addView(ui.button("‹ Shelf", false, view -> showLibrary()));
        TextView title = ui.heading(current.title, 16);
        title.setMaxLines(2); title.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(ui.button("Eye & Text", false, view -> showSettings()));
        body.addView(top);
        readerHeader = top;
        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = ui.row();
        tools.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        if ("pdf".equals(current.format)) toolButton(tools, textMode ? "Page view" : "Text mode", view -> { textMode = !textMode; stopScroll(); showReader(); renderPage(true); });
        toolButton(tools, "Index & Notes", view -> showIndex());
        toolButton(tools, "Listen", view -> toggleSpeaking());
        toolButton(tools, "Scroll", view -> toggleScroll());
        toolButton(tools, "AI", view -> openAi("", false));
        toolButton(tools, "Bookmark", view -> addBookmark());
        toolButton(tools, "Focus", view -> { settings.put("ruler", !settings.flag("ruler", false)); ruler.invalidate(); });
        toolButton(tools, "Full mode", view -> toggleImmersive());
        toolScroll.addView(tools);
        body.addView(toolScroll);
        readerToolbar = toolScroll;
        LinearLayout stage = ui.row();
        if (tablet()) {
            ScrollView navigation = new ScrollView(this);
            sidebar = ui.column(); sidebar.setId(R.id.tablet_sidebar);
            sidebar.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18));
            sidebar.setBackgroundColor(ui.surface);
            navigation.addView(sidebar);
            readerNavigation = navigation;
            stage.addView(navigation, new LinearLayout.LayoutParams(getResources().getDimensionPixelSize(R.dimen.sidebar_width), ViewGroup.LayoutParams.MATCH_PARENT));
        } else { sidebar = null; readerNavigation = null; }
        readerFrame = new FrameLayout(this);
        readerFrame.setId(R.id.reader_content);
        readerFrame.addOnLayoutChangeListener((view, left, topEdge, right, bottomEdge, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (view != readerFrame) return;
            if (readingText != null && textMode) {
                int padding = getResources().getDimensionPixelSize(R.dimen.reader_padding);
                int available = Math.max(ui.dp(180), right - left - padding * 2);
                ViewGroup.LayoutParams params = readingText.getLayoutParams();
                int width = Math.min(available, ui.dp(settings.number("width", tablet() ? 800 : 680)));
                if (params.width != width) { params.width = width; readingText.setLayoutParams(params); }
            }
            if (!textMode && oldRight - oldLeft > 0 && right - left != oldRight - oldLeft) renderPage(false);
        });
        stage.addView(readerFrame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (textMode) {
            textScroll = new ComfortScrollView(this, factor -> {
                int size = Math.max(14, Math.min(34, Math.round(settings.number("fontSize", 19) * factor)));
                settings.put("fontSize", size); styleReadingText();
            });
            LinearLayout holder = ui.column(); holder.setGravity(Gravity.CENTER_HORIZONTAL);
            int padding = getResources().getDimensionPixelSize(R.dimen.reader_padding);
            holder.setPadding(padding, ui.dp(26), padding, ui.dp(60));
            readingText = ui.label("Loading page…", 19, ui.foreground);
            readingText.setId(R.id.reader_text);
            readingText.setTextIsSelectable(true);
            readingText.setCustomSelectionActionModeCallback(selectionActions());
            holder.addView(readingText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textScroll.addView(holder);
            readerFrame.addView(textScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            readingText.post(() -> {
                int available = Math.max(ui.dp(200), readerFrame.getWidth() - padding * 2);
                int max = ui.dp(settings.number("width", tablet() ? 800 : 680));
                ViewGroup.LayoutParams params = readingText.getLayoutParams(); params.width = Math.min(available, max); readingText.setLayoutParams(params);
            });
            GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    if (autoScroll) { stopScroll(); toast("Auto-scroll paused"); return true; }
                    Layout layout = readingText.getLayout();
                    if (layout != null && !pageText.isEmpty()) {
                        int line = layout.getLineForVertical(Math.max(0, (int)event.getY() - readingText.getTotalPaddingTop()));
                        int offset = layout.getOffsetForHorizontal(line, event.getX() - readingText.getTotalPaddingLeft());
                        markEnd(offset);
                    }
                    return false;
                }
            });
            readingText.setOnTouchListener((view, event) -> { taps.onTouchEvent(event); return false; });
            styleReadingText();
        } else {
            textScroll = null; readingText = null;
            pdf = new PdfPageView(this, new PdfPageView.Listener() {
                @Override public void turn(int delta) { turnPage(delta); }
                @Override public void tapped() { if (immersive) toggleImmersive(); }
                @Override public void zoomed(float scale) { renderPage(false); }
            });
            pdf.theme(settings.text("theme", "dark"));
            readerFrame.addView(pdf, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ruler = new CareOverlay(this, settings, true);
        readerFrame.addView(ruler, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bottom = ui.row();
        bottom.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); bottom.setBackgroundColor(ui.surface);
        bottom.addView(ui.button("‹ Prev", false, view -> turnPage(-1)));
        LinearLayout center = ui.column(); center.setGravity(Gravity.CENTER);
        pageLabel = ui.label("", 14, ui.foreground); pageLabel.setId(R.id.reader_page_label); pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setOnClickListener(view -> jumpDialog());
        timerLabel = ui.label("", 12, ui.accent); timerLabel.setGravity(Gravity.CENTER); timerLabel.setOnClickListener(view -> showRest());
        center.addView(pageLabel); center.addView(timerLabel);
        bottom.addView(center, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        bottom.addView(ui.button("Next ›", true, view -> turnPage(1)));
        body.addView(bottom);
        readerFooter = bottom;
        install(body);
        updateTimer(); updatePageLabel(); refreshSidebar(); setAwake();
    }

    private void toolButton(LinearLayout row, String label, View.OnClickListener click) {
        Button button = ui.button(label, false, click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48));
        params.setMarginEnd(ui.dp(6)); row.addView(button, params);
    }

    private void renderPage(boolean reset) {
        if (session == null || current == null || destroyed) return;
        int ticket = ++renderTicket;
        Book book = current;
        int page = book.page;
        DocumentSession document = session;
        PdfPageView targetPdf = pdf;
        TextView targetText = readingText;
        int width = readerFrame.getWidth();
        if (width <= 0) { readerFrame.post(() -> { if (current == book && ticket == renderTicket) renderPage(reset); }); return; }
        boolean asText = textMode;
        int renderWidth = Math.round(width * (targetPdf == null ? 1f : targetPdf.zoom()));
        io.execute(() -> {
            try {
                if (asText) {
                    TextPaginator.Page content = document.text(page);
                    main.post(() -> {
                        if (destroyed || ticket != renderTicket || current != book || readingText != targetText) return;
                        pageText = content.heading.isEmpty() ? content.text : content.heading + "\n\n" + content.text;
                        if (pageText.isEmpty()) targetText.setText("This PDF page is an image without selectable text. Switch to Page view to read it.");
                        else decorateText();
                        if (reset) textScroll.scrollTo(0, 0);
                        if (book.endPage == page && book.endOffset >= 0 && reset) targetText.post(() -> scrollToOffset(book.endOffset));
                    });
                } else {
                    Bitmap bitmap = document.render(page, renderWidth);
                    main.post(() -> {
                        if (destroyed || ticket != renderTicket || current != book || pdf != targetPdf) { bitmap.recycle(); return; }
                        targetPdf.bitmap(bitmap, reset);
                    });
                }
            } catch (Exception error) { main.post(() -> { if (!destroyed && ticket == renderTicket) toast("Page could not be read: " + safe(error)); }); }
        });
        book.lastRead = System.currentTimeMillis(); store.saveBook(book); updatePageLabel();
    }

    private void turnPage(int delta) {
        if (current == null || session == null || busy) return;
        int page = Math.max(0, Math.min(current.pageCount - 1, current.page + delta));
        if (page == current.page) return;
        stopSpeaking(); current.page = page; pageText = ""; renderPage(true); refreshSidebar();
    }

    private void updatePageLabel() {
        if (pageLabel != null && current != null) pageLabel.setText("Page " + (current.page + 1) + " / " + current.pageCount);
    }

    private void updateTimer() {
        if (timerLabel != null && current != null) timerLabel.setText(String.format(Locale.ROOT, "%02d:%02d · eye break", Math.max(0, timerSeconds) / 60, Math.max(0, timerSeconds) % 60));
    }

    private void setAwake() {
        if (current != null && resumed && settings != null && settings.flag("awake", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static void closeSession(DocumentSession session) {
        if (session != null) try { session.close(); } catch (Exception ignored) { }
    }

    private String safe(Exception error) { String message = error.getMessage(); return message == null ? "Unknown file error" : message; }

    private interface Value<T> { void ready(T result); }

    private <T> void job(String message, Callable<T> work, Value<T> success) {
        if (busy || destroyed) { toast("Please wait for the current operation"); return; }
        busy = true;
        LinearLayout loading = ui.row(); loading.addView(new ProgressBar(this));
        TextView label = ui.label(message, 15, ui.foreground); label.setPadding(ui.dp(16), 0, 0, 0); loading.addView(label);
        AlertDialog dialog = show(dialog("EyeSafe Reader", loading).setNegativeButton("Cancel", null));
        final Future<?>[] task = new Future<?>[1];
        dialog.setOnCancelListener(value -> { if (task[0] != null) task[0].cancel(true); busy = false; });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> { if (task[0] != null) task[0].cancel(true); dialog.dismiss(); busy = false; });
        task[0] = io.submit(() -> {
            try {
                T result = work.call();
                main.post(() -> { if (!destroyed && dialog.isShowing()) { dialog.dismiss(); busy = false; success.ready(result); } });
            } catch (Exception error) { main.post(() -> { if (!destroyed && dialog.isShowing()) { dialog.dismiss(); busy = false; toast(safe(error)); } }); }
        });
    }

    private AlertDialog.Builder dialog(String title, View view) {
        TextView heading = ui.heading(title, 20); heading.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8));
        LinearLayout box = ui.column(); box.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8)); box.addView(view);
        return new AlertDialog.Builder(this).setCustomTitle(heading).setView(box);
    }

    private AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create(); dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 20));
            if (tablet()) dialog.getWindow().setLayout(Math.min(ui.dp(640), getResources().getDisplayMetrics().widthPixels - ui.dp(64)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL})
            dialog.getButton(button).setTextColor(ui.accent);
        return dialog;
    }

    private void chooseBooks() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/epub+zip", "text/plain", "text/markdown", "application/octet-stream"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_BOOKS);
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_FOLDER);
    }

    private void chooseBackupExport() { createDocument("application/zip", "EyeSafe-backup-" + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date()) + ".zip", EXPORT_BACKUP); }
    private void chooseRestore() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"}), RESTORE_BACKUP); }
    private void createDocument(String mime, String name, int request) { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name), request); }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = Intent.ACTION_SEND.equals(intent.getAction()) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
        if (uri != null && "content".equals(uri.getScheme())) { ArrayList<Uri> files = new ArrayList<>(); files.add(uri); importBooks(files); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null && data.getClipData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_BOOKS) {
            ArrayList<Uri> files = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) files.add(data.getClipData().getItemAt(i).getUri());
            else files.add(uri);
            for (Uri file : files) try { getContentResolver().takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            importBooks(files);
        } else if (request == OPEN_FOLDER && uri != null) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            job("Reading folder…", () -> {
                List<Uri> files = new ArrayList<>(); collectFolder(uri, DocumentsContract.getTreeDocumentId(uri), files, 0); return files;
            }, this::importBooks);
        } else if (request == EXPORT_NOTES && uri != null) {
            String notes = pendingMarkdown;
            job("Exporting notes…", () -> { try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) { if (output == null) throw new IOException("Destination unavailable"); output.write(notes.getBytes(StandardCharsets.UTF_8)); } return true; }, value -> toast("Notes exported"));
        } else if (request == EXPORT_BACKUP && uri != null) {
            job("Backing up books and study data…", () -> { BackupManager.export(this, store, settings, uri); return true; }, value -> toast("Full library backup exported"));
        } else if (request == RESTORE_BACKUP && uri != null) {
            if (current != null) showLibrary();
            job("Restoring library…", () -> BackupManager.restore(this, store, settings, uri), count -> { toast("Restored " + count + " books"); showLibrary(); });
        }
    }

    private void collectFolder(Uri tree, String documentId, List<Uri> files, int depth) throws Exception {
        if (depth > 3 || files.size() >= 200) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext() && files.size() < 200) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) collectFolder(tree, id, files, depth + 1);
                else try { BookImporter.format(name, mime); files.add(DocumentsContract.buildDocumentUriUsingTree(tree, id)); } catch (IOException ignored) { }
            }
        }
    }

    private void importBooks(List<Uri> files) {
        if (files.isEmpty()) { toast("No supported books found in this folder"); return; }
        job("Importing " + files.size() + " book(s)…", () -> {
            Book last = null; List<String> failures = new ArrayList<>();
            for (Uri file : files) {
                if (Thread.currentThread().isInterrupted()) break;
                try { last = BookImporter.importUri(this, store, file); } catch (Exception error) { failures.add(safe(error)); }
            }
            if (last == null) throw new IOException(failures.isEmpty() ? "Import cancelled" : failures.get(0));
            if (!failures.isEmpty()) { int count = failures.size(); main.post(() -> toast(count + " file(s) could not be imported")); }
            return last;
        }, book -> { toast("Saved locally for offline reading"); openBook(book); });
    }

    private void styleReadingText() {
        if (readingText == null) return;
        readingText.setTextSize(Math.max(14, Math.min(34, settings.number("fontSize", 19))));
        readingText.setLineSpacing(0, Math.max(1.4f, Math.min(2.4f, settings.number("lineSpacing", 18) / 10f)));
        readingText.setLetterSpacing(Math.max(0, Math.min(.2f, settings.number("letterSpacing", 0) / 100f)));
        String font = settings.text("font", "serif");
        readingText.setTypeface(Typeface.create("mono".equals(font) ? "monospace" : "sans".equals(font) ? "sans-serif" : "wide".equals(font) ? "sans-serif-medium" : "serif", Typeface.NORMAL));
        // These integer constants are inlined, so the API 26 TextView method remains compatible.
        if (Build.VERSION.SDK_INT >= 26) readingText.setJustificationMode(settings.flag("justify", false)
                ? android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
                : android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE);
        readingText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
    }

    private int highlightColor(String color) {
        switch (color) {
            case "sage": return 0x5534C479;
            case "sky": return 0x5538BDF8;
            case "rose": return 0x55F43F5E;
            default: return 0x55F59E0B;
        }
    }

    private void decorateText() {
        if (readingText == null || current == null || pageText.isEmpty()) return;
        SpannableString text = new SpannableString(pageText);
        if (settings.flag("bionic", false)) {
            java.util.regex.Matcher words = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]{2,}").matcher(pageText);
            while (words.find()) text.setSpan(new StyleSpan(Typeface.BOLD), words.start(), words.start() + Math.max(1, (words.end() - words.start()) / 2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (JSONObject note : store.annotations(current.id, current.page)) {
            if (!"highlight".equals(note.optString("type"))) continue;
            int start = Math.max(0, note.optInt("start")), end = Math.min(pageText.length(), note.optInt("end"));
            if (end > start) text.setSpan(new BackgroundColorSpan(highlightColor(note.optString("color"))), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (current.endPage == current.page && current.endOffset >= 0 && current.endOffset < pageText.length()) {
            int start = current.endOffset;
            int end = Math.min(pageText.length(), start + Math.max(1, TextPaginator.snippet(pageText, start).length()));
            text.setSpan(new android.text.style.UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        readingText.setText(text);
    }

    private void markEnd(int offset) {
        if (current == null || pageText.isEmpty()) return;
        offset = Math.max(0, Math.min(pageText.length() - 1, offset));
        if (current.endPage == current.page && current.endOffset == offset) {
            current.endPage = current.endOffset = -1; current.endSnippet = ""; toast("End Line cleared");
        } else {
            current.endPage = current.page; current.endOffset = offset;
            current.endSnippet = TextPaginator.snippet(pageText, offset);
            toast("End Line saved · page " + (current.page + 1));
        }
        store.saveBook(current); decorateText(); refreshSidebar();
    }

    private void scrollToOffset(int offset) {
        if (textScroll == null || readingText == null || readingText.getLayout() == null) return;
        Layout layout = readingText.getLayout();
        int safe = Math.max(0, Math.min(Math.max(0, readingText.length() - 1), offset));
        int top = layout.getLineTop(layout.getLineForOffset(safe));
        textScroll.smoothScrollTo(0, Math.max(0, top - textScroll.getHeight() / 3));
    }

    private ActionMode.Callback selectionActions() {
        return new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, 9101, 1, "Highlight"); menu.add(0, 9102, 2, "Note");
                menu.add(0, 9103, 3, "End Line"); menu.add(0, 9104, 4, "Ask AI");
                menu.add(0, 9105, 5, "Flashcard"); menu.add(0, 9106, 6, "Define");
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() < 9101 || item.getItemId() > 9106 || readingText == null) return false;
                int start = Math.min(readingText.getSelectionStart(), readingText.getSelectionEnd());
                int end = Math.max(readingText.getSelectionStart(), readingText.getSelectionEnd());
                if (start < 0 || end <= start || end > pageText.length()) return false;
                String selected = pageText.substring(start, end);
                switch (item.getItemId()) {
                    case 9101: chooseHighlight(start, end, selected); break;
                    case 9102: noteEditor(start, end, selected, null); break;
                    case 9103: markEnd(start); break;
                    case 9104: openAi(selected, false); break;
                    case 9105: createCard(selected); break;
                    case 9106: openAi(selected, true); break;
                    default: return false;
                }
                mode.finish(); return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        };
    }

    private void chooseHighlight(int start, int end, String quote) {
        LinearLayout colors = ui.column();
        for (String color : new String[]{"amber", "sage", "sky", "rose"}) {
            Button button = ui.button(color.substring(0, 1).toUpperCase(Locale.ROOT) + color.substring(1), false, null);
            button.setBackground(ui.shape(highlightColor(color), 12));
            ui.add(colors, button);
        }
        AlertDialog dialog = show(dialog("Highlight color", colors).setNegativeButton("Cancel", null));
        String[] names = {"amber", "sage", "sky", "rose"};
        for (int i = 0; i < names.length; i++) {
            String color = names[i];
            colors.getChildAt(i).setOnClickListener(view -> {
                try { store.annotate(current, current.page, start, end, quote, "", color, "highlight"); decorateText(); refreshSidebar(); toast("Highlight saved"); }
                catch (Exception error) { toast("Could not save highlight"); }
                dialog.dismiss();
            });
        }
    }

    private void noteEditor(int start, int end, String quote, JSONObject existing) {
        if (current == null) return;
        Book book = current;
        int page = existing == null ? book.page : existing.optInt("page");
        LinearLayout form = ui.column();
        TextView preview = ui.label(quote, 14, ui.muted); preview.setMaxLines(5); ui.add(form, preview);
        EditText note = ui.input("Your margin note", true); if (existing != null) note.setText(existing.optString("note")); ui.add(form, note);
        String[] colors = {"amber", "sage", "sky", "rose"};
        final int[] chosen = {0};
        if (existing != null) for (int i = 0; i < colors.length; i++) if (colors[i].equals(existing.optString("color"))) chosen[0] = i;
        ui.add(form, spinner(colors, chosen[0], index -> chosen[0] = index));
        show(dialog("Highlight & margin note", form).setPositiveButton("Save", (d, w) -> {
            try {
                if (existing != null) { existing.put("note", note.getText().toString()).put("color", colors[chosen[0]]); store.saveAnnotation(existing); }
                else store.annotate(book, page, start, end, quote, note.getText().toString(), colors[chosen[0]], "highlight");
                decorateText(); refreshSidebar(); toast("Note saved");
            } catch (Exception error) { toast("Could not save note"); }
        }).setNegativeButton("Cancel", null));
    }

    private void addBookmark() {
        if (current == null) return;
        for (JSONObject annotation : store.annotations(current.id, current.page)) {
            if ("bookmark".equals(annotation.optString("type"))) { store.deleteAnnotation(annotation.optString("id")); toast("Bookmark removed"); refreshSidebar(); return; }
        }
        try { store.annotate(current, current.page, 0, 0, "Page " + (current.page + 1), "", "amber", "bookmark"); toast("Page bookmarked"); refreshSidebar(); }
        catch (Exception error) { toast("Could not save bookmark"); }
    }

    private void refreshSidebar() {
        if (sidebar == null || current == null) return;
        LinearLayout target = sidebar;
        Book book = current;
        target.removeAllViews();
        ui.add(target, ui.heading("Reading desk", 20));
        ui.add(target, ui.label(book.title, 14, ui.muted));
        ui.add(target, ui.label(book.format.toUpperCase(Locale.ROOT) + " · " + book.pageCount + " pages", 12, ui.accent));
        ui.add(target, ui.button("Jump to page", false, view -> jumpDialog()));
        if (book.endPage >= 0) {
            ui.add(target, ui.button("⚑ Resume page " + (book.endPage + 1), true, view -> resumeEnd()));
            ui.add(target, ui.label(book.endSnippet, 13, ui.muted));
        }
        ui.add(target, ui.button("Search this book", false, view -> searchDialog()));
        ui.add(target, ui.heading("Chapters", 16));
        LinearLayout chapters = ui.column(); target.addView(chapters);
        DocumentSession document = session;
        if (document != null) io.execute(() -> {
            try {
                JSONArray toc = document.chapters();
                main.post(() -> {
                    if (destroyed || sidebar != target || current != book) return;
                    if (toc.length() == 0) ui.add(chapters, ui.label("Use page jump or search to navigate this book.", 13, ui.muted));
                    for (int i = 0; i < Math.min(60, toc.length()); i++) {
                        JSONObject chapter = toc.optJSONObject(i);
                        if (chapter != null) ui.add(chapters, ui.button(chapter.optString("title"), false, view -> jump(chapter.optInt("page"))));
                    }
                });
            } catch (Exception ignored) { }
        });
        ui.add(target, ui.heading("Recent notes & markers", 16));
        List<JSONObject> notes = store.annotations(book.id, -1);
        if (notes.isEmpty()) ui.add(target, ui.label("Long-press text for highlights, notes and flashcards. Tap a line to save your resume spot.", 13, ui.muted));
        for (int i = Math.max(0, notes.size() - 6); i < notes.size(); i++) {
            JSONObject note = notes.get(i);
            String preview = note.optString("quote"); if (preview.length() > 65) preview = preview.substring(0, 65) + "…";
            ui.add(target, ui.button("P" + (note.optInt("page") + 1) + " · " + preview, false, view -> jump(note.optInt("page"))));
        }
        ui.add(target, ui.button("All notes & export", false, view -> showIndex()));
        ui.add(target, ui.button("Study Deck", false, view -> showDeck(false)));
    }

    private void resumeEnd() {
        if (current == null || current.endPage < 0) return;
        if ("pdf".equals(current.format) && !textMode) { textMode = true; showReader(); }
        jump(current.endPage);
        if (readingText != null) readingText.postDelayed(() -> { if (current != null) scrollToOffset(current.endOffset); }, 200);
    }

    private void jump(int page) {
        if (current == null || session == null) return;
        stopSpeaking(); current.page = Math.max(0, Math.min(current.pageCount - 1, page)); renderPage(true); refreshSidebar();
    }

    private void jumpDialog() {
        if (current == null) return;
        EditText input = ui.input("Page 1 to " + current.pageCount, false); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setText(String.valueOf(current.page + 1));
        AlertDialog dialog = show(dialog("Jump to page", input).setPositiveButton("Go", null).setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try { int page = Integer.parseInt(input.getText().toString()); if (page < 1 || page > current.pageCount) throw new NumberFormatException(); jump(page - 1); dialog.dismiss(); }
            catch (NumberFormatException error) { input.setError("Enter a valid page number"); }
        });
    }

    private void showIndex() {
        if (current == null) return;
        Book book = current;
        LinearLayout index = ui.column();
        ui.add(index, ui.button("Jump to page", false, view -> jumpDialog()));
        ui.add(index, ui.button("Search book", false, view -> searchDialog()));
        ui.add(index, ui.button("Export notes .md", true, view -> {
            pendingMarkdown = store.markdown(book); createDocument("text/markdown", book.title.replaceAll("[^a-zA-Z0-9_-]", "_") + "_notes.md", EXPORT_NOTES);
        }));
        ui.add(index, ui.heading("Chapters", 17));
        LinearLayout chapters = ui.column();
        ui.add(index, chapters);
        if (book.endPage >= 0) ui.add(index, ui.button("⚑ End Line · Page " + (book.endPage + 1), false, view -> resumeEnd()));
        ui.add(index, ui.heading("Highlights, notes & bookmarks", 17));
        List<JSONObject> notes = store.annotations(book.id, -1);
        if (notes.isEmpty()) ui.add(index, ui.label("Long-press text in Text mode to highlight it or add a margin note.", 15, ui.muted));
        for (JSONObject annotation : notes.subList(0, Math.min(150, notes.size()))) {
            LinearLayout card = ui.column(); card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); card.setBackground(ui.shape(ui.elevated, 12));
            ui.add(card, ui.heading("Page " + (annotation.optInt("page") + 1) + ("bookmark".equals(annotation.optString("type")) ? " · bookmark" : ""), 14));
            TextView quote = ui.label(annotation.optString("quote"), 14, ui.foreground); quote.setMaxLines(5); ui.add(card, quote);
            if (!annotation.optString("note").isEmpty()) ui.add(card, ui.label(annotation.optString("note"), 14, ui.muted));
            LinearLayout actions = ui.row();
            ui.addButton(actions, ui.button("Go", false, view -> jump(annotation.optInt("page"))));
            if (!"bookmark".equals(annotation.optString("type"))) ui.addButton(actions, ui.button("Edit", false, view -> noteEditor(annotation.optInt("start"), annotation.optInt("end"), annotation.optString("quote"), annotation)));
            ui.addButton(actions, ui.button("Delete", false, view -> { store.deleteAnnotation(annotation.optString("id")); index.removeView(card); decorateText(); refreshSidebar(); }));
            card.addView(actions); ui.add(index, card);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(index);
        AlertDialog dialog = show(dialog("Book index & study notes", scroll).setNegativeButton("Close", null));
        DocumentSession document = session;
        if (document != null) io.execute(() -> {
            try {
                JSONArray toc = document.chapters();
                main.post(() -> {
                    if (destroyed || !dialog.isShowing() || current != book) return;
                    if (toc.length() == 0) ui.add(chapters, ui.label("No embedded chapter outline. Use page jump or search.", 14, ui.muted));
                    for (int i = 0; i < Math.min(150, toc.length()); i++) {
                        JSONObject chapter = toc.optJSONObject(i);
                        if (chapter != null) ui.add(chapters, ui.button(chapter.optString("title"), false, view -> { dialog.dismiss(); jump(chapter.optInt("page")); }));
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private void searchDialog() {
        if (current == null || session == null) return;
        EditText query = ui.input("Word or phrase", false);
        AlertDialog dialog = show(dialog("Search this book", query).setPositiveButton("Find", null).setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) { query.setError("Enter a search term"); return; }
            dialog.dismiss(); searchBook(value);
        });
    }

    private void searchBook(String query) {
        Book book = current; DocumentSession document = session;
        if (book == null || document == null) return;
        job("Searching book…", () -> {
            JSONArray matches = new JSONArray(); String lower = query.toLowerCase(Locale.ROOT);
            for (int page = 0; page < book.pageCount && matches.length() < 50; page++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Search cancelled");
                TextPaginator.Page data = document.text(page);
                String text = data.heading.isEmpty() ? data.text : data.heading + "\n\n" + data.text;
                int offset = text.toLowerCase(Locale.ROOT).indexOf(lower);
                if (offset >= 0) matches.put(new JSONObject().put("page", page).put("offset", offset)
                        .put("preview", TextPaginator.snippet(text, Math.max(0, offset - 30))));
            }
            return matches;
        }, matches -> {
            if (current != book) return;
            LinearLayout list = ui.column();
            if (matches.length() == 0) ui.add(list, ui.label("No matches. Scanned PDF images need OCR before their text can be searched.", 15, ui.muted));
            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.optJSONObject(i);
                if (match != null) ui.add(list, ui.button("Page " + (match.optInt("page") + 1) + " · " + match.optString("preview"), false, null));
            }
            ScrollView scroll = new ScrollView(this); scroll.addView(list);
            AlertDialog results = show(dialog(matches.length() + " matching pages", scroll).setNegativeButton("Close", null));
            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.optJSONObject(i);
                list.getChildAt(i).setOnClickListener(view -> {
                    results.dismiss();
                    if (!textMode) { textMode = true; showReader(); }
                    jump(match.optInt("page"));
                    readingText.postDelayed(() -> scrollToOffset(match.optInt("offset")), 250);
                });
            }
        });
    }

    private Spinner spinner(String[] choices, int selected, Value<Integer> change) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, choices) {
            @Override public View getView(int position, View recycled, ViewGroup parent) { TextView view = (TextView)super.getView(position, recycled, parent); view.setTextColor(ui.foreground); view.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); return view; }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) { TextView view = (TextView)super.getDropDownView(position, recycled, parent); view.setTextColor(ui.foreground); view.setBackgroundColor(ui.surface); view.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); return view; }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(Math.max(0, Math.min(choices.length - 1, selected)));
        spinner.setBackground(ui.shape(ui.elevated, 12));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { change.ready(position); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        return spinner;
    }

    private void slider(LinearLayout form, String label, int value, int min, int max, Value<Integer> update) {
        TextView title = ui.label(label + " · " + value, 14, ui.foreground); ui.add(form, title);
        SeekBar bar = new SeekBar(this); bar.setContentDescription(label); bar.setMax(max - min); bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean user) { if (user) { title.setText(label + " · " + (progress + min)); update.ready(progress + min); } }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        ui.add(form, bar);
    }

    private void toggle(LinearLayout form, String label, String key, boolean fallback) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextColor(ui.foreground); box.setTextSize(15); box.setMinHeight(ui.dp(48));
        box.setChecked(settings.flag(key, fallback));
        box.setOnCheckedChangeListener((button, checked) -> { settings.put(key, checked); setAwake(); if (ruler != null) ruler.invalidate(); if ("bionic".equals(key)) decorateText(); styleReadingText(); });
        ui.add(form, box);
    }

    private void showSettings() {
        LinearLayout form = ui.column();
        ui.add(form, ui.heading("Reading appearance", 17));
        String[] themes = {"dark", "oled", "sepia", "paper"}; int theme = 0;
        for (int i = 0; i < themes.length; i++) if (themes[i].equals(settings.text("theme", "dark"))) theme = i;
        Spinner appearance = spinner(new String[]{"Dark", "OLED black", "Sepia", "Paper"}, theme, value -> settings.put("theme", themes[value]));
        appearance.setContentDescription("Reading theme"); ui.add(form, appearance);
        slider(form, "Warmth %", settings.number("warmth", 20), 0, 60, value -> { settings.put("warmth", value); care.invalidate(); });
        slider(form, "Extra dim %", settings.number("dimmer", 0), 0, 65, value -> { settings.put("dimmer", value); care.invalidate(); });
        ui.add(form, ui.heading("Native text layout", 17));
        String[] fonts = {"serif", "sans", "wide", "mono"}; int font = 0;
        for (int i = 0; i < fonts.length; i++) if (fonts[i].equals(settings.text("font", "serif"))) font = i;
        Spinner family = spinner(new String[]{"Serif", "Sans", "Wide", "Monospace"}, font, value -> { settings.put("font", fonts[value]); styleReadingText(); });
        family.setContentDescription("Reading font"); ui.add(form, family);
        slider(form, "Font size sp", settings.number("fontSize", 19), 14, 34, value -> { settings.put("fontSize", value); styleReadingText(); });
        slider(form, "Line spacing ×10", settings.number("lineSpacing", 18), 14, 24, value -> { settings.put("lineSpacing", value); styleReadingText(); });
        slider(form, "Letter spacing", settings.number("letterSpacing", 0), 0, 20, value -> { settings.put("letterSpacing", value); styleReadingText(); });
        slider(form, "Page width dp", settings.number("width", tablet() ? 800 : 680), 440, 1000, value -> settings.put("width", value));
        toggle(form, "Focus Bold word beginnings", "bionic", false);
        toggle(form, "Justified text (Android 8+)", "justify", false);
        toggle(form, "Reading ruler · drag the amber handle", "ruler", false);
        toggle(form, "Keep screen on while reading", "awake", true);
        ui.add(form, ui.heading("Reading rhythm", 17));
        int[] intervals = {10,15,20,30}; int interval = 2;
        for (int i = 0; i < intervals.length; i++) if (intervals[i] == settings.number("interval", 20)) interval = i;
        Spinner breaks = spinner(new String[]{"Eye break every 10 minutes", "Eye break every 15 minutes", "Eye break every 20 minutes", "Eye break every 30 minutes"}, interval, value -> {
            if (settings.number("interval", 20) != intervals[value]) timerSeconds = intervals[value] * 60;
            settings.put("interval", intervals[value]); updateTimer();
        });
        breaks.setContentDescription("Eye-break interval"); ui.add(form, breaks);
        slider(form, "Daily reading goal minutes", settings.number("goal", 30), 10, 120, value -> settings.put("goal", value));
        slider(form, "Auto-scroll speed ×10", Math.round(autoScrollSpeed * 10), 3, 50, value -> autoScrollSpeed = value / 10f);
        slider(form, "Read aloud speed %", settings.number("ttsRate", 100), 75, 200, value -> settings.put("ttsRate", value));
        if (speechReady && speech.getVoices() != null && !speech.getVoices().isEmpty()) {
            List<android.speech.tts.Voice> voices = new ArrayList<>(speech.getVoices());
            java.util.Collections.sort(voices, (left, right) -> left.getName().compareTo(right.getName()));
            String[] labels = new String[voices.size()]; int chosen = 0;
            for (int i = 0; i < voices.size(); i++) {
                labels[i] = voices.get(i).getName() + (voices.get(i).isNetworkConnectionRequired() ? " · network" : " · offline");
                if (voices.get(i).getName().equals(settings.text("ttsVoice", ""))) chosen = i;
            }
            Spinner voice = spinner(labels, chosen, value -> settings.put("ttsVoice", voices.get(value).getName()));
            voice.setContentDescription("Read aloud voice"); ui.add(form, voice);
        }
        ui.add(form, ui.button("20-second eye break now", false, view -> { if (current != null) showRest(); else toast("Open a book first"); }));
        ui.add(form, ui.heading("Study companion", 17));
        ui.add(form, ui.button("AI models & encrypted keys", false, view -> showAiManager()));
        toggle(form, "Try a backup AI profile on failure", "aiFallback", true);
        ui.add(form, ui.label("Files are granted through Android’s file/folder picker. Imported books are copied into private storage and remain readable offline.", 13, ui.muted));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        show(dialog("Eye & Text", scroll).setPositiveButton("Done", (d, w) -> {
            stopSpeaking(); if (current == null) showLibrary(); else { showReader(); renderPage(false); }
        }));
    }

    private void showRest() {
        if (current == null || restDialog != null && restDialog.isShowing()) return;
        stopSpeaking();
        restRemaining = 20;
        restLabel = ui.label("Look away from the screen at an object about 20 feet away. Blink slowly and relax your shoulders.\n\n20 seconds", 20, ui.foreground);
        restLabel.setGravity(Gravity.CENTER); restLabel.setPadding(0, ui.dp(24), 0, ui.dp(24));
        restDialog = show(dialog("Rest your eyes", restLabel).setCancelable(false).setPositiveButton("Continue", (d, w) -> endRest(true)).setNegativeButton("Skip", (d, w) -> endRest(false)));
        restDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void endRest(boolean completed) {
        if (completed) settings.put("breaks", settings.number("breaks", 0) + 1);
        restDialog = null;
        timerSeconds = Math.max(1, settings.number("interval", 20)) * 60;
        updateTimer();
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        if (current != null) {
            int visibility = immersive ? View.GONE : View.VISIBLE;
            if (readerHeader != null) readerHeader.setVisibility(visibility);
            if (readerToolbar != null) readerToolbar.setVisibility(visibility);
            if (readerFooter != null) readerFooter.setVisibility(visibility);
            if (readerNavigation != null) readerNavigation.setVisibility(visibility);
            if (exitImmersive != null) exitImmersive.setVisibility(immersive ? View.VISIBLE : View.GONE);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (immersive) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
            }
        } else getWindow().getDecorView().setSystemUiVisibility(immersive ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : ui.light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
    }

    private void toggleImmersive() { immersive = !immersive; applyImmersive(); }

    private void toggleScroll() {
        if (current == null || session == null) return;
        if (autoScroll) { stopScroll(); toast("Auto-scroll paused"); return; }
        stopSpeaking();
        if (!textMode) { textMode = true; showReader(); renderPage(true); }
        autoScroll = true; main.removeCallbacks(scroller); main.postDelayed(scroller, 500);
        toast("Auto-scroll started · tap text to pause");
    }

    private void stopScroll() { autoScroll = false; main.removeCallbacks(scroller); }

    private void showAiManager() {
        LinearLayout content = ui.column();
        ui.add(content, ui.label("AI is optional. Keys are encrypted with Android Keystore. Reading and manual flashcards work without a provider.", 14, ui.muted));
        JSONArray profiles = settings.profiles();
        ui.add(content, ui.button("Add AI profile", true, null));
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i); if (profile == null) continue;
            LinearLayout card = ui.column(); card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); card.setBackground(ui.shape(ui.elevated, 12));
            ui.add(card, ui.heading(profile.optString("name") + (i == settings.number("activeAi", 0) ? " · active" : ""), 15));
            ui.add(card, ui.label(profile.optString("model"), 13, ui.muted));
            LinearLayout actions = ui.row();
            ui.addButton(actions, ui.button("Use", false, null)); ui.addButton(actions, ui.button("Edit", false, null)); ui.addButton(actions, ui.button("Delete", false, null));
            card.addView(actions); ui.add(content, card);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = show(dialog("AI models", scroll).setNegativeButton("Close", null));
        content.getChildAt(1).setOnClickListener(view -> { dialog.dismiss(); aiEditor(null); });
        for (int i = 0; i < profiles.length(); i++) {
            final int index = i; JSONObject profile = profiles.optJSONObject(i);
            if (profile == null) continue;
            LinearLayout card = (LinearLayout)content.getChildAt(i + 2);
            LinearLayout actions = (LinearLayout)card.getChildAt(card.getChildCount() - 1);
            actions.getChildAt(0).setOnClickListener(view -> { settings.put("activeAi", index); dialog.dismiss(); showAiManager(); });
            actions.getChildAt(1).setOnClickListener(view -> { dialog.dismiss(); aiEditor(profile); });
            actions.getChildAt(2).setOnClickListener(view -> {
                JSONArray updated = new JSONArray(); for (int j = 0; j < profiles.length(); j++) if (j != index) updated.put(profiles.opt(j));
                settings.put("aiProfiles", updated.toString()); settings.put("activeAi", 0); dialog.dismiss(); showAiManager();
            });
        }
    }

    private void aiEditor(JSONObject original) {
        LinearLayout form = ui.column();
        EditText name = ui.input("Profile name", false); name.setText(original == null ? "Gemini Flash" : original.optString("name")); ui.add(form, name);
        EditText model = ui.input("Model ID", false); model.setText(original == null ? "gemini-2.5-flash" : original.optString("model"));
        EditText endpoint = ui.input("HTTPS chat/completions endpoint", false); endpoint.setText(original == null ? "" : original.optString("endpoint"));
        final int[] protocol = {original != null && !"gemini".equals(original.optString("provider")) ? 1 : 0};
        Spinner provider = spinner(new String[]{"Google Gemini", "OpenAI-compatible endpoint"}, protocol[0], value -> { protocol[0] = value; endpoint.setVisibility(value == 0 ? View.GONE : View.VISIBLE); });
        provider.setContentDescription("AI protocol"); ui.add(form, provider); ui.add(form, endpoint); ui.add(form, model);
        EditText key = ui.input("API key", false); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (original != null) try { key.setText(SecretVault.decrypt(original.optString("key"))); } catch (Exception ignored) { }
        ui.add(form, key);
        ui.add(form, ui.label("Presets · model IDs can be edited", 13, ui.muted));
        LinearLayout presets = ui.row();
        ui.addButton(presets, ui.button("Gemini", false, view -> { name.setText("Gemini Flash"); provider.setSelection(0); model.setText("gemini-2.5-flash"); endpoint.setText(""); }));
        ui.addButton(presets, ui.button("OpenAI", false, view -> { name.setText("OpenAI"); provider.setSelection(1); model.setText("gpt-4o-mini"); endpoint.setText("https://api.openai.com/v1/chat/completions"); }));
        ui.add(form, presets);
        LinearLayout more = ui.row();
        ui.addButton(more, ui.button("OpenRouter", false, view -> { name.setText("OpenRouter"); provider.setSelection(1); model.setText("meta-llama/llama-3.3-70b-instruct:free"); endpoint.setText("https://openrouter.ai/api/v1/chat/completions"); }));
        ui.addButton(more, ui.button("Groq", false, view -> { name.setText("Groq"); provider.setSelection(1); model.setText("llama-3.3-70b-versatile"); endpoint.setText("https://api.groq.com/openai/v1/chat/completions"); }));
        ui.add(form, more);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = show(dialog("AI profile", scroll).setPositiveButton("Save", null).setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (name.getText().toString().trim().isEmpty() || model.getText().toString().trim().isEmpty()) { name.setError("Enter a name and model ID"); return; }
            if (protocol[0] == 1 && !endpoint.getText().toString().trim().startsWith("https://")) { endpoint.setError("Use an HTTPS endpoint"); return; }
            try {
                String id = original == null ? UUID.randomUUID().toString() : original.optString("id");
                JSONObject profile = new JSONObject().put("id", id).put("name", name.getText().toString().trim())
                        .put("model", model.getText().toString().trim()).put("provider", protocol[0] == 0 ? "gemini" : "custom")
                        .put("endpoint", endpoint.getText().toString().trim()).put("key", SecretVault.encrypt(key.getText().toString().trim()));
                JSONArray profiles = settings.profiles(), updated = new JSONArray(); boolean replaced = false;
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject existing = profiles.optJSONObject(i);
                    if (existing != null && id.equals(existing.optString("id"))) { updated.put(profile); replaced = true; } else updated.put(existing);
                }
                if (!replaced) { updated.put(profile); settings.put("activeAi", updated.length() - 1); }
                settings.put("aiProfiles", updated.toString()); dialog.dismiss(); toast("AI profile saved with an encrypted key");
            } catch (Exception error) { toast("Could not encrypt the API key on this device"); }
        });
    }

    private void openAi(String selected, boolean define) {
        if (current == null || session == null) return;
        if (settings.profiles().length() == 0) { showAiManager(); return; }
        Book book = current; DocumentSession document = session; int page = book.page;
        job("Preparing reading context…", () -> {
            TextPaginator.Page data = document.text(page);
            String full = data.heading + "\n\n" + data.text;
            return selected.isEmpty() ? full : define ? "TARGET TERM: " + selected + "\nSURROUNDING PAGE:\n" + full : selected;
        }, context -> {
            if (current != book) return;
            try { aiChat(Book.fromJson(book.toJson()), context, define ? "Define \"" + selected + "\" as used in this book." : ""); }
            catch (Exception error) { toast("Could not prepare AI context"); }
        });
    }

    private void aiChat(Book book, String context, String initialQuestion) {
        LinearLayout content = ui.column();
        ui.add(content, ui.label("Context: " + book.title + " · page " + (book.page + 1) + ". Sent to your chosen provider when you ask.", 13, ui.muted));
        LinearLayout feed = ui.column();
        ScrollView scroll = new ScrollView(this); scroll.addView(feed);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tablet() ? 320 : 240)));
        EditText input = ui.input("Ask about this passage", false); input.setText(initialQuestion); ui.add(content, input);
        LinearLayout actions = ui.row();
        Button ask = ui.button("Ask", true, null), quiz = ui.button("Make quiz", false, null);
        ui.addButton(actions, ask); ui.addButton(actions, quiz); content.addView(actions);
        AlertDialog dialog = show(dialog("AI study companion", content).setNegativeButton("Close", null));
        final boolean[] pending = {false};
        class Request {
            void send(boolean makeQuiz) {
                if (pending[0]) return;
                String question = input.getText().toString().trim();
                if (!makeQuiz && question.isEmpty()) { input.setError("Enter a question"); return; }
                pending[0] = true; ask.setEnabled(false); quiz.setEnabled(false);
                TextView waiting = ui.label("Thinking…", 14, ui.accent); ui.add(feed, waiting);
                networkRequest = network.submit(() -> {
                    try {
                        AiClient.Reply reply = AiClient.ask(settings, book, context, question, makeQuiz);
                        JSONArray questions = makeQuiz ? AiClient.questions(reply.text) : null;
                        main.post(() -> {
                            if (destroyed || !dialog.isShowing()) return;
                            pending[0] = false; ask.setEnabled(true); quiz.setEnabled(true); feed.removeView(waiting);
                            ui.add(feed, ui.heading(reply.model, 13));
                            if (questions == null) {
                                TextView answer = ui.label(reply.text, 16, ui.foreground); answer.setTextIsSelectable(true); ui.add(feed, answer);
                                ui.add(feed, ui.button("Copy answer", false, view -> copy(reply.text)));
                            } else renderQuiz(feed, questions, book);
                            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                        });
                    } catch (Exception error) {
                        main.post(() -> { if (!destroyed && dialog.isShowing()) { pending[0] = false; ask.setEnabled(true); quiz.setEnabled(true); waiting.setText(safe(error)); } });
                    }
                });
            }
        }
        Request request = new Request();
        ask.setOnClickListener(view -> request.send(false)); quiz.setOnClickListener(view -> request.send(true));
        dialog.setOnDismissListener(value -> { if (pending[0] && networkRequest != null) networkRequest.cancel(true); });
        if (!initialQuestion.isEmpty()) request.send(false);
    }

    private void renderQuiz(LinearLayout feed, JSONArray questions, Book book) {
        ui.add(feed, ui.button("Save all to Study Deck", true, view -> {
            for (int i = 0; i < questions.length(); i++) { JSONObject question = questions.optJSONObject(i); if (question != null) saveQuestion(question, book); }
            toast("Questions saved for offline review");
        }));
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i); if (question == null) continue;
            LinearLayout card = ui.column(); card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); card.setBackground(ui.shape(ui.elevated, 12));
            ui.add(card, ui.heading(question.optString("question"), 16));
            TextView explanation = ui.label(question.optString("explanation", question.optString("answer")), 15, ui.foreground); explanation.setVisibility(View.GONE);
            JSONArray options = question.optJSONArray("options");
            if ("mcq".equals(question.optString("type")) && options != null) {
                final boolean[] answered = {false};
                for (int option = 0; option < options.length(); option++) {
                    final int choice = option;
                    Button button = ui.button(options.optString(option), false, null);
                    button.setOnClickListener(view -> {
                        if (answered[0]) return; answered[0] = true;
                        button.setBackground(ui.shape(choice == question.optInt("answerIndex") ? 0x6644BB77 : 0x66DD5555, 12));
                        explanation.setText("Answer: " + options.optString(question.optInt("answerIndex")) + "\n\n" + question.optString("explanation"));
                        explanation.setVisibility(View.VISIBLE);
                    });
                    ui.add(card, button);
                }
            } else ui.add(card, ui.button("Reveal answer", false, view -> explanation.setVisibility(View.VISIBLE)));
            ui.add(card, explanation);
            ui.add(card, ui.button("Save card", false, view -> { saveQuestion(question, book); toast("Card saved"); }));
            ui.add(feed, card);
        }
    }

    private void saveQuestion(JSONObject question, Book book) {
        try {
            for (JSONObject existing : store.cards(false)) if (book.id.equals(existing.optString("bookId")) && question.optString("question").equals(existing.optString("question"))) return;
            JSONObject card = new JSONObject(question.toString()).put("id", UUID.randomUUID().toString()).put("bookId", book.id)
                    .put("bookTitle", book.title).put("page", book.page).put("due", System.currentTimeMillis()).put("intervalDays", 0);
            store.saveCard(card);
        } catch (Exception error) { toast("Could not save study card"); }
    }

    private void createCard(String selected) {
        Book book = current;
        LinearLayout form = ui.column();
        EditText question = ui.input("Recall question", true), answer = ui.input("Answer", true); answer.setText(selected);
        ui.add(form, question); ui.add(form, answer);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = show(dialog("Create offline flashcard", scroll).setPositiveButton("Save", null).setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (question.getText().toString().trim().isEmpty() || answer.getText().toString().trim().isEmpty()) { question.setError("Enter a question and answer"); return; }
            try {
                JSONObject card = new JSONObject().put("id", UUID.randomUUID().toString()).put("type", "flashcard")
                        .put("question", question.getText().toString().trim()).put("answer", answer.getText().toString().trim())
                        .put("bookId", book == null ? "general" : book.id).put("bookTitle", book == null ? "Manual study" : book.title)
                        .put("page", book == null ? 0 : book.page).put("due", System.currentTimeMillis()).put("intervalDays", 0);
                store.saveCard(card); dialog.dismiss(); toast("Flashcard saved for offline review");
            } catch (Exception error) { toast("Could not save card"); }
        });
    }

    private void showDeck(boolean all) {
        LinearLayout body = ui.column();
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        class Deck {
            boolean showAll = all;
            int index;
            void render() {
                body.removeAllViews();
                List<JSONObject> cards = store.cards(!showAll);
                ui.add(body, ui.label(store.cards(true).size() + " due · " + store.cards(false).size() + " total · available offline", 13, ui.muted));
                LinearLayout filters = ui.row();
                ui.addButton(filters, ui.button(showAll ? "Due only" : "All cards", false, view -> { showAll = !showAll; index = 0; render(); }));
                ui.addButton(filters, ui.button("Create card", false, view -> createCard(""))); ui.add(body, filters);
                if (cards.isEmpty()) { ui.add(body, ui.heading(showAll ? "Your deck is empty" : "All due cards reviewed", 19)); ui.add(body, ui.label("Create your own cards from selected text, or generate a quiz with the optional AI companion.", 15, ui.muted)); return; }
                index %= cards.size(); JSONObject card = cards.get(index);
                ui.add(body, ui.label(card.optString("bookTitle") + " · page " + (card.optInt("page") + 1) + " · " + (index + 1) + "/" + cards.size(), 13, ui.accent));
                ui.add(body, ui.heading(card.optString("question"), 22));
                TextView answer = ui.label(card.optString("answer", card.optString("explanation")), 17, ui.foreground); answer.setVisibility(View.GONE);
                LinearLayout ratings = ui.row(); ratings.setVisibility(View.GONE);
                String[] values = {"again", "good", "easy"};
                for (String rating : values) {
                    int days = ReviewSchedule.nextInterval(card.optInt("intervalDays"), rating);
                    ui.addButton(ratings, ui.button(rating.substring(0, 1).toUpperCase(Locale.ROOT) + rating.substring(1) + ("again".equals(rating) ? " · 10m" : " · " + days + "d"), false, view -> {
                        try { int previous = card.optInt("intervalDays"); card.put("due", ReviewSchedule.nextDue(System.currentTimeMillis(), previous, rating)).put("intervalDays", ReviewSchedule.nextInterval(previous, rating)); store.saveCard(card); if (showAll) index++; render(); }
                        catch (Exception error) { toast("Could not schedule card"); }
                    }));
                }
                JSONArray options = card.optJSONArray("options");
                if ("mcq".equals(card.optString("type")) && options != null) {
                    final boolean[] answered = {false};
                    for (int i = 0; i < options.length(); i++) {
                        final int selected = i;
                        Button option = ui.button(options.optString(i), false, null);
                        option.setOnClickListener(view -> {
                            if (answered[0]) return; answered[0] = true;
                            option.setBackground(ui.shape(selected == card.optInt("answerIndex") ? 0x6644BB77 : 0x66DD5555, 12));
                            answer.setText("Answer: " + options.optString(card.optInt("answerIndex")) + "\n\n" + card.optString("explanation"));
                            answer.setVisibility(View.VISIBLE); ratings.setVisibility(View.VISIBLE);
                        });
                        ui.add(body, option);
                    }
                } else ui.add(body, ui.button("Reveal answer", true, view -> { answer.setVisibility(View.VISIBLE); ratings.setVisibility(View.VISIBLE); }));
                ui.add(body, answer); ui.add(body, ratings);
                LinearLayout navigation = ui.row();
                ui.addButton(navigation, ui.button("Delete", false, view -> { store.deleteCard(card.optString("id")); render(); }));
                ui.addButton(navigation, ui.button("Next card", false, view -> { index++; render(); }));
                ui.add(body, navigation);
            }
        }
        Deck deck = new Deck(); deck.render();
        show(dialog("Offline Study Deck", scroll).setNegativeButton("Close", null));
    }

    private void initSpeech() {
        speech = new TextToSpeech(this, status -> main.post(() -> {
            if (destroyed) return;
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady && speech != null) speech.setLanguage(Locale.getDefault());
        }));
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> {
                if (destroyed || !id.equals(activeUtterance) || !speaking) return;
                if (readingText != null && ttsPosition < utterances.size()) {
                    int[] range = utterances.get(ttsPosition); decorateText();
                    if (range[1] <= readingText.length()) {
                        SpannableString text = new SpannableString(readingText.getText());
                        text.setSpan(new BackgroundColorSpan(0x55D99B52), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        readingText.setText(text); scrollToOffset(range[0]);
                    }
                }
            }); }
            @Override public void onDone(String id) { main.post(() -> { if (!destroyed && id.equals(activeUtterance) && speaking) { ttsPosition++; speakNext(); } }); }
            @Override public void onError(String id) { main.post(() -> { if (!destroyed && id.equals(activeUtterance)) { stopSpeaking(); toast("This voice could not read the passage. Choose a downloaded voice in Eye & Text."); } }); }
        });
    }

    private void toggleSpeaking() {
        if (current == null || session == null) return;
        if (!speechReady) { toast("Install or enable an Android text-to-speech engine and voice first"); return; }
        if (speaking) { speechPlayer(); return; }
        stopScroll(); Book book = current; DocumentSession document = session; int page = book.page;
        job("Preparing read aloud…", () -> { TextPaginator.Page content = document.text(page); return content.heading.isEmpty() ? content.text : content.heading + "\n\n" + content.text; }, text -> {
            if (current != book || current.page != page) return;
            if (text.trim().isEmpty()) { toast("This page has no extractable text to read aloud"); return; }
            pageText = text;
            utterances.clear();
            java.util.regex.Matcher sentences = java.util.regex.Pattern.compile("[^.!?\\n]+[.!?]?").matcher(text);
            while (sentences.find()) if (!sentences.group().trim().isEmpty()) utterances.add(new int[]{sentences.start(), sentences.end()});
            ttsPosition = 0; speaking = true; speakNext(); speechPlayer();
        });
    }

    private void speakNext() {
        if (!speaking || !speechReady || current == null) return;
        if (ttsPosition >= utterances.size()) { stopSpeaking(); toast("Page finished"); return; }
        int[] range = utterances.get(ttsPosition);
        if (range[1] > pageText.length()) { stopSpeaking(); return; }
        speech.setSpeechRate(Math.max(.75f, Math.min(2f, settings.number("ttsRate", 100) / 100f)));
        if (speech.getVoices() != null) for (android.speech.tts.Voice voice : speech.getVoices()) if (voice.getName().equals(settings.text("ttsVoice", ""))) { speech.setVoice(voice); break; }
        activeUtterance = UUID.randomUUID().toString();
        speech.speak(pageText.substring(range[0], range[1]), TextToSpeech.QUEUE_FLUSH, null, activeUtterance);
    }

    private void speechPlayer() {
        LinearLayout content = ui.column();
        ui.add(content, ui.label("Android read aloud · current page", 15, ui.muted));
        Button play = ui.button(speaking ? "Pause" : "Resume", true, view -> {
            if (speaking) { speaking = false; activeUtterance = ""; speech.stop(); }
            else { speaking = true; speakNext(); }
            ((Button)view).setText(speaking ? "Pause" : "Resume");
        });
        ui.add(content, play);
        slider(content, "Playback speed %", settings.number("ttsRate", 100), 75, 200, value -> settings.put("ttsRate", value));
        Button timer = ui.button("Sleep · " + (sleepMinutes == 0 ? "off" : sleepMinutes + "m"), false, view -> {
            sleepMinutes = sleepMinutes == 0 ? 15 : sleepMinutes == 15 ? 30 : 0;
            main.removeCallbacks(sleep); if (sleepMinutes > 0) main.postDelayed(sleep, sleepMinutes * 60L * 1000);
            ((Button)view).setText("Sleep · " + (sleepMinutes == 0 ? "off" : sleepMinutes + "m"));
        });
        ui.add(content, timer);
        show(dialog("Read aloud", content).setPositiveButton("Hide player", null).setNegativeButton("Stop", (d, w) -> stopSpeaking()));
    }

    private void stopSpeaking() {
        speaking = false; activeUtterance = ""; main.removeCallbacks(sleep); sleepMinutes = 0;
        if (speech != null) speech.stop();
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText("EyeSafe excerpt", text)); toast("Copied"); }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() { if (current != null && immersive) toggleImmersive(); else if (current != null) showLibrary(); else finish(); }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (current != null) {
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_PAGE_DOWN) { turnPage(1); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_PAGE_UP) { turnPage(-1); return true; }
            if (key == KeyEvent.KEYCODE_SPACE) { toggleScroll(); return true; }
        }
        return super.onKeyDown(key, event);
    }
}
