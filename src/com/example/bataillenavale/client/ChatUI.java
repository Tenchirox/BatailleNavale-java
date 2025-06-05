package com.example.bataillenavale.client;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

public class ChatUI extends JPanel {
    /**
	 * 
	 */
	private static final long serialVersionUID = -938676808712185229L;
	private JTextArea chatDisplayArea;
    private JTextField chatInputField;
    private JButton sendChatButton;

    public ChatUI() {
        super(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Chat"));

        chatDisplayArea = new JTextArea(10, 25);
        chatDisplayArea.setEditable(false);
        chatDisplayArea.setLineWrap(true);
        chatDisplayArea.setWrapStyleWord(true);
        JScrollPane chatScrollPane = new JScrollPane(chatDisplayArea);
        add(chatScrollPane, BorderLayout.CENTER);

        JPanel chatInputPanel = new JPanel(new BorderLayout(5, 0));
        chatInputField = new JTextField();
        sendChatButton = new JButton("Envoyer");

        chatInputPanel.add(chatInputField, BorderLayout.CENTER);
        chatInputPanel.add(sendChatButton, BorderLayout.EAST);
        add(chatInputPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(250, 0)); // Largeur préférée
    }

    public void appendMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            chatDisplayArea.append(message + "\n");
            chatDisplayArea.setCaretPosition(chatDisplayArea.getDocument().getLength());
        });
    }

    public String getChatMessage() {
        String message = chatInputField.getText().trim();
        if (!message.isEmpty()) {
            chatInputField.setText("");
        }
        return message;
    }

    public void addSendButtonListener(ActionListener listener) {
        sendChatButton.addActionListener(listener);
    }

    public void addInputFieldEnterListener(ActionListener listener) {
        chatInputField.addActionListener(listener);
    }

    public void clearChat() {
        chatDisplayArea.setText("");
    }
}