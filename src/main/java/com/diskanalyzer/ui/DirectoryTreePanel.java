package com.diskanalyzer.ui;

import com.diskanalyzer.AppSettings;
import com.diskanalyzer.model.FileNode;
import com.diskanalyzer.model.FileSystemNode;
import com.diskanalyzer.model.FolderNode;
import com.diskanalyzer.util.ElevatedDeleteHelper;
import com.diskanalyzer.util.FileSizeFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class DirectoryTreePanel extends JPanel {

    // ── Tree ─────────────────────────────────────────────────────────
    private JTree                    tree;
    private DefaultTreeModel         treeModel;
    private SizeTreeCellRenderer     cellRenderer;

    // ── Data ─────────────────────────────────────────────────────────
    /** Full scanned tree (dirs + files) — never rendered directly; source of truth. */
    private DefaultMutableTreeNode storedRoot;

    /**
     * Two pre-built, pre-sorted display models built once from storedRoot.
     * Toggling show/hide files just swaps which model is active — zero deep-copy on the EDT.
     */
    private DefaultTreeModel modelWithoutFiles;   // dirs only
    private DefaultTreeModel modelWithFiles;      // dirs + file leaves

    // ── State ────────────────────────────────────────────────────────
    private boolean sortBySize = true;
    private boolean showFiles  = false;
    private boolean deletionInProgress  = false;
    private boolean modelsReady         = false;   // true once background build finishes

    // ── Toolbar controls ─────────────────────────────────────────────
    private JToggleButton sortBySizeBtn;
    private JToggleButton sortByNameBtn;
    private JButton       expandAllBtn;
    private JButton       collapseAllBtn;
    private JCheckBox     showFilesChk;
    private JButton       deleteBtn;

    // ── Callbacks ────────────────────────────────────────────────────
    /** Called with status-bar messages (progress, completion, errors). */
    private Consumer<String>  onStatusCallback  = msg -> {};
    /** Called with true when deletion starts, false when it finishes. */
    private Consumer<Boolean> onBusyCallback    = busy -> {};
    /** Called with the final result message after a successful deletion. */
    private Consumer<String>  onDeleteCallback  = msg -> {};

    public DirectoryTreePanel() {
        setLayout(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(6, 6, 6, 6));
        initComponents();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════════════════════════

    private void initComponents() {
        // ── Row 1: sort + expand ─────────────────────────────────────
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));

        JLabel sortLabel = new JLabel("Sort:");
        sortLabel.setFont(sortLabel.getFont().deriveFont(Font.BOLD));

        ButtonGroup sortGroup = new ButtonGroup();
        sortBySizeBtn = new JToggleButton("By Size", true);
        sortByNameBtn = new JToggleButton("By Name", false);
        sortGroup.add(sortBySizeBtn);
        sortGroup.add(sortByNameBtn);
        sortBySizeBtn.setEnabled(false);
        sortByNameBtn.setEnabled(false);
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
        row1.add(sep());
        row1.add(expandAllBtn);
        row1.add(collapseAllBtn);

        // ── Row 2: file visibility + delete ──────────────────────────
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));

        showFilesChk = new JCheckBox("Show Files", showFiles);
        showFilesChk.setToolTipText("Include individual files in the directory tree");
        showFilesChk.setEnabled(false);   // enabled once models are ready
        showFilesChk.addActionListener(e -> {
            showFiles = showFilesChk.isSelected();
            activateModel(showFiles);  // O(1) model swap — no deep copy, no freeze
        });

        deleteBtn = new JButton("🗑  Delete");
        deleteBtn.setToolTipText("Delete selected file or folder from disk  [Delete]");
        deleteBtn.setEnabled(false);
        deleteBtn.setForeground(new Color(255, 100, 80));
        deleteBtn.addActionListener(e -> deleteSelected());

        row2.add(showFilesChk);
        row2.add(sep());
        row2.add(deleteBtn);

        // ── Toolbar wrapper ───────────────────────────────────────────
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.add(row1);
        toolbar.add(row2);

        // ── Tree ─────────────────────────────────────────────────────
        cellRenderer = new SizeTreeCellRenderer();
        tree = new JTree(new DefaultMutableTreeNode(
                "No data — select a disk and press Analyze"));
        tree.setCellRenderer(cellRenderer);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        ToolTipManager.sharedInstance().registerComponent(tree);

        tree.addTreeSelectionListener(e -> updateDeleteButtonState());

        // Delete key binding
        tree.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteNode");
        tree.getActionMap().put("deleteNode", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (deleteBtn.isEnabled()) deleteSelected();
            }
        });

        // Right-click context menu
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
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
        storedRoot    = root;
        modelsReady   = false;
        modelWithFiles    = null;
        modelWithoutFiles = null;

        if (root.getUserObject() instanceof FolderNode f) {
            cellRenderer.setReferenceSize(f.getSize());
        }

        // Temporarily show root-only placeholder while building in background
        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Building tree…"));
        tree.setModel(treeModel);
        showFilesChk.setEnabled(false);
        sortBySizeBtn.setEnabled(false);
        sortByNameBtn.setEnabled(false);
        expandAllBtn .setEnabled(false);
        collapseAllBtn.setEnabled(false);
        onStatusCallback.accept("Building display model…");

        // Build both pre-sorted display models off the EDT
        SwingWorker<Void, Void> builder = new SwingWorker<>() {
            private DefaultTreeModel builtWithFiles;
            private DefaultTreeModel builtWithoutFiles;

            @Override
            protected Void doInBackground() {
                DefaultMutableTreeNode withFiles    = copyNode(root, true);
                DefaultMutableTreeNode withoutFiles = copyNode(root, false);
                sortNodes(withFiles,    sortBySize);
                sortNodes(withoutFiles, sortBySize);
                builtWithFiles    = new DefaultTreeModel(withFiles);
                builtWithoutFiles = new DefaultTreeModel(withoutFiles);
                return null;
            }

            @Override
            protected void done() {
                modelWithFiles    = builtWithFiles;
                modelWithoutFiles = builtWithoutFiles;
                modelsReady = true;

                showFilesChk  .setEnabled(true);
                sortBySizeBtn .setEnabled(true);
                sortByNameBtn .setEnabled(true);
                expandAllBtn  .setEnabled(true);
                collapseAllBtn.setEnabled(true);

                activateModel(showFiles);
                onStatusCallback.accept("Ready.");
            }
        };
        builder.execute();
    }

    public void clear() {
        storedRoot        = null;
        treeModel         = null;
        modelWithFiles    = null;
        modelWithoutFiles = null;
        modelsReady       = false;
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(
                "No data — select a disk and press Analyze")));
        expandAllBtn  .setEnabled(false);
        collapseAllBtn.setEnabled(false);
        deleteBtn     .setEnabled(false);
        showFilesChk  .setEnabled(false);
        sortBySizeBtn .setEnabled(false);
        sortByNameBtn .setEnabled(false);
    }

    public JTree getTree() { return tree; }

    public void setOnStatusCallback(Consumer<String>  cb) { onStatusCallback  = cb; }
    public void setOnBusyCallback  (Consumer<Boolean> cb) { onBusyCallback    = cb; }
    public void setOnDeleteCallback(Consumer<String>  cb) { onDeleteCallback  = cb; }

    // ═══════════════════════════════════════════════════════════════
    //  Display-model management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Instantly swaps to the correct pre-built model. O(1) on the EDT.
     * Restored expanded paths are matched by user-object identity.
     */
    private void activateModel(boolean withFiles) {
        if (!modelsReady) return;

        List<TreePath> expanded = getExpandedPaths();

        treeModel = withFiles ? modelWithFiles : modelWithoutFiles;
        tree.setModel(treeModel);
        tree.expandRow(0);

        // Best-effort restore of expanded paths
        for (TreePath tp : expanded) tree.expandPath(tp);
        updateDeleteButtonState();
    }

    /**
     * Rebuilds both pre-built models in the background (called after deletion
     * to keep them in sync with storedRoot). Re-applies the active view on completion.
     */
    private void rebuildDisplayModel() {
        if (storedRoot == null) return;
        modelsReady = false;

        List<TreePath> expanded = getExpandedPaths();

        SwingWorker<Void, Void> builder = new SwingWorker<>() {
            private DefaultTreeModel builtWithFiles;
            private DefaultTreeModel builtWithoutFiles;

            @Override
            protected Void doInBackground() {
                DefaultMutableTreeNode withFiles    = copyNode(storedRoot, true);
                DefaultMutableTreeNode withoutFiles = copyNode(storedRoot, false);
                sortNodes(withFiles,    sortBySize);
                sortNodes(withoutFiles, sortBySize);
                builtWithFiles    = new DefaultTreeModel(withFiles);
                builtWithoutFiles = new DefaultTreeModel(withoutFiles);
                return null;
            }

            @Override
            protected void done() {
                modelWithFiles    = builtWithFiles;
                modelWithoutFiles = builtWithoutFiles;
                modelsReady = true;

                treeModel = showFiles ? modelWithFiles : modelWithoutFiles;
                tree.setModel(treeModel);
                tree.expandRow(0);
                for (TreePath tp : expanded) tree.expandPath(tp);
                updateDeleteButtonState();
            }
        };
        builder.execute();
    }

    /** Deep-copies a sub-tree node, including or excluding FileNode leaves. */
    private DefaultMutableTreeNode copyNode(DefaultMutableTreeNode src, boolean includeFiles) {
        DefaultMutableTreeNode copy = new DefaultMutableTreeNode(src.getUserObject());
        for (int i = 0; i < src.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) src.getChildAt(i);
            if (child.getUserObject() instanceof FileNode) {
                if (includeFiles) copy.add(new DefaultMutableTreeNode(child.getUserObject()));
            } else {
                copy.add(copyNode(child, includeFiles));
            }
        }
        return copy;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Sort
    // ═══════════════════════════════════════════════════════════════

    private void applySort(boolean bySize) {
        this.sortBySize = bySize;
        if (storedRoot == null || !modelsReady) return;

        List<TreePath> expanded = getExpandedPaths();

        // Re-sort both pre-built models in-place (fast, no deep copy)
        sortNodes((DefaultMutableTreeNode) modelWithFiles   .getRoot(), bySize);
        sortNodes((DefaultMutableTreeNode) modelWithoutFiles.getRoot(), bySize);
        modelWithFiles   .reload();
        modelWithoutFiles.reload();

        // Reattach the currently active one
        treeModel = showFiles ? modelWithFiles : modelWithoutFiles;
        tree.setModel(treeModel);
        tree.expandRow(0);
        for (TreePath tp : expanded) tree.expandPath(tp);
    }

    private void sortNodes(DefaultMutableTreeNode node, boolean bySize) {
        int count = node.getChildCount();
        if (count == 0) return;

        List<DefaultMutableTreeNode> children = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            children.add((DefaultMutableTreeNode) node.getChildAt(i));

        Comparator<DefaultMutableTreeNode> cmp = bySize
            ? Comparator.comparingLong(this::nodeSize).reversed()
            : Comparator.comparingInt((DefaultMutableTreeNode n) -> isFileLeaf(n) ? 1 : 0)
                        .thenComparing(this::nodeName, String.CASE_INSENSITIVE_ORDER);

        children.sort(cmp);
        node.removeAllChildren();
        for (DefaultMutableTreeNode child : children) {
            node.add(child);
            if (!isFileLeaf(child)) sortNodes(child, bySize);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Context menu (right-click)
    // ═══════════════════════════════════════════════════════════════

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;

        // Select the row under cursor before showing menu
        TreePath pathUnderCursor = tree.getPathForLocation(e.getX(), e.getY());
        if (pathUnderCursor == null) return;
        tree.setSelectionPath(pathUnderCursor);

        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) pathUnderCursor.getLastPathComponent();
        Object userObj = node.getUserObject();

        JPopupMenu menu = new JPopupMenu();

        // ── Skip Scan (directories only, non-root) ───────────────
        if (userObj instanceof FolderNode folder && node.getParent() != null) {
            String absPath = folder.getFile().getAbsolutePath();
            boolean alreadySkipped = AppSettings.get().isSkipped(absPath);

            JMenuItem skipItem = new JMenuItem(
                    alreadySkipped ? "✓ Already in Skip List" : "⊘ Skip Scan");
            skipItem.setEnabled(!alreadySkipped);
            skipItem.addActionListener(ev -> {
                AppSettings.get().addSkipPath(absPath);
                onStatusCallback.accept(
                        "Skip-scan added: " + absPath + "  (will take effect on next scan)");
            });
            menu.add(skipItem);
            menu.addSeparator();
        }

        // ── Delete ───────────────────────────────────────────────
        if (userObj instanceof FileSystemNode && node.getParent() != null && !deletionInProgress) {
            JMenuItem deleteItem = new JMenuItem("🗑  Delete");
            deleteItem.setForeground(new Color(255, 100, 80));
            deleteItem.addActionListener(ev -> deleteSelected());
            menu.add(deleteItem);
        }

        if (menu.getComponentCount() > 0) menu.show(tree, e.getX(), e.getY());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Delete — runs on background SwingWorker thread
    // ═══════════════════════════════════════════════════════════════

    private void deleteSelected() {
        if (deletionInProgress) return;

        TreePath selPath = tree.getSelectionPath();
        if (selPath == null) return;

        DefaultMutableTreeNode displayNode =
                (DefaultMutableTreeNode) selPath.getLastPathComponent();

        if (displayNode.getParent() == null) {
            JOptionPane.showMessageDialog(this,
                    "Cannot delete the root of the drive.",
                    "Delete", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!(displayNode.getUserObject() instanceof FileSystemNode fsNode)) return;

        // ── Confirm (on EDT, before starting background work) ─────
        if (AppSettings.get().isConfirmDeletion()) {
            String type    = fsNode.isDirectory() ? "folder" : "file";
            String sizeStr = FileSizeFormatter.format(fsNode.getSize());
            String detail  = fsNode.isDirectory()
                    ? "  (" + ((FolderNode) fsNode).getFileCount() + " files)" : "";
            int choice = JOptionPane.showConfirmDialog(this,
                    "<html>Permanently delete this " + type + " from disk?<br><br>"
                    + "<b>" + fsNode.getFile().getAbsolutePath() + "</b><br>"
                    + "<i>" + sizeStr + detail + "</i></html>",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        // ── Snapshot what we're about to delete ───────────────────
        final long        deletedSize  = fsNode.getSize();
        final long        deletedFiles = fsNode.isDirectory()
                ? ((FolderNode) fsNode).getFileCount() : 1L;
        final Path        targetPath   = fsNode.getFile().toPath();
        final String      targetLabel  = fsNode.getFile().getAbsolutePath();
        final Object      userObj      = fsNode;  // identity ref into storedRoot

        // ── Disable UI while deleting ─────────────────────────────
        setDeletionBusy(true);
        onStatusCallback.accept("Deleting: " + targetLabel + " …");

        // ── Background worker ─────────────────────────────────────
        SwingWorker<Void, String> worker = new SwingWorker<>() {

            private final AtomicLong deletedCount = new AtomicLong(0);
            private       String     errorMsg     = null;

            @Override
            protected Void doInBackground() {
                try {
                    if (Files.isDirectory(targetPath)) {
                        try (var walk = Files.walk(targetPath)) {
                            walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.delete(p);
                                        long n = deletedCount.incrementAndGet();
                                        if (n % 100 == 0) {
                                            publish("Deleting: " + n + " items removed …");
                                        }
                                    } catch (IOException ex) {
                                        throw new UncheckedIOException(ex);
                                    }
                                });
                        } catch (UncheckedIOException ex) {
                            throw ex.getCause();
                        }
                    } else {
                        Files.delete(targetPath);
                        deletedCount.set(1);
                    }
                } catch (IOException ex) {
                    errorMsg = ex.getMessage();
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) onStatusCallback.accept(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                setDeletionBusy(false);

                if (errorMsg != null) {
                    onStatusCallback.accept("Delete failed: " + errorMsg);
                    offerElevatedDelete(targetPath, targetLabel,
                                        deletedSize, deletedFiles, userObj, errorMsg);
                    return;
                }

                finaliseDeletedNode(userObj, deletedSize, deletedFiles,
                                    deletedCount.get(), targetLabel);
            }
        };

        worker.execute();
    }

    /** Enables/disables controls that must not be used during deletion. */
    private void setDeletionBusy(boolean busy) {
        deletionInProgress = busy;
        deleteBtn.setEnabled(!busy);
        // Sort/filter controls also need models to be ready
        boolean canInteract = !busy && modelsReady;
        sortBySizeBtn.setEnabled(canInteract);
        sortByNameBtn.setEnabled(canInteract);
        showFilesChk .setEnabled(canInteract);
        onBusyCallback.accept(busy);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Elevated-privilege retry
    // ═══════════════════════════════════════════════════════════════

    /**
     * Called on the EDT after a normal deletion fails.
     * Shows a dialog asking whether to retry with admin/root privileges.
     * If confirmed, launches a new background SwingWorker that uses
     * {@link ElevatedDeleteHelper}.
     */
    private void offerElevatedDelete(Path targetPath, String targetLabel,
                                     long deletedSize, long deletedFiles,
                                     Object userObj, String originalError) {
        String osNote = switch (ElevatedDeleteHelper.detectOS()) {
            case WINDOWS -> "A UAC (User Account Control) prompt will appear.";
            case MAC     -> "You will be prompted for your administrator password.";
            case LINUX   -> "A graphical privilege dialog (pkexec / gksudo) will appear.";
            default      -> "The OS will request elevated permissions.";
        };

        int choice = JOptionPane.showConfirmDialog(
                DirectoryTreePanel.this,
                "<html>"
                + "<b>Deletion failed:</b><br>"
                + "<i>" + escapeHtml(originalError) + "</i>"
                + "<br><br>"
                + "This may be a permissions issue.<br>"
                + "Would you like to retry with <b>administrator privileges</b>?<br><br>"
                + "<small>" + osNote + "</small>"
                + "</html>",
                "Retry with Elevated Privileges",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) return;

        runElevatedDelete(targetPath, targetLabel, deletedSize, deletedFiles, userObj);
    }

    /**
     * Runs {@link ElevatedDeleteHelper#delete} on a background thread,
     * streaming status updates, then finalises the tree on success.
     */
    private void runElevatedDelete(Path targetPath, String targetLabel,
                                   long deletedSize, long deletedFiles, Object userObj) {
        setDeletionBusy(true);
        onStatusCallback.accept("Requesting elevated privileges …");

        SwingWorker<ElevatedDeleteHelper.Result, String> elevWorker = new SwingWorker<>() {

            @Override
            protected ElevatedDeleteHelper.Result doInBackground() {
                return ElevatedDeleteHelper.delete(targetPath,
                        msg -> publish(msg));
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty())
                    onStatusCallback.accept(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                setDeletionBusy(false);
                ElevatedDeleteHelper.Result result;
                try {
                    result = get();
                } catch (Exception ex) {
                    onStatusCallback.accept("Elevated delete failed: " + ex.getMessage());
                    JOptionPane.showMessageDialog(DirectoryTreePanel.this,
                            "Elevated deletion encountered an unexpected error:\n"
                            + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                if (!result.success()) {
                    onStatusCallback.accept("Elevated delete failed: " + result.errorMessage());
                    JOptionPane.showMessageDialog(DirectoryTreePanel.this,
                            "<html><b>Elevated deletion failed:</b><br>"
                            + escapeHtml(result.errorMessage()) + "</html>",
                            "Delete Failed", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // Success — update tree exactly as with a regular delete
                finaliseDeletedNode(userObj, deletedSize, deletedFiles, -1L, targetLabel);
            }
        };

        elevWorker.execute();
    }

    /**
     * Updates the in-memory tree model after a successful deletion
     * (regular or elevated) and fires status callbacks.
     *
     * @param itemCount negative means "unknown" (elevated process gives no file count)
     */
    private void finaliseDeletedNode(Object userObj, long deletedSize,
                                     long deletedFiles, long itemCount, String targetLabel) {
        DefaultMutableTreeNode storedNode = findInTree(storedRoot, userObj);
        if (storedNode != null) {
            adjustAncestorSizes(storedNode, deletedSize, deletedFiles);
            storedNode.removeFromParent();
        }

        rebuildDisplayModel();

        String countPart = itemCount >= 0
                ? itemCount + " items  —  "
                : "";
        String msg = "Deleted " + countPart
                + FileSizeFormatter.format(deletedSize) + " freed  ("
                + targetLabel + ")";
        onStatusCallback.accept(msg);
        onDeleteCallback.accept(msg);
    }

    /** Minimal HTML escaping for use inside JOptionPane HTML strings. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tree helpers
    // ═══════════════════════════════════════════════════════════════

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

    private DefaultMutableTreeNode findInTree(DefaultMutableTreeNode root, Object target) {
        if (root.getUserObject() == target) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode found =
                    findInTree((DefaultMutableTreeNode) root.getChildAt(i), target);
            if (found != null) return found;
        }
        return null;
    }

    private void updateDeleteButtonState() {
        if (storedRoot == null || deletionInProgress) { deleteBtn.setEnabled(false); return; }
        TreePath sel = tree.getSelectionPath();
        if (sel == null) { deleteBtn.setEnabled(false); return; }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) sel.getLastPathComponent();
        deleteBtn.setEnabled(node.getParent() != null
                && node.getUserObject() instanceof FileSystemNode);
    }

    private long nodeSize(DefaultMutableTreeNode n) {
        return (n.getUserObject() instanceof FileSystemNode fsn) ? fsn.getSize() : 0L;
    }

    private String nodeName(DefaultMutableTreeNode n) {
        return (n.getUserObject() instanceof FileSystemNode fsn)
                ? fsn.getName() : n.getUserObject().toString();
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

    private static JSeparator sep() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1, 20));
        return s;
    }
}
