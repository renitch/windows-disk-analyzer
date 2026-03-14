package com.diskanalyzer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Application-wide settings, persisted across sessions via
 * {@link java.util.prefs.Preferences}.
 */
public class AppSettings {

    private static final AppSettings INSTANCE = new AppSettings();

    private static final String PREF_SKIP_PATHS      = "skipPaths";
    private static final String PREF_CONFIRM_DELETE   = "confirmDeletion";
    private static final String PATH_SEPARATOR        = "\u0000"; // NUL – safe delimiter

    private final Preferences prefs =
            Preferences.userNodeForPackage(AppSettings.class);

    /** Ordered set of absolute paths to skip during scanning. */
    private final Set<String> skipPaths = new LinkedHashSet<>();

    private boolean confirmDeletion;

    private AppSettings() {
        load();
    }

    public static AppSettings get() { return INSTANCE; }

    // ── Skip paths ────────────────────────────────────────────────

    public Set<String> getSkipPaths() {
        return Collections.unmodifiableSet(skipPaths);
    }

    public boolean isSkipped(String absolutePath) {
        return skipPaths.contains(absolutePath);
    }

    public void addSkipPath(String absolutePath) {
        skipPaths.add(absolutePath);
        save();
    }

    public void removeSkipPath(String absolutePath) {
        skipPaths.remove(absolutePath);
        save();
    }

    public void clearSkipPaths() {
        skipPaths.clear();
        save();
    }

    // ── Deletion settings ─────────────────────────────────────────

    public boolean isConfirmDeletion() { return confirmDeletion; }

    public void setConfirmDeletion(boolean value) {
        confirmDeletion = value;
        save();
    }

    // ── Persistence ───────────────────────────────────────────────

    private void load() {
        confirmDeletion = prefs.getBoolean(PREF_CONFIRM_DELETE, true);

        String raw = prefs.get(PREF_SKIP_PATHS, "");
        skipPaths.clear();
        if (!raw.isEmpty()) {
            for (String part : raw.split(PATH_SEPARATOR, -1)) {
                if (!part.isBlank()) skipPaths.add(part);
            }
        }
    }

    private void save() {
        prefs.putBoolean(PREF_CONFIRM_DELETE, confirmDeletion);
        prefs.put(PREF_SKIP_PATHS, String.join(PATH_SEPARATOR, skipPaths));
    }
}
