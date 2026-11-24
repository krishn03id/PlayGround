package io.github.krishn03id.eyesafe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArchivePathsTest {
    @Test public void backupAcceptsOnlyLibraryFiles() {
        assertTrue(ArchivePaths.isAllowed("library.json"));
        assertTrue(ArchivePaths.isAllowed("books/sample-quiet-discipline/source.md"));
        assertTrue(ArchivePaths.isAllowed("books/123-456/pages.json"));
        assertTrue(ArchivePaths.isAllowed("books/123-456/cover.png"));
        assertFalse(ArchivePaths.isAllowed("books/123-456/api-keys.json"));
        assertFalse(ArchivePaths.isAllowed("books/123-456/source.exe"));
    }

    @Test public void rejectsTraversalAndAbsolutePaths() {
        assertFalse(ArchivePaths.isAllowed("../library.json"));
        assertFalse(ArchivePaths.isAllowed("/books/test/source.pdf"));
        assertFalse(ArchivePaths.isAllowed("books/test/../../preferences.xml"));
        assertFalse(ArchivePaths.isAllowed("books\\test\\source.pdf"));
        assertFalse(ArchivePaths.validBookId("../test"));
        assertFalse(ArchivePaths.validBookId(""));
        assertFalse(ArchivePaths.validBookId(null));
    }
}
