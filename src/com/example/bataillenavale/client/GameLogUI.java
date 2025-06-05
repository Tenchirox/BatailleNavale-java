package com.example.bataillenavale.client;

import javax.swing.*;
import java.awt.*;

public class GameLogUI extends JPanel {
    private JTextArea gameLogArea;

    public GameLogUI() {
        super(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Log de la Partie"));

        gameLogArea = new JTextArea(10, 25);
        gameLogArea.setEditable(false);
        gameLogArea.setLineWrap(true);
        gameLogArea.setWrapStyleWord(true);
        JScrollPane gameLogScrollPane = new JScrollPane(gameLogArea);
        add(gameLogScrollPane, BorderLayout.CENTER);

        setPreferredSize(new Dimension(250, 0)); // Largeur préférée
    }

    public void addLogEntry(String entry) {
        SwingUtilities.invokeLater(() -> {
            gameLogArea.append(entry + "\n");
            gameLogArea.setCaretPosition(gameLogArea.getDocument().getLength());
        });
    }

    public void clearLog() {
        gameLogArea.setText("");
    }
}