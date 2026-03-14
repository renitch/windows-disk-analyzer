package com.diskanalyzer.ui;

import com.diskanalyzer.AppSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;

/**
 * Modal Settings dialog.
 * <ul>
 *   <li>Skipped-scan folders list — add via right-click in the tree; remove here.</li>
 *   <li>Deletion confirmation toggle.</li>
 * </ul>
 */
public class SettingsDialog extends JDialog {

    private final AppSettings settings = AppSettings.get();

    private DefaultListModel<String> skipListModel;
    private JList<String>            skipList;
    private JButton                  removeFolderBtn;
    private JButton                  clearAllBtn;
    private JCheckBox                confirmDeleteChk;

    public SettingsDialog(Frame owner) {
        super(owner, "Settings", true);
        setSize(620, 430);
        setMinimumSize(new Dimension(480, 360));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUI();
        loadFromSettings();
    }

    // ── UI ────────────────────────────────────────────────────────

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        root.add(buildSkipPanel(),    BorderLayout.CENTER);
        root.add(buildBottomPanel(),  BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildSkipPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new TitledBorder("Folders to Skip During Scan"));

        // Info label
        JLabel info = new JLabel(
                "<html><i>These folders are skipped when scanning. " +
                "Add them by right-clicking a folder in the tree → \"Skip Scan\".</i></html>");
        info.setBorder(new EmptyBorder(0, 2, 4, 2));
        info.setFont(info.getFont().deriveFont(11f));

        // List
        skipListModel = new DefaultListModel<>();
        skipList = new JList<>(skipListModel);
        skipList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        skipList.addListSelectionListener(e -> removeFolderBtn.setEnabled(
                !skipList.isSelectionEmpty()));

        // Delete key removes selected
        skipList.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeSkip");
        skipList.getActionMap().put("removeSkip",
                new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        removeSelectedSkipPaths();
                    }
                });

        JScrollPane scroll = new JScrollPane(skipList);
        scroll.setPreferredSize(new Dimension(0, 180));

        // Buttons
        removeFolderBtn = new JButton("Remove Selected");
        removeFolderBtn.setEnabled(false);
        removeFolderBtn.addActionListener(e -> removeSelectedSkipPaths());

        clearAllBtn = new JButton("Clear All");
        clearAllBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Remove all skipped folders?", "Clear All",
                    JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                settings.clearSkipPaths();
                skipListModel.clear();
                removeFolderBtn.setEnabled(false);
            }
        });

        JButton addFolderBtn = new JButton("Add Folder…");
        addFolderBtn.setToolTipText("Browse for a folder to add to the skip list");
        addFolderBtn.addActionListener(e -> browseAndAdd());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(addFolderBtn);
        btnRow.add(removeFolderBtn);
        btnRow.add(clearAllBtn);

        panel.add(info,   BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(btnRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Deletion"));

        confirmDeleteChk = new JCheckBox("Confirm before deleting files or folders  (recommended)", true);
        confirmDeleteChk.setBorder(new EmptyBorder(4, 4, 4, 4));
        confirmDeleteChk.addActionListener(e ->
                settings.setConfirmDeletion(confirmDeleteChk.isSelected()));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        btnRow.add(closeBtn);

        panel.add(confirmDeleteChk, BorderLayout.CENTER);
        panel.add(btnRow,           BorderLayout.SOUTH);
        return panel;
    }

    // ── Load / save ───────────────────────────────────────────────

    private void loadFromSettings() {
        skipListModel.clear();
        for (String p : settings.getSkipPaths()) skipListModel.addElement(p);
        confirmDeleteChk.setSelected(settings.isConfirmDeletion());
    }

    // ── Actions ───────────────────────────────────────────────────

    private void removeSelectedSkipPaths() {
        for (String p : skipList.getSelectedValuesList()) {
            settings.removeSkipPath(p);
            skipListModel.removeElement(p);
        }
        removeFolderBtn.setEnabled(false);
    }

    private void browseAndAdd() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Select folder(s) to skip");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        for (File f : fc.getSelectedFiles()) {
            String path = f.getAbsolutePath();
            if (!skipListModel.contains(path)) {
                settings.addSkipPath(path);
                skipListModel.addElement(path);
            }
        }
    }

    // ── Public helper called from the tree's right-click menu ─────

    /** Adds a path to the skip list programmatically (from the context menu). */
    public static void addSkipPath(String path) {
        AppSettings.get().addSkipPath(path);
    }
}
