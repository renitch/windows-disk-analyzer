package com.diskanalyzer;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } catch (Exception e) {
                // Fall back to system L&F
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) {}
            }
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
