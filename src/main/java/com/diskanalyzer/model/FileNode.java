package com.diskanalyzer.model;

import java.io.File;

/** Represents a single (non-directory) file shown as a leaf in the tree. */
public class FileNode implements FileSystemNode {

    private final File file;

    public FileNode(File file) {
        this.file = file;
    }

    @Override public File    getFile()        { return file; }
    @Override public String  getName()        { return file.getName(); }
    @Override public long    getSize()        { return file.length(); }
    @Override public boolean isDirectory()    { return false; }

    @Override public String toString() { return getName(); }
}
