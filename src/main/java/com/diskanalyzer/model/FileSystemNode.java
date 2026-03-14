package com.diskanalyzer.model;

import java.io.File;

/**
 * Common interface for both directory nodes (FolderNode) and
 * file leaf nodes (FileNode) in the directory tree.
 */
public interface FileSystemNode {
    File   getFile();
    String getName();
    long   getSize();
    boolean isDirectory();
}
