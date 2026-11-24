package io.github.krishn03id.eyesafe;

final class ArchivePaths {
    static boolean isAllowed(String path) {
        return "library.json".equals(path) || path.matches(
                "books/[a-zA-Z0-9-]{1,80}/(?:source\\.(?:pdf|epub|txt|md)|pages\\.json|cover\\.png)");
    }

    static boolean validBookId(String id) {
        return id != null && id.matches("[a-zA-Z0-9-]{1,80}");
    }
}
