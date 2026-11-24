package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeReaderTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private File fixture(String name) throws Exception {
        File directory = new File(context().getCacheDir(), "imports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return new File(directory, name);
    }

    private ActivityScenario<MainActivity> open(File file, String mime) {
        Uri uri = FileProvider.getUriForFile(context(), context().getPackageName() + ".files", file);
        Intent intent = new Intent(context(), MainActivity.class).setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return ActivityScenario.launch(intent);
    }

    private void awaitText(ActivityScenario<MainActivity> scenario, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        AtomicBoolean ready = new AtomicBoolean(false);
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                View view = activity.findViewById(R.id.reader_text);
                ready.set(view instanceof TextView && ((TextView)view).getText().toString().contains(expected));
            });
            if (ready.get()) return;
            Thread.sleep(100);
        }
        fail("Native reader did not display: " + expected);
    }

    private void capture(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        File directory = new File(context().getFilesDir(), "screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-api" + Build.VERSION.SDK_INT + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    @Test public void sampleReaderSurvivesRotationAndRecreation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Sample book")).perform(click());
            awaitText(scenario, "At five in the morning");
            scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            awaitText(scenario, "At five in the morning");
            scenario.recreate();
            awaitText(scenario, "At five in the morning");
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.reader_content));
                assertTrue(activity.findViewById(R.id.reader_text) instanceof TextView);
                if (activity.getResources().getConfiguration().screenWidthDp >= 600)
                    assertNotNull(activity.findViewById(R.id.tablet_sidebar));
            });
            capture("native-sample-landscape");
            onView(withText("Full mode")).perform(scrollTo(), click());
            onView(withText("Exit full mode")).check(matches(isDisplayed()));
            capture("native-fullscreen");
            onView(withText("Exit full mode")).perform(click());
        }
    }

    @Test public void markdownImportsThroughAndroidContentUrisAndPersists() throws Exception {
        File file = fixture("native-import.md");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("# Native import\n\nThis passage was imported through Android file access and saved offline.".getBytes(StandardCharsets.UTF_8));
        }
        try (ActivityScenario<MainActivity> scenario = open(file, "text/markdown")) {
            awaitText(scenario, "imported through Android file access");
            assertTrue(file.delete());
            scenario.recreate();
            awaitText(scenario, "imported through Android file access");
        }
    }

    @Test public void epubSpineIsParsedWithNativeZipAndXmlApis() throws Exception {
        File file = fixture("native-book.epub");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip, "mimetype", "application/epub+zip");
            entry(zip, "META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>");
            entry(zip, "OEBPS/book.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata><dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">Native EPUB</dc:title></metadata><manifest><item id=\"c1\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>");
            entry(zip, "OEBPS/chapter.xhtml", "<html><body><h1>Quiet chapter</h1><p>This EPUB is read by native Android components.</p></body></html>");
        }
        try (ActivityScenario<MainActivity> scenario = open(file, "application/epub+zip")) {
            awaitText(scenario, "native Android components");
        }
    }

    @Test public void pdfRendersNativelyAndOffersExtractedText() throws Exception {
        File file = fixture("native-pdf.pdf");
        try (PdfDocument document = new PdfDocument()) {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(400, 600, 1).create());
            Paint paint = new Paint(); paint.setTextSize(24);
            page.getCanvas().drawText("Offline native PDF reading", 30, 60, paint);
            document.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(file)) { document.writeTo(output); }
        }
        try (ActivityScenario<MainActivity> scenario = open(file, "application/pdf")) {
            long deadline = System.currentTimeMillis() + 15000;
            AtomicBoolean ready = new AtomicBoolean(false);
            while (!ready.get() && System.currentTimeMillis() < deadline) {
                scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.reader_content) != null && activity.findViewById(R.id.reader_text) == null));
                Thread.sleep(100);
            }
            assertTrue("PDF page view did not open", ready.get());
            onView(withText("Text mode")).perform(click());
            awaitText(scenario, "Offline native PDF reading");
            capture("native-pdf-text");
        }
    }

    private static void entry(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
