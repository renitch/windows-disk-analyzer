package com.diskanalyzer.model;

import java.io.File;

public class FolderNode implements FileSystemNode {
    private final File file;
    private volatile long size;
    private volatile long fileCount;

    public FolderNode(File file) {
        this.file = file;
        this.size = 0;
        this.fileCount = 0;
    }

    public File getFile() {
        return file;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getFileCount() {
        return fileCount;
    }

    public void setFileCount(long fileCount) {
        this.fileCount = fileCount;
    }

    @Override
    public boolean isDirectory() { return true; }

    public String getName() {
        String name = file.getName();
        // For root drives (e.g. "C:\"), getName() returns ""
        return name.isEmpty() ? file.getAbsolutePath() : name;
    }

    @Override
    public String toString() {
        return getName();
    }
}
