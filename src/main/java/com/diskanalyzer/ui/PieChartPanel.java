package com.diskanalyzer.ui;

import com.diskanalyzer.model.FileNode;
import com.diskanalyzer.model.FolderNode;
import com.diskanalyzer.model.FileSystemNode;
import com.diskanalyzer.util.FileSizeFormatter;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.PieSectionLabelGenerator;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.general.DefaultPieDataset;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PieChartPanel extends JPanel {

    private static final int TOP_N = 12;

    // Palette — vivid colours that stand out on dark background
    private static final Color[] PALETTE = {
        new Color(0xFF6B6B), new Color(0x4ECDC4), new Color(0x45B7D1),
        new Color(0xFFA07A), new Color(0x98D8C8), new Color(0xF7DC6F),
        new Color(0xBB8FCE), new Color(0x82E0AA), new Color(0xF0B27A),
        new Color(0x85C1E9), new Color(0xF1948A), new Color(0xA9DFBF),
        new Color(0xAAAAAA)  // "Others"
    };

    private ChartPanel chartPanel;

    public PieChartPanel() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(6, 6, 6, 6));
        showPlaceholder();
    }

    // ── Public API ────────────────────────────────────────────────

    public void showPlaceholder() {
        removeAll();
        JLabel placeholder = new JLabel(
                "<html><center>Select a disk and click <b>Analyze</b><br>to see the folder size distribution.</center></html>",
                SwingConstants.CENTER);
        placeholder.setFont(placeholder.getFont().deriveFont(14f));
        placeholder.setForeground(UIManager.getColor("Label.disabledForeground"));
        add(placeholder, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /** Builds the chart from the scanned tree root. */
    public void updateChart(DefaultMutableTreeNode rootNode) {
        removeAll();

        FolderNode rootFolder = (FolderNode) rootNode.getUserObject();
        long rootSize = rootFolder.getSize();

        // Collect only sub-directory children (FileNode leaves are ignored for the pie)
        List<FolderNode> children = new ArrayList<>();
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (child.getUserObject() instanceof FolderNode fn) {
                children.add(fn);
            }
        }
        children.sort(Comparator.comparingLong(FolderNode::getSize).reversed());

        DefaultPieDataset<String> dataset = new DefaultPieDataset<>();

        long accountedFor = 0;
        int limit = Math.min(TOP_N, children.size());
        for (int i = 0; i < limit; i++) {
            FolderNode node = children.get(i);
            if (node.getSize() == 0) break;
            String label = node.getName() + "\n" + FileSizeFormatter.format(node.getSize());
            dataset.setValue(label, node.getSize());
            accountedFor += node.getSize();
        }

        long others = rootSize - accountedFor;
        if (others > 0) {
            dataset.setValue("Files / Others\n" + FileSizeFormatter.format(others), others);
        }

        // ── Build chart ───────────────────────────────────────────
        JFreeChart chart = ChartFactory.createPieChart(
                "Folder Size Distribution — " + rootFolder.getName()
                + "  (" + FileSizeFormatter.format(rootSize) + " total)",
                dataset, true, true, false);

        styleChart(chart, dataset);

        chartPanel = new ChartPanel(chart);
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setBorder(BorderFactory.createEmptyBorder());

        // ── Summary table below chart ─────────────────────────────
        JPanel wrapper = new JPanel(new BorderLayout(0, 6));
        wrapper.add(chartPanel, BorderLayout.CENTER);
        wrapper.add(buildSummaryTable(children, rootSize, limit), BorderLayout.SOUTH);

        add(wrapper, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ── Styling ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void styleChart(JFreeChart chart, DefaultPieDataset<String> dataset) {
        Color background = UIManager.getColor("Panel.background");
        if (background == null) background = new Color(0x2B2B2B);

        chart.setBackgroundPaint(background);
        chart.getLegend().setBackgroundPaint(background);
        chart.getLegend().setItemPaint(Color.LIGHT_GRAY);

        TextTitle title = chart.getTitle();
        title.setPaint(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        PiePlot<String> plot = (PiePlot<String>) chart.getPlot();
        plot.setBackgroundPaint(background);
        plot.setOutlineVisible(false);
        plot.setShadowPaint(null);
        plot.setLabelBackgroundPaint(new Color(50, 50, 50, 200));
        plot.setLabelOutlinePaint(Color.GRAY);
        plot.setLabelShadowPaint(null);
        plot.setLabelFont(new Font("SansSerif", Font.PLAIN, 11));
        plot.setLabelPaint(Color.WHITE);
        plot.setSimpleLabels(false);

        // Custom label: "FolderName\nXX.X MB (yy.y%)"
        PieSectionLabelGenerator gen = new StandardPieSectionLabelGenerator(
                "{0}\n{2}", new DecimalFormat("#,##0"), new DecimalFormat("0.0%"));
        plot.setLabelGenerator(gen);

        // Assign colours
        int ci = 0;
        for (int i = 0; i < dataset.getItemCount(); i++) {
            Comparable<?> key = dataset.getKey(i);
            Color c = PALETTE[ci % PALETTE.length];
            plot.setSectionPaint(key, c);
            plot.setSectionOutlinePaint(key, background);
            plot.setSectionOutlineStroke(key, new BasicStroke(2f));
            ci++;
        }

        // Explode the largest slice slightly
        if (dataset.getItemCount() > 0) {
            plot.setExplodePercent(dataset.getKey(0), 0.04);
        }
    }

    private JScrollPane buildSummaryTable(List<FolderNode> children, long rootSize, int limit) {
        String[] columns = {"#", "Folder", "Size", "% of Drive"};
        Object[][] data = new Object[limit][4];

        for (int i = 0; i < limit; i++) {
            FolderNode node = children.get(i);
            double pct = rootSize > 0 ? 100.0 * node.getSize() / rootSize : 0;
            data[i][0] = i + 1;
            data[i][1] = node.getName();
            data[i][2] = FileSizeFormatter.format(node.getSize());
            data[i][3] = String.format("%.1f %%", pct);
        }

        JTable table = new JTable(data, columns) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(35);
        table.getColumnModel().getColumn(3).setMaxWidth(80);

        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(0, 140));
        sp.setBorder(BorderFactory.createTitledBorder("Top Folders"));
        return sp;
    }
}
