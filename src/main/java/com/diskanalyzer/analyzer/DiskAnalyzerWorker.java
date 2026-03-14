package com.diskanalyzer.analyzer;

import com.diskanalyzer.model.FileNode;
import com.diskanalyzer.model.FolderNode;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class DiskAnalyzerWorker extends SwingWorker<DefaultMutableTreeNode, String> {

    private final File rootFile;
    private final Consumer<String> progressConsumer;
    private final Set<String>      skipPaths;          // canonical paths to skip
    private final AtomicBoolean    stopFlag  = new AtomicBoolean(false);
    private final AtomicLong       scannedDirs = new AtomicLong(0);
    // Tracks visited canonical paths to prevent symlink infinite loops
    private final Set<String> visitedPaths = new HashSet<>();

    public DiskAnalyzerWorker(File rootFile,
                               Consumer<String> progressConsumer,
                               Set<String> skipPaths) {
        this.rootFile         = rootFile;
        this.progressConsumer = progressConsumer;
        this.skipPaths        = skipPaths == null ? Collections.emptySet() : skipPaths;
    }

    @Override
    protected DefaultMutableTreeNode doInBackground() {
        FolderNode rootNode = new FolderNode(rootFile);
        DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode(rootNode);

        try { visitedPaths.add(rootFile.getCanonicalPath()); }
        catch (IOException ignored) {}

        publish("Scanning: " + rootFile.getAbsolutePath());
        long[] result = scanDirectory(rootFile, treeRoot);
        rootNode.setSize(result[0]);
        rootNode.setFileCount(result[1]);
        return treeRoot;
    }

    /**
     * Recursively scans {@code dir}, populating {@code parentNode} with:
     * <ol>
     *   <li>Sub-directory nodes (sorted by total size desc)</li>
     *   <li>Direct file leaf nodes (sorted by size desc)</li>
     * </ol>
     * Directories whose canonical path is in {@code skipPaths} are silently skipped.
     *
     * @return long[2]: [0] = total bytes, [1] = total file count
     */
    private long[] scanDirectory(File dir, DefaultMutableTreeNode parentNode) {
        if (stopFlag.get() || isCancelled()) return new long[]{0, 0};

        File[] entries = dir.listFiles();
        if (entries == null) return new long[]{0, 0};

        long totalBytes = 0;
        long totalFiles = 0;

        List<DefaultMutableTreeNode> dirNodes = new ArrayList<>();
        List<File>                  fileList  = new ArrayList<>();

        for (File entry : entries) {
            if (stopFlag.get() || isCancelled()) break;

            if (entry.isDirectory()) {
                String canonical;
                try {
                    canonical = entry.getCanonicalPath();
                    if (!visitedPaths.add(canonical)) continue; // symlink loop guard
                } catch (IOException ignored) { continue; }

                // ── Skip-scan check ───────────────────────────────
                if (skipPaths.contains(canonical) || skipPaths.contains(entry.getAbsolutePath())) {
                    publish("Skipping: " + entry.getAbsolutePath());
                    continue;
                }

                FolderNode childFolder = new FolderNode(entry);
                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(childFolder);

                long count = scannedDirs.incrementAndGet();
                if (count % 50 == 0) {
                    publish("Scanning [" + count + " dirs]: " + entry.getAbsolutePath());
                }

                long[] sub = scanDirectory(entry, childNode);
                childFolder.setSize(sub[0]);
                childFolder.setFileCount(sub[1]);

                totalBytes += sub[0];
                totalFiles += sub[1];
                dirNodes.add(childNode);

            } else if (entry.isFile()) {
                totalBytes += entry.length();
                totalFiles++;
                fileList.add(entry);
            }
        }

        // Sub-directory nodes — largest first
        dirNodes.sort((a, b) -> {
            FolderNode fa = (FolderNode) a.getUserObject();
            FolderNode fb = (FolderNode) b.getUserObject();
            return Long.compare(fb.getSize(), fa.getSize());
        });
        for (DefaultMutableTreeNode dn : dirNodes) parentNode.add(dn);

        // File leaf nodes — largest first
        fileList.sort(Comparator.comparingLong(File::length).reversed());
        for (File f : fileList) {
            parentNode.add(new DefaultMutableTreeNode(new FileNode(f)));
        }

        return new long[]{totalBytes, totalFiles};
    }

    @Override
    protected void process(List<String> chunks) {
        if (!chunks.isEmpty()) progressConsumer.accept(chunks.get(chunks.size() - 1));
    }

    public void requestStop() {
        stopFlag.set(true);
        cancel(true);
    }
}
