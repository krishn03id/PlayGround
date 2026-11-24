package io.github.krishn03id.eyesafe;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TextPaginatorTest {
    @Test public void headingsDoNotRequireBlankLines() {
        List<TextPaginator.Page> pages = TextPaginator.paginate("# First\nThe first paragraph.\n\n## Second\nThe second paragraph.", true);
        assertEquals(2, pages.size());
        assertEquals("First", pages.get(0).heading);
        assertEquals("The first paragraph.", pages.get(0).text);
        assertEquals("Second", pages.get(1).heading);
    }

    @Test public void longParagraphsPreserveAllWordsAndBoundMemoryPerPage() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 1500; i++) input.append("word").append(i).append(' ');
        List<TextPaginator.Page> pages = TextPaginator.paginate(input.toString(), false);
        StringBuilder combined = new StringBuilder();
        for (TextPaginator.Page page : pages) {
            assertTrue(page.text.length() <= 2200);
            combined.append(page.text).append(' ');
        }
        assertTrue(pages.size() > 1);
        assertEquals(input.toString().trim(), combined.toString().trim());
    }

    @Test public void unicodeSurrogatesAreNeverSplitAcrossPages() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 4000; i++) input.append("📚");
        List<TextPaginator.Page> pages = TextPaginator.paginate(input.toString(), false);
        StringBuilder combined = new StringBuilder();
        for (TextPaginator.Page page : pages) {
            assertFalse(Character.isHighSurrogate(page.text.charAt(page.text.length() - 1)));
            assertFalse(Character.isLowSurrogate(page.text.charAt(0)));
            combined.append(page.text);
        }
        assertEquals(input.toString(), combined.toString());
    }

    @Test public void markdownLinksAndEmptyFilesAreReadable() {
        assertEquals("Visit EyeSafe and use bold text.", TextPaginator.plainMarkdown("Visit [EyeSafe](https://example.com) and use **bold** text."));
        assertEquals("(Empty document)", TextPaginator.paginate("\r\n\uFEFF ", false).get(0).text);
        assertEquals("", TextPaginator.snippet("", 100));
    }
}
