package com.diskanalyzer.ui;

import com.diskanalyzer.model.FileNode;
import com.diskanalyzer.model.FolderNode;
import com.diskanalyzer.util.FileSizeFormatter;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

public class SizeTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final Color DIR_COLOR_LARGE  = new Color(255, 120,  80);
    private static final Color DIR_COLOR_MEDIUM = new Color(255, 200,  80);
    private static final Color DIR_COLOR_SMALL  = new Color(120, 200, 120);
    private static final Color FILE_COLOR       = new Color(160, 185, 215);

    // Reference size set from root for relative colouring (1 GB default)
    private long referenceSize = 1_073_741_824L;

    public void setReferenceSize(long referenceSize) {
        this.referenceSize = referenceSize > 0 ? referenceSize : 1;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        if (!(value instanceof DefaultMutableTreeNode node)) return this;

        Object userObj = node.getUserObject();

        if (userObj instanceof FolderNode folder) {
            long size = folder.getSize();
            setText(folder.getName() + "   [" + FileSizeFormatter.format(size) + "]");
            if (!selected) {
                double ratio = (double) size / referenceSize;
                if      (ratio >= 0.10) setForeground(DIR_COLOR_LARGE);
                else if (ratio >= 0.02) setForeground(DIR_COLOR_MEDIUM);
                else                   setForeground(DIR_COLOR_SMALL);
            }
            setIcon(expanded ? getOpenIcon() : getClosedIcon());
            setToolTipText(folder.getFile().getAbsolutePath()
                    + "  —  " + FileSizeFormatter.format(size)
                    + "  (" + folder.getFileCount() + " files)");

        } else if (userObj instanceof FileNode file) {
            setText(file.getName() + "   [" + FileSizeFormatter.format(file.getSize()) + "]");
            if (!selected) setForeground(FILE_COLOR);
            setIcon(getLeafIcon());
            setToolTipText(file.getFile().getAbsolutePath()
                    + "  —  " + FileSizeFormatter.format(file.getSize()));
        }

        return this;
    }
}

