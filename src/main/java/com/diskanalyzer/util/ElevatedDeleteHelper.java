package com.diskanalyzer.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Attempts to delete a file or directory by spawning a privileged OS process.
 *
 * <table>
 *   <tr><th>OS</th><th>Mechanism</th></tr>
 *   <tr><td>Windows</td><td>PowerShell {@code Start-Process} with {@code -Verb RunAs} (UAC)</td></tr>
 *   <tr><td>macOS</td><td>{@code osascript} "do shell script … with administrator privileges"</td></tr>
 *   <tr><td>Linux</td><td>{@code pkexec} (polkit) → {@code gksudo} → {@code kdesudo} (fallback chain)</td></tr>
 * </table>
 *
 * All methods are blocking and must be called from a background thread.
 */
public class ElevatedDeleteHelper {

    private static final int TIMEOUT_SECONDS = 300; // 5 min max

    public enum OS { WINDOWS, MAC, LINUX, UNKNOWN }

    /** Detects the current operating system. */
    public static OS detectOS() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win"))   return OS.WINDOWS;
        if (name.contains("mac"))   return OS.MAC;
        if (name.contains("linux")) return OS.LINUX;
        return OS.UNKNOWN;
    }

    /**
     * Result of an elevated delete attempt.
     */
    public record Result(boolean success, String errorMessage) {
        public static Result ok()              { return new Result(true,  null); }
        public static Result fail(String msg)  { return new Result(false, msg); }
    }

    /**
     * Deletes {@code target} using elevated privileges appropriate for the current OS.
     *
     * @param target          the file or directory to delete
     * @param progressUpdate  consumer called with human-readable status strings (may be null)
     * @return                a {@link Result} indicating success or failure
     */
    public static Result delete(Path target, Consumer<String> progressUpdate) {
        Consumer<String> log = progressUpdate != null ? progressUpdate : msg -> {};
        String path = target.toAbsolutePath().toString();

        log.accept("Requesting elevated privileges for: " + path + " …");

        return switch (detectOS()) {
            case WINDOWS -> deleteWindows(path, log);
            case MAC     -> deleteMac(path, log);
            case LINUX   -> deleteLinux(path, log);
            default      -> Result.fail("Unsupported operating system: "
                                + System.getProperty("os.name"));
        };
    }

    // ── Windows ───────────────────────────────────────────────────────

    /**
     * Windows: Uses PowerShell {@code Start-Process} with {@code -Verb RunAs}
     * to trigger UAC elevation, then runs {@code cmd /c rd /s /q} or {@code del /f /q}.
     * The process is synchronous ({@code -Wait}).
     */
    private static Result deleteWindows(String path, Consumer<String> log) {
        log.accept("Requesting UAC elevation (Windows) …");

        // Build the inner command to run as admin
        // rd /s /q handles directories; del /f /s /q handles files.
        // We use a combined approach: try rd first, fall back to del.
        String innerCmd = "if exist \"" + path + "\\\" (rd /s /q \"" + path + "\") "
                        + "else (del /f /s /q \"" + path + "\")";

        // Escape single quotes inside the argument for PowerShell
        String psArg = innerCmd.replace("'", "''");

        List<String> cmd = List.of(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Start-Process", "cmd.exe",
            "-ArgumentList", "'/c " + psArg + "'",
            "-Verb", "RunAs",
            "-Wait",
            "-WindowStyle", "Hidden"
        );

        return runProcess(cmd, TIMEOUT_SECONDS, log);
    }

    // ── macOS ─────────────────────────────────────────────────────────

    /**
     * macOS: Uses {@code osascript} to invoke {@code rm -rf} with "with administrator privileges",
     * which shows the native macOS authentication dialog.
     */
    private static Result deleteMac(String path, Consumer<String> log) {
        log.accept("Requesting administrator privileges (macOS) …");

        // Escape single-quotes inside the path for the shell script string
        String escaped = path.replace("'", "'\\''");
        String script  = "do shell script \"rm -rf '\" & quoted form of \"" + escaped
                       + "\" & \"'\" with administrator privileges";

        List<String> cmd = List.of("osascript", "-e", script);
        return runProcess(cmd, TIMEOUT_SECONDS, log);
    }

    // ── Linux ─────────────────────────────────────────────────────────

    /**
     * Linux: Tries {@code pkexec}, then {@code gksudo}, then {@code kdesudo}
     * to invoke {@code rm -rf} with a graphical privilege prompt.
     */
    private static Result deleteLinux(String path, Consumer<String> log) {
        // Ordered preference list of graphical sudo helpers
        String[][] candidates = {
            { "pkexec",  "rm", "-rf", path },
            { "gksudo",  "--description", "Disk Analyzer — elevated delete",
                         "rm", "-rf", path },
            { "kdesudo", "-d", "--comment",
                         "Disk Analyzer requires administrator rights to delete this item.",
                         "rm", "-rf", path },
        };

        for (String[] cmd : candidates) {
            if (!commandExists(cmd[0])) continue;
            log.accept("Requesting privileges via " + cmd[0] + " …");
            Result r = runProcess(List.of(cmd), TIMEOUT_SECONDS, log);
            if (r.success()) return r;
            // If the helper exists but returned non-zero, surface that error
            if (!r.errorMessage().startsWith("Command not found")) return r;
        }

        return Result.fail(
            "No graphical privilege helper found on this Linux system.\n"
            + "Tried: pkexec, gksudo, kdesudo.\n"
            + "You can manually delete the item with:\n"
            + "    sudo rm -rf \"" + path + "\"");
    }

    // ── Shared process runner ─────────────────────────────────────────

    private static Result runProcess(List<String> cmd, int timeoutSecs, Consumer<String> log) {
        Process proc;
        try {
            proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // "error=2" typically means the executable was not found
            if (msg.contains("error=2") || msg.contains("No such file")) {
                return Result.fail("Command not found: " + cmd.get(0));
            }
            return Result.fail("Failed to launch elevated process: " + msg);
        }

        // Stream output to the log consumer
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    log.accept("  " + line);
                }
            } catch (IOException ignored) {}
        }, "elevated-delete-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished;
        try {
            finished = proc.waitFor(timeoutSecs, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            return Result.fail("Elevated deletion interrupted.");
        }

        if (!finished) {
            proc.destroyForcibly();
            return Result.fail("Elevated deletion timed out after " + timeoutSecs + "s.");
        }

        int exitCode = proc.exitValue();
        if (exitCode == 0) return Result.ok();

        // Interpret common non-zero exit codes
        String detail = output.toString().trim();
        if (detail.isEmpty()) detail = "exit code " + exitCode;

        // Windows: 1223 = UAC prompt cancelled by user
        if (exitCode == 1223) return Result.fail("Elevation was cancelled by the user.");
        // pkexec: 126 = not authorised, 127 = not found
        if (exitCode == 126)  return Result.fail("Permission denied by the authorization dialog.");
        if (exitCode == 127)  return Result.fail("Command not found: " + cmd.get(0));

        return Result.fail("Elevated deletion failed (" + detail + ").");
    }

    /** Checks whether a command is available on the PATH. */
    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(
                        System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")
                            ? List.of("where", command)
                            : List.of("which", command))
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
