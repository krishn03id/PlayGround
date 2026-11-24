package io.github.krishn03id.eyesafe;

import android.text.Html;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class EpubReader {
    static final class Result {
        String title;
        byte[] cover;
        final List<TextPaginator.Page> pages = new ArrayList<>();
    }

    private static final int MAX_ENTRY = 12 * 1024 * 1024;
    private static final int MAX_TEXT = 24 * 1024 * 1024;

    @SuppressWarnings("deprecation")
    static Result read(File file, String fallbackTitle) throws Exception {
        Result result = new Result();
        result.title = fallbackTitle;
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                String encryption = text(zip, "META-INF/encryption.xml");
                if (encryption.contains("EncryptedData") && !encryption.contains("embedding"))
                    throw new IOException("DRM-protected EPUBs cannot be opened");
            }
            String opfPath = "";
            XmlPullParser container = parser(text(zip, "META-INF/container.xml"));
            for (int event = container.next(); event != XmlPullParser.END_DOCUMENT; event = container.next()) {
                if (event == XmlPullParser.START_TAG && "rootfile".equals(container.getName())) {
                    opfPath = container.getAttributeValue(null, "full-path");
                    break;
                }
            }
            if (opfPath == null || opfPath.isEmpty()) throw new IOException("EPUB package is missing");
            Map<String, String> manifest = new HashMap<>();
            List<String> spine = new ArrayList<>();
            String coverId = "";
            String coverHref = "";
            XmlPullParser opf = parser(text(zip, opfPath));
            for (int event = opf.next(); event != XmlPullParser.END_DOCUMENT; event = opf.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                String name = opf.getName();
                if ("title".equals(name)) result.title = opf.nextText().trim();
                else if ("item".equals(name)) {
                    String id = opf.getAttributeValue(null, "id");
                    String href = opf.getAttributeValue(null, "href");
                    if (id != null && href != null) manifest.put(id, href);
                    String properties = opf.getAttributeValue(null, "properties");
                    if (properties != null && properties.contains("cover-image")) coverHref = href;
                } else if ("itemref".equals(name)) {
                    String id = opf.getAttributeValue(null, "idref");
                    if (id != null) spine.add(id);
                } else if ("meta".equals(name) && "cover".equals(opf.getAttributeValue(null, "name"))) {
                    coverId = opf.getAttributeValue(null, "content");
                }
            }
            if (result.title.isEmpty()) result.title = fallbackTitle;
            if (coverHref == null || coverHref.isEmpty()) coverHref = manifest.get(coverId);
            if (coverHref != null) {
                try { result.cover = bytes(zip, resolve(opfPath, coverHref)); } catch (IOException ignored) { }
            }
            int totalText = 0;
            Pattern headings = Pattern.compile("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>");
            for (String id : spine) {
                String href = manifest.get(id);
                if (href == null) continue;
                String html = text(zip, resolve(opfPath, href))
                        .replaceAll("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>", "");
                Matcher match = headings.matcher(html);
                String heading = match.find() ? Html.fromHtml(match.group(1)).toString().trim() : "";
                String content = Html.fromHtml(html).toString().replace("\uFFFC", "").trim();
                totalText += content.length();
                if (totalText > MAX_TEXT) throw new IOException("EPUB text is too large (24 MB limit)");
                if (content.isEmpty()) continue;
                List<TextPaginator.Page> pages = TextPaginator.paginate(content, false);
                if (!heading.isEmpty() && !pages.isEmpty()) {
                    TextPaginator.Page first = pages.get(0);
                    pages.set(0, new TextPaginator.Page(heading, first.text));
                }
                result.pages.addAll(pages);
            }
            if (result.pages.isEmpty()) throw new IOException("No readable EPUB chapters found");
        }
        return result;
    }

    private static XmlPullParser parser(String source) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new java.io.StringReader(source));
        return parser;
    }

    private static String resolve(String base, String relative) throws IOException {
        try {
            URI resolved = new URI(base.replace(" ", "%20")).resolve(relative.replace(" ", "%20")).normalize();
            String path = resolved.getPath();
            if (resolved.isAbsolute() || path.startsWith("/") || path.startsWith("../"))
                throw new IOException("Invalid EPUB resource path");
            return path;
        } catch (java.net.URISyntaxException e) { throw new IOException("Invalid EPUB resource", e); }
    }

    private static String text(ZipFile zip, String name) throws IOException {
        return new String(bytes(zip, name), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing EPUB resource: " + name);
        if (entry.getSize() > MAX_ENTRY) throw new IOException("EPUB resource is too large");
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_ENTRY) throw new IOException("EPUB resource is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
