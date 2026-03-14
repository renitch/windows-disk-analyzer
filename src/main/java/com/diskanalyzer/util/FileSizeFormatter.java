package com.diskanalyzer.util;

public class FileSizeFormatter {

    public static String format(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1_024L) return bytes + " B";
        double kb = bytes / 1_024.0;
        if (kb < 1_024.0) return String.format("%.1f KB", kb);
        double mb = kb / 1_024.0;
        if (mb < 1_024.0) return String.format("%.1f MB", mb);
        double gb = mb / 1_024.0;
        if (gb < 1_024.0) return String.format("%.2f GB", gb);
        double tb = gb / 1_024.0;
        return String.format("%.2f TB", tb);
    }

    public static String formatWithCount(long bytes, long files) {
        return format(bytes) + (files > 0 ? "  (" + files + " files)" : "");
    }
}
