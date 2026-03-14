package com.diskanalyzer;

import com.diskanalyzer.analyzer.DiskAnalyzerWorker;
import com.diskanalyzer.model.FileNode;
import com.diskanalyzer.model.FolderNode;
import com.diskanalyzer.ui.DirectoryTreePanel;
import com.diskanalyzer.ui.PieChartPanel;
import com.diskanalyzer.ui.SettingsDialog;
import com.diskanalyzer.util.FileSizeFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.concurrent.ExecutionException;

public class MainFrame extends JFrame {

    // ── Top bar ───────────────────────────────────────────────────
    private JComboBox<File> diskComboBox;
    private JButton analyzeBtn;
    private JButton cancelBtn;
    private JLabel diskInfoLabel;

    // ── Centre ────────────────────────────────────────────────────
    private DirectoryTreePanel treePanel;
    private PieChartPanel chartPanel;
    private JSplitPane splitPane;

    // ── Status bar ────────────────────────────────────────────────
    private JLabel statusLabel;
    private JProgressBar progressBar;

    // ── Worker ───────────────────────────────────────────────────
    private DiskAnalyzerWorker currentWorker;

    public MainFrame() {
        setTitle("Disk Analyzer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1300, 820);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        setIconImage(buildIcon());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });

        buildUI();
        refreshDiskInfo();
    }

    // ── UI construction ───────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCentre(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));

        // Left: disk selector
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(new JLabel("Drive:"));

        diskComboBox = new JComboBox<>(File.listRoots());
        diskComboBox.setRenderer(new DiskComboRenderer());
        diskComboBox.setPreferredSize(new Dimension(340, 30));
        diskComboBox.addActionListener(e -> refreshDiskInfo());
        left.add(diskComboBox);

        analyzeBtn = new JButton("▶  Analyze");
        analyzeBtn.setFont(analyzeBtn.getFont().deriveFont(Font.BOLD, 13f));
        analyzeBtn.setPreferredSize(new Dimension(120, 30));
        analyzeBtn.addActionListener(e -> startAnalysis());
        left.add(analyzeBtn);

        cancelBtn = new JButton("✕  Cancel");
        cancelBtn.setPreferredSize(new Dimension(110, 30));
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> cancelAnalysis());
        left.add(cancelBtn);

        JButton settingsBtn = new JButton("⚙  Settings");
        settingsBtn.setPreferredSize(new Dimension(115, 30));
        settingsBtn.addActionListener(e ->
                new SettingsDialog(this).setVisible(true));
        left.add(settingsBtn);

        // Right: disk usage bar
        diskInfoLabel = new JLabel();
        diskInfoLabel.setFont(diskInfoLabel.getFont().deriveFont(12f));
        diskInfoLabel.setBorder(new EmptyBorder(0, 0, 0, 4));

        panel.add(left, BorderLayout.WEST);
        panel.add(diskInfoLabel, BorderLayout.EAST);

        return panel;
    }

    private JSplitPane buildCentre() {
        treePanel  = new DirectoryTreePanel();
        chartPanel = new PieChartPanel();

        // Status bar messages from deletion progress
        treePanel.setOnStatusCallback(msg ->
                SwingUtilities.invokeLater(() -> statusLabel.setText(msg)));

        // Show/hide progress bar during deletion
        treePanel.setOnBusyCallback(busy -> SwingUtilities.invokeLater(() -> {
            progressBar.setVisible(busy);
            progressBar.setIndeterminate(busy);
            analyzeBtn.setEnabled(!busy);
        }));

        // Legacy callback (no-op, status handles it)
        treePanel.setOnDeleteCallback(msg -> {});

        // Selection → status bar info
        treePanel.getTree().addTreeSelectionListener(e -> {
            TreePath path = e.getNewLeadSelectionPath();
            if (path == null) return;
            if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) return;

            Object obj = node.getUserObject();
            if (obj instanceof FolderNode folder) {
                statusLabel.setText("Selected: " + folder.getFile().getAbsolutePath()
                        + "  —  " + FileSizeFormatter.formatWithCount(folder.getSize(), folder.getFileCount()));
            } else if (obj instanceof FileNode file) {
                statusLabel.setText("Selected: " + file.getFile().getAbsolutePath()
                        + "  —  " + FileSizeFormatter.format(file.getSize()));
            }
        });

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, chartPanel);
        splitPane.setDividerLocation(480);
        splitPane.setResizeWeight(0.40);
        splitPane.setBorder(new EmptyBorder(0, 6, 0, 6));
        return splitPane;
    }

    private JPanel buildStatusBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Separator.foreground")),
                new EmptyBorder(4, 10, 4, 10)));

        statusLabel = new JLabel("Ready. Select a drive and click Analyze.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setPreferredSize(new Dimension(200, 16));
        progressBar.setVisible(false);

        panel.add(statusLabel,  BorderLayout.CENTER);
        panel.add(progressBar,  BorderLayout.EAST);
        return panel;
    }

    // ── Analysis lifecycle ────────────────────────────────────────

    private void startAnalysis() {
        File selectedDisk = (File) diskComboBox.getSelectedItem();
        if (selectedDisk == null) return;

        analyzeBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);

        treePanel.clear();
        chartPanel.showPlaceholder();

        statusLabel.setText("Scanning " + selectedDisk.getAbsolutePath() + " …");

        currentWorker = new DiskAnalyzerWorker(
                selectedDisk,
                msg -> SwingUtilities.invokeLater(() -> statusLabel.setText(msg)),
                AppSettings.get().getSkipPaths());

        currentWorker.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && evt.getNewValue() == SwingWorker.StateValue.DONE) {
                SwingUtilities.invokeLater(this::onAnalysisDone);
            }
        });

        currentWorker.execute();
    }

    private void onAnalysisDone() {
        analyzeBtn.setEnabled(true);
        cancelBtn.setEnabled(false);
        progressBar.setVisible(false);
        progressBar.setIndeterminate(false);

        if (currentWorker.isCancelled()) {
            statusLabel.setText("Analysis cancelled.");
            return;
        }

        try {
            DefaultMutableTreeNode root = currentWorker.get();
            FolderNode rootFolder = (FolderNode) root.getUserObject();

            treePanel.setTreeData(root);
            chartPanel.updateChart(root);

            statusLabel.setText("Done — "
                    + FileSizeFormatter.formatWithCount(rootFolder.getSize(), rootFolder.getFileCount())
                    + "  in  " + rootFolder.getName());

        } catch (InterruptedException | ExecutionException ex) {
            statusLabel.setText("Error: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Analysis failed:\n" + ex.getCause().getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cancelAnalysis() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.requestStop();
            statusLabel.setText("Cancelling…");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void refreshDiskInfo() {
        File disk = (File) diskComboBox.getSelectedItem();
        if (disk == null) return;
        long total = disk.getTotalSpace();
        long free  = disk.getFreeSpace();
        long used  = total - free;
        double pct = total > 0 ? 100.0 * used / total : 0;
        diskInfoLabel.setText(String.format(
                "  Used: %s  |  Free: %s  |  Total: %s  (%.1f%% full)",
                FileSizeFormatter.format(used),
                FileSizeFormatter.format(free),
                FileSizeFormatter.format(total), pct));
    }

    private void onClose() {
        cancelAnalysis();
        dispose();
        System.exit(0);
    }

    /** Tiny coloured square as window icon (no external resource needed). */
    private Image buildIcon() {
        int size = 32;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x4ECDC4));
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(new Color(0xFF6B6B));
        g.fillArc(size / 2, 2, size / 2 - 2, size / 2 - 2, 0, 360);
        g.dispose();
        return img;
    }

    // ── Inner renderer ────────────────────────────────────────────

    private static class DiskComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof File f) {
                long total = f.getTotalSpace();
                long free  = f.getFreeSpace();
                long used  = total - free;
                double pct = total > 0 ? 100.0 * used / total : 0;
                setText(String.format("%s   [%s used / %s total  %.0f%%]",
                        f.getAbsolutePath(),
                        FileSizeFormatter.format(used),
                        FileSizeFormatter.format(total), pct));
            }
            return this;
        }
    }
}
