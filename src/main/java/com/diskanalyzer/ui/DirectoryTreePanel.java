package com.diskanalyzer.ui;

import com.diskanalyzer.model.FileNode;
import com.diskanalyzer.model.FileSystemNode;
import com.diskanalyzer.model.FolderNode;
import com.diskanalyzer.util.FileSizeFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class DirectoryTreePanel extends JPanel {

    // ── Tree ────────────────────────────────────────────────────────
    private JTree             tree;
    private DefaultTreeModel  treeModel;
    private SizeTreeCellRenderer cellRenderer;

    // ── Data ────────────────────────────────────────────────────────
    /** Full scanned tree (dirs + files) — never displayed directly; used as source of truth. */
    private DefaultMutableTreeNode storedRoot;

    // ── State ────────────────────────────────────────────────────────
    private boolean sortBySize      = true;
    private boolean showFiles       = false;
    private boolean confirmDeletion = true;

    // ── Toolbar controls ─────────────────────────────────────────────
    private JToggleButton sortBySizeBtn;
    private JToggleButton sortByNameBtn;
    private JButton       expandAllBtn;
    private JButton       collapseAllBtn;
    private JCheckBox     showFilesChk;
    private JButton       deleteBtn;
    private JCheckBox     confirmDeleteChk;

    // ── Callback ─────────────────────────────────────────────────────
    /** Called after a successful deletion with a human-readable summary. */
    private Consumer<String> onDeleteCallback = msg -> {};

    public DirectoryTreePanel() {
        setLayout(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(6, 6, 6, 6));
        initComponents();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════════════════════════

    private void initComponents() {
        // ── Toolbar: row 1 — sort + expand ───────────────────────────
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));

        JLabel sortLabel = new JLabel("Sort:");
        sortLabel.setFont(sortLabel.getFont().deriveFont(Font.BOLD));

        ButtonGroup sortGroup = new ButtonGroup();
        sortBySizeBtn = new JToggleButton("By Size", true);
        sortByNameBtn = new JToggleButton("By Name", false);
        sortGroup.add(sortBySizeBtn);
        sortGroup.add(sortByNameBtn);
        sortBySizeBtn.addActionListener(e -> applySort(true));
        sortByNameBtn.addActionListener(e -> applySort(false));

        expandAllBtn   = new JButton("⊞ Expand");
        collapseAllBtn = new JButton("⊟ Collapse");
        expandAllBtn  .addActionListener(e -> expandAll());
        collapseAllBtn.addActionListener(e -> collapseAll());
        expandAllBtn  .setEnabled(false);
        collapseAllBtn.setEnabled(false);

        row1.add(sortLabel);
        row1.add(sortBySizeBtn);
        row1.add(sortByNameBtn);
        row1.add(separator());
        row1.add(expandAllBtn);
        row1.add(collapseAllBtn);

        // ── Toolbar: row 2 — files + delete ──────────────────────────
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));

        showFilesChk = new JCheckBox("Show Files", showFiles);
        showFilesChk.setToolTipText("Include individual files in the directory tree");
        showFilesChk.addActionListener(e -> {
            showFiles = showFilesChk.isSelected();
            rebuildDisplayModel();
        });

        deleteBtn = new JButton("🗑  Delete");
        deleteBtn.setToolTipText("Delete selected file or folder from disk  [Delete]");
        deleteBtn.setEnabled(false);
        deleteBtn.setForeground(new Color(255, 100, 80));
        deleteBtn.addActionListener(e -> deleteSelected());

        confirmDeleteChk = new JCheckBox("Confirm deletion", confirmDeletion);
        confirmDeleteChk.setToolTipText("Show a confirmation dialog before deleting");
        confirmDeleteChk.addActionListener(e -> confirmDeletion = confirmDeleteChk.isSelected());

        row2.add(showFilesChk);
        row2.add(separator());
        row2.add(deleteBtn);
        row2.add(confirmDeleteChk);

        // ── Toolbar wrapper ───────────────────────────────────────────
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.add(row1);
        toolbar.add(row2);

        // ── Tree ─────────────────────────────────────────────────────
        cellRenderer = new SizeTreeCellRenderer();
        tree = new JTree(new DefaultMutableTreeNode("No data — select a disk and press Analyze"));
        tree.setCellRenderer(cellRenderer);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        ToolTipManager.sharedInstance().registerComponent(tree);

        // Selection → enable/disable delete button
        tree.addTreeSelectionListener(e -> updateDeleteButtonState());

        // Delete key binding
        tree.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteNode");
        tree.getActionMap().put("deleteNode",
                new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (deleteBtn.isEnabled()) deleteSelected();
                    }
                });

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor"), 1));

        add(toolbar,    BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════

    public void setTreeData(DefaultMutableTreeNode root) {
        storedRoot = root;

        if (root.getUserObject() instanceof FolderNode folder) {
            cellRenderer.setReferenceSize(folder.getSize());
        }

        rebuildDisplayModel();
        expandAllBtn  .setEnabled(true);
        collapseAllBtn.setEnabled(true);
    }

    public void clear() {
        storedRoot = null;
        treeModel  = null;
        tree.setModel(new DefaultTreeModel(
                new DefaultMutableTreeNode("No data — select a disk and press Analyze")));
        expandAllBtn  .setEnabled(false);
        collapseAllBtn.setEnabled(false);
        deleteBtn     .setEnabled(false);
    }

    public JTree getTree() { return tree; }

    /** Register a callback that fires after each successful deletion. */
    public void setOnDeleteCallback(Consumer<String> cb) { this.onDeleteCallback = cb; }

    // ═══════════════════════════════════════════════════════════════
    //  Display-model rebuild  (show/hide files + sort)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Rebuilds the Swing tree model from {@code storedRoot},
     * optionally including file leaves, then applies the current sort.
     * Expanded paths are restored afterwards.
     */
    private void rebuildDisplayModel() {
        if (storedRoot == null) return;

        List<TreePath> expanded = getExpandedPaths();

        DefaultMutableTreeNode displayRoot = copyNode(storedRoot, showFiles);
        sortNodes(displayRoot, sortBySize);

        treeModel = new DefaultTreeModel(displayRoot);
        tree.setModel(treeModel);

        // Expand root row
        tree.expandRow(0);

        // Restore previously expanded paths (best-effort by path string)
        for (TreePath tp : expanded) tree.expandPath(tp);

        updateDeleteButtonState();
    }

    /**
     * Deep-copies a sub-tree from {@code source}.
     * File leaf nodes are included only if {@code includeFiles} is true.
     */
    private DefaultMutableTreeNode copyNode(DefaultMutableTreeNode source, boolean includeFiles) {
        Object obj = source.getUserObject();
        DefaultMutableTreeNode copy = new DefaultMutableTreeNode(obj);

        for (int i = 0; i < source.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) source.getChildAt(i);
            Object childObj = child.getUserObject();

            if (childObj instanceof FileNode) {
                if (includeFiles) copy.add(new DefaultMutableTreeNode(childObj)); // leaf
            } else {
                copy.add(copyNode(child, includeFiles)); // recurse into dirs
            }
        }
        return copy;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Sort
    // ═══════════════════════════════════════════════════════════════

    private void applySort(boolean bySize) {
        this.sortBySize = bySize;
        if (storedRoot == null) return;

        List<TreePath> expanded = getExpandedPaths();
        sortNodes((DefaultMutableTreeNode) treeModel.getRoot(), bySize);
        treeModel.reload();
        for (TreePath tp : expanded) tree.expandPath(tp);
    }

    /**
     * Sorts children of {@code node} in-place.
     * <ul>
     *   <li>By size: purely descending (dirs and files interleaved).</li>
     *   <li>By name: directories first (alphabetical), then files (alphabetical).</li>
     * </ul>
     */
    private void sortNodes(DefaultMutableTreeNode node, boolean bySize) {
        int count = node.getChildCount();
        if (count == 0) return;

        List<DefaultMutableTreeNode> children = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            children.add((DefaultMutableTreeNode) node.getChildAt(i));
        }

        Comparator<DefaultMutableTreeNode> cmp;
        if (bySize) {
            cmp = Comparator.comparingLong((DefaultMutableTreeNode n) -> nodeSize(n)).reversed();
        } else {
            // Dirs before files; within each group, alphabetical
            cmp = Comparator
                    .comparingInt((DefaultMutableTreeNode n) -> isFileLeaf(n) ? 1 : 0)
                    .thenComparing(n -> nodeName(n), String.CASE_INSENSITIVE_ORDER);
        }

        children.sort(cmp);
        node.removeAllChildren();
        for (DefaultMutableTreeNode child : children) {
            node.add(child);
            if (!isFileLeaf(child)) sortNodes(child, bySize); // recurse into dirs only
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Delete
    // ═══════════════════════════════════════════════════════════════

    private void deleteSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode displayNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = displayNode.getUserObject();

        // Guard: cannot delete the root
        if (displayNode.getParent() == null) {
            JOptionPane.showMessageDialog(this,
                    "Cannot delete the root of the drive.", "Delete", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!(userObj instanceof FileSystemNode fsNode)) return;

        // ── Optional confirmation ─────────────────────────────────
        if (confirmDeletion) {
            String type    = fsNode.isDirectory() ? "folder" : "file";
            String sizeStr = FileSizeFormatter.format(fsNode.getSize());
            int choice = JOptionPane.showConfirmDialog(this,
                    "<html>Permanently delete this " + type + " from disk?<br><br>"
                    + "<b>" + fsNode.getFile().getAbsolutePath() + "</b><br>"
                    + "<i>" + sizeStr + (fsNode.isDirectory()
                            ? "  (" + ((FolderNode) fsNode).getFileCount() + " files)" : "")
                    + "</i></html>",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        long deletedSize      = fsNode.getSize();
        long deletedFileCount = fsNode.isDirectory()
                ? ((FolderNode) fsNode).getFileCount() : 1;

        // ── Delete from filesystem ────────────────────────────────
        try {
            deleteFromDisk(fsNode.getFile().toPath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Delete failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // ── Update storedRoot ancestor sizes ──────────────────────
        DefaultMutableTreeNode storedNode = findInTree(storedRoot, userObj);
        if (storedNode != null) {
            adjustAncestorSizes(storedNode, deletedSize, deletedFileCount);
            storedNode.removeFromParent();
        }

        // ── Rebuild display ───────────────────────────────────────
        rebuildDisplayModel();

        String msg = "Deleted: " + fsNode.getFile().getAbsolutePath()
                + "  (" + FileSizeFormatter.format(deletedSize) + ")";
        onDeleteCallback.accept(msg);
    }

    /** Deletes a file or recursively deletes a directory. */
    private void deleteFromDisk(Path target) throws IOException {
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        } else {
            Files.delete(target);
        }
    }

    /**
     * Walks up the ancestor chain of {@code node} in the storedRoot tree,
     * subtracting {@code sizeToRemove} and {@code fileCountToRemove} from each
     * ancestor {@link FolderNode}.
     */
    private void adjustAncestorSizes(DefaultMutableTreeNode node,
                                     long sizeToRemove, long fileCountToRemove) {
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        while (parent != null) {
            if (parent.getUserObject() instanceof FolderNode folder) {
                folder.setSize(Math.max(0, folder.getSize() - sizeToRemove));
                folder.setFileCount(Math.max(0, folder.getFileCount() - fileCountToRemove));
            }
            parent = (DefaultMutableTreeNode) parent.getParent();
        }
    }

    /**
     * Finds the first node in {@code root}'s subtree whose user-object is
     * {@code == target} (identity comparison, since we share references).
     */
    private DefaultMutableTreeNode findInTree(DefaultMutableTreeNode root, Object target) {
        if (root.getUserObject() == target) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode found =
                    findInTree((DefaultMutableTreeNode) root.getChildAt(i), target);
            if (found != null) return found;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private void updateDeleteButtonState() {
        if (storedRoot == null) { deleteBtn.setEnabled(false); return; }
        TreePath sel = tree.getSelectionPath();
        if (sel == null) { deleteBtn.setEnabled(false); return; }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) sel.getLastPathComponent();
        // Disable for the root drive node
        deleteBtn.setEnabled(node.getParent() != null
                && node.getUserObject() instanceof FileSystemNode);
    }

    private long nodeSize(DefaultMutableTreeNode n) {
        Object obj = n.getUserObject();
        return (obj instanceof FileSystemNode fsn) ? fsn.getSize() : 0L;
    }

    private String nodeName(DefaultMutableTreeNode n) {
        Object obj = n.getUserObject();
        return (obj instanceof FileSystemNode fsn) ? fsn.getName() : obj.toString();
    }

    private boolean isFileLeaf(DefaultMutableTreeNode n) {
        return n.getUserObject() instanceof FileNode;
    }

    private List<TreePath> getExpandedPaths() {
        List<TreePath> paths = new ArrayList<>();
        Object root = tree.getModel().getRoot();
        if (root == null) return paths;
        Enumeration<TreePath> en = tree.getExpandedDescendants(new TreePath(root));
        if (en != null) while (en.hasMoreElements()) paths.add(en.nextElement());
        return paths;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private void collapseAll() {
        for (int i = tree.getRowCount() - 1; i > 0; i--) tree.collapseRow(i);
    }

    private static JSeparator separator() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1, 20));
        return s;
    }
}

