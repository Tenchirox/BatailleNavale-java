package com.example.bataillenavale.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.function.Consumer; // Pour le callback de message
import java.util.function.BiConsumer; // Pour le callback d'erreur de connexion

public class ServerCommunicator {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread listeningThread;
    private volatile boolean isConnected = false;

    private Consumer<String> onMessageReceived; // Callback pour les messages du serveur
    private Runnable onDisconnected;          // Callback pour la déconnexion
    private BiConsumer<String, Exception> onConnectionError; // Callback pour les erreurs de connexion

    public ServerCommunicator(Consumer<String> onMessageReceived, Runnable onDisconnected, BiConsumer<String, Exception> onConnectionError) {
        this.onMessageReceived = onMessageReceived;
        this.onDisconnected = onDisconnected;
        this.onConnectionError = onConnectionError;
    }

    public boolean connect(String serverAddress, int serverPort, String playerName) {
        try {
            if (socket != null && !socket.isClosed()) {
                disconnect(); // Fermer une connexion existante
            }
            socket = new Socket(serverAddress, serverPort);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            isConnected = true;

            // Démarrer le thread d'écoute
            listeningThread = new Thread(this::listenToServerLoop);
            listeningThread.start();

            // Envoyer le nom du joueur
            // Le serveur devrait répondre avec REQ_NAME si nécessaire, ou traiter SET_NAME.
            // Pour simplifier, on envoie SET_NAME directement après la connexion.
            // sendMessage("SET_NAME:" + playerName); // Ou laisser BatailleNavaleClient le faire.
            // La logique actuelle de BatailleNavaleClient envoie SET_NAME après le clic sur "Connecter".

            return true;
        } catch (UnknownHostException ex) {
            isConnected = false;
            if (onConnectionError != null) {
                onConnectionError.accept("Hôte inconnu: " + serverAddress, ex);
            }
            return false;
        } catch (IOException ex) {
            isConnected = false;
            if (onConnectionError != null) {
                onConnectionError.accept("Connexion impossible à " + serverAddress + ":" + serverPort, ex);
            }
            return false;
        }
    }

    private void listenToServerLoop() {
        try {
            String serverMessage;
            while (isConnected && socket != null && !socket.isClosed() && (serverMessage = in.readLine()) != null) {
                if (onMessageReceived != null) {
                    final String finalMessage = serverMessage; // Pour la lambda
                    SwingUtilities.invokeLater(() -> onMessageReceived.accept(finalMessage));
                }
            }
        } catch (IOException e) {
            if (isConnected) { // Si l'erreur survient pendant une connexion active
                System.err.println("ServerCommunicator: Erreur de lecture du serveur: " + e.getMessage());
            }
        } finally {
            if (isConnected) { // Si on était connecté et que la boucle se termine
                isConnected = false; // Marquer comme déconnecté
                if (onDisconnected != null) {
                     SwingUtilities.invokeLater(onDisconnected); // Exécuter le callback de déconnexion sur l'EDT
                }
            }
            cleanup();
        }
    }

    public void sendMessage(String message) {
        if (out != null && isConnected && !out.checkError()) {
            out.println(message);
        } else {
            System.err.println("ServerCommunicator: Impossible d'envoyer le message, non connecté ou erreur de flux.");
        }
    }

    public boolean isConnected() {
        return isConnected && socket != null && !socket.isClosed();
    }

    public void disconnect() {
        isConnected = false; // Important de le mettre à false en premier pour arrêter la boucle d'écoute
        cleanup();
        if (listeningThread != null && listeningThread.isAlive()) {
            listeningThread.interrupt(); // Tenter d'interrompre le thread d'écoute
        }
    }

    private void cleanup() {
        try {
            if (in != null) in.close();
        } catch (IOException e) { /* Ignored */ }
        if (out != null) out.close();
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) { /* Ignored */ }
        in = null;
        out = null;
        socket = null;
    }
}