package com.example.bataillenavale.client; // Exemple de package

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage; 
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException; 
import java.net.Socket;
import java.net.URL; 
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List; 
import java.util.HashMap; 
import java.util.Map; 


public class BatailleNavaleClient extends JFrame {

    // Énumérations et constantes du modèle
    public static class ModelConstants {
        public enum ShipType {
            PORTE_AVIONS("Porte-avions", 5, 'P'), 
            CROISEUR("Croiseur", 4, 'C'),
            CONTRE_TORPILLEUR("Contre-torpilleur", 3, 'T'),
            SOUS_MARIN("Sous-marin", 3, 'S'),
            TORPILLEUR("Torpilleur", 2, 'R');

            private final String nom;
            private final int taille;
            private final char spriteChar; 

            ShipType(String nom, int taille, char spriteChar) { 
                this.nom = nom;
                this.taille = taille;
                this.spriteChar = spriteChar;
            }
            public String getNom() { return nom; }
            public int getTaille() { return taille; }
            public char getSpriteChar() { return spriteChar; } 
        }

        public enum ShotResult { MANQUE, TOUCHE, COULE, DEJA_JOUE, ERREUR }
        public static final int TAILLE_GRILLE = 10;
        public static final char OPP_UNKNOWN = '?';
        public static final char OPP_MISS = 'O'; 
        public static final char OPP_HIT = 'X';  
        public static final char OPP_SUNK = '!'; 
        public static final char MY_EMPTY = '~';
        public static final char MY_HIT_ON_ME = 'X'; 
        public static final char MY_MISS_ON_ME = 'M'; 
    }

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private String serverAddress = "127.0.0.1";
    private int serverPort = 12347;

    private GridPanel myGridPanel; // Grille personnelle du joueur
    // Pour le mode multi-joueurs et spectateur, nous aurons un conteneur pour toutes les grilles visibles
    private JPanel gameGridsDisplayPanel; // Remplacera opponentGridsContainerPanel pour plus de flexibilité
    private Map<Integer, GridPanel> playerGridPanels = new HashMap<>(); // Stocke toutes les grilles (ma grille + adversaires)
    private Map<Integer, JLabel> playerNameLabels = new HashMap<>(); // Pour les noms au-dessus des grilles


    private JLabel statusLabel;
    private JTextArea lobbyArea;
    private JButton connectButton; 
    private JTextField nameField, serverIpField;
    private JPanel connectionPanel, mainGamePanel, placementControlsPanel;
    private JPanel lobbyPanelContainer; 

    private JRadioButton horizontalRadioButton, verticalRadioButton;
    private JLabel shipToPlaceLabel;
    private JButton quitGameButton; 
    private JButton hostStartGameButton; 

    // Composants du Chat
    private JTextArea chatDisplayArea;
    private JTextField chatInputField;
    private JButton sendChatButton;
    private JPanel chatPanel;


    private String playerName = "Joueur"; 
    private int playerIndex = -1; 
    private boolean myTurn = false;
    private boolean placementPhase = false;
    private ModelConstants.ShipType currentShipToPlace = null;
    private int currentShipSize = 0;
    private String currentShipDisplayName = "";
    private boolean inGame = false; 
    private boolean amISpectator = false; // Nouveau flag pour le mode spectateur
    
    private int totalPlayersInGame = 0;
    private Map<Integer, String> allPlayerNames = new HashMap<>(); // Stocke les noms de tous les joueurs par leur index de jeu
    private int minPlayersToStartLobby = 2; 
    private boolean nameSuccessfullySentThisSession = false; 


    public BatailleNavaleClient() {
        setTitle("Bataille Navale Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(10,10,10,10));

        // --- Panneau de Connexion (IP et Nom) ---
        connectionPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbcConn = new GridBagConstraints();
        gbcConn.insets = new Insets(5,5,5,5); 
        gbcConn.fill = GridBagConstraints.HORIZONTAL;

        gbcConn.gridx = 0; gbcConn.gridy = 0; connectionPanel.add(new JLabel("Serveur IP:"), gbcConn);
        serverIpField = new JTextField(serverAddress, 15);
        gbcConn.gridx = 1; gbcConn.gridy = 0; connectionPanel.add(serverIpField, gbcConn);
        
        gbcConn.gridx = 0; gbcConn.gridy = 1; connectionPanel.add(new JLabel("Votre Nom (max 15 car.):"), gbcConn);
        nameField = new JTextField(playerName, 15);
        gbcConn.gridx = 1; gbcConn.gridy = 1; connectionPanel.add(nameField, gbcConn);
        
        connectButton = new JButton("Se Connecter et Rejoindre");
        gbcConn.gridx = 0; gbcConn.gridy = 2; gbcConn.gridwidth = 2; 
        gbcConn.insets = new Insets(10,5,5,5); 
        connectionPanel.add(connectButton, gbcConn);
        
        mainGamePanel = new JPanel(new BorderLayout(10,10)); 

        lobbyPanelContainer = new JPanel(new BorderLayout(5,5));
        lobbyArea = new JTextArea(8, 40); 
        lobbyArea.setEditable(false);
        lobbyArea.setLineWrap(true);
        lobbyArea.setWrapStyleWord(true);
        lobbyPanelContainer.add(new JScrollPane(lobbyArea), BorderLayout.CENTER);

        hostStartGameButton = new JButton("Démarrer la Partie (Hôte)");
        hostStartGameButton.setVisible(false); 
        hostStartGameButton.addActionListener(e -> {
            if (out != null) {
                out.println("ADMIN_START_GAME");
            }
        });
        lobbyPanelContainer.add(hostStartGameButton, BorderLayout.SOUTH);


        statusLabel = new JLabel("Bienvenue! Entrez l'IP du serveur, votre nom, et connectez-vous.");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        
        quitGameButton = new JButton("Quitter"); // Texte générique, sera adapté
        quitGameButton.setVisible(false);
        quitGameButton.addActionListener(e -> {
            if (out != null) {
                out.println("QUIT_GAME"); 
            }
        });

        // --- Panneau de Chat ---
        chatPanel = new JPanel(new BorderLayout(5,5));
        chatPanel.setBorder(BorderFactory.createTitledBorder("Chat"));
        chatDisplayArea = new JTextArea(10, 25); 
        chatDisplayArea.setEditable(false);
        chatDisplayArea.setLineWrap(true);
        chatDisplayArea.setWrapStyleWord(true);
        JScrollPane chatScrollPane = new JScrollPane(chatDisplayArea);
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        JPanel chatInputPanel = new JPanel(new BorderLayout(5,0));
        chatInputField = new JTextField();
        chatInputField.addActionListener(e -> sendChatMessage()); // Envoi sur Entrée
        chatInputPanel.add(chatInputField, BorderLayout.CENTER);
        sendChatButton = new JButton("Envoyer");
        sendChatButton.addActionListener(e -> sendChatMessage());
        chatInputPanel.add(sendChatButton, BorderLayout.EAST);
        chatPanel.add(chatInputPanel, BorderLayout.SOUTH);
        chatPanel.setPreferredSize(new Dimension(250, 0)); // Donner une largeur préférée au chat
        
        add(chatPanel, BorderLayout.EAST); 


        connectButton.addActionListener(e -> connectAndSetName());

        switchToView("CONNECTION"); 

        pack(); 
        setSize(1200, 800); 
        setLocationRelativeTo(null);
        setVisible(true);
    }
    
    private void sendChatMessage() {
        String message = chatInputField.getText().trim();
        if (!message.isEmpty() && out != null) {
            out.println("CHAT_MSG:" + message);
            chatInputField.setText(""); 
        }
    }


    private void switchToView(String viewName) {
        if (connectionPanel.getParent() == getContentPane()) getContentPane().remove(connectionPanel);
        if (mainGamePanel.getParent() == getContentPane()) getContentPane().remove(mainGamePanel);
        
        connectionPanel.setVisible(false);
        mainGamePanel.setVisible(false); 
        lobbyPanelContainer.setVisible(false); 
        chatPanel.setVisible(false); // Cacher le chat par défaut, réafficher si besoin


        switch (viewName) {
            case "CONNECTION":
                if (connectionPanel.getParent() == null) add(connectionPanel, BorderLayout.NORTH);
                connectionPanel.setVisible(true);
                if (nameField != null) {
                    nameField.setText(this.playerName); 
                    nameField.setEditable(true);
                }
                if (serverIpField != null) {
                    serverIpField.setEditable(true);
                }
                if (connectButton != null) {
                    connectButton.setEnabled(true);
                }
                quitGameButton.setVisible(false);
                hostStartGameButton.setVisible(false);
                this.playerIndex = -1; 
                this.inGame = false;
                this.placementPhase = false;
                this.nameSuccessfullySentThisSession = false; 
                this.amISpectator = false;
                break;
            case "LOBBY":
                if (mainGamePanel.getParent() == null) add(mainGamePanel, BorderLayout.CENTER);
                mainGamePanel.removeAll();
                mainGamePanel.add(lobbyPanelContainer, BorderLayout.CENTER);
                lobbyPanelContainer.setVisible(true);
                mainGamePanel.setVisible(true);
                quitGameButton.setText("Quitter le Lobby");
                quitGameButton.setVisible(true); 
                chatPanel.setVisible(true); 
                break;
            case "PLACEMENT":
            case "COMBAT":
            case "SPECTATOR": // Vue similaire pour le jeu
                if (mainGamePanel.getParent() == null) add(mainGamePanel, BorderLayout.CENTER);
                mainGamePanel.setVisible(true); 
                quitGameButton.setText(amISpectator ? "Quitter (Spectateur)" : "Quitter la Partie");
                quitGameButton.setVisible(true); 
                hostStartGameButton.setVisible(false); 
                chatPanel.setVisible(true); 
                break;
        }
        getContentPane().revalidate();
        getContentPane().repaint();
    }
    
    private void switchToLobbyView(String lobbyMessage, int namedPlayerCount, int minPlayers, String firstPlayerNameInLobby) {
        inGame = false; 
        placementPhase = false;
        amISpectator = false; // Pas spectateur dans le lobby (on est un joueur potentiel)
        
        lobbyArea.setText(lobbyMessage);
        
        boolean amIHost = this.playerName.equals(firstPlayerNameInLobby) && !firstPlayerNameInLobby.isEmpty();
        if (this.playerIndex != -1 && this.playerIndex != 0) { 
            amIHost = false;
        } else if (this.playerIndex == -1 && !this.playerName.equals(firstPlayerNameInLobby)) {
            amIHost = false;
        }

        hostStartGameButton.setVisible(amIHost);
        hostStartGameButton.setEnabled(amIHost && namedPlayerCount >= minPlayers);
        
        switchToView("LOBBY"); 
        statusLabel.setText("Dans le lobby. En attente du démarrage de la partie...");
    }

    // Renommée et adaptée pour afficher toutes les grilles nécessaires (sa propre ou celles des autres)
    private void setupPlayerGridsForGameView() {
        gameGridsDisplayPanel = new JPanel(); // Conteneur principal pour les grilles
        playerGridPanels.clear(); // Utiliser playerGridPanels au lieu de opponentGridsMap
        playerNameLabels.clear(); // Utiliser playerNameLabels au lieu de opponentNameLabelsMap

        if (amISpectator) {
            // Mode spectateur : afficher toutes les grilles des joueurs en jeu
            int numPlayersToDisplay = totalPlayersInGame;
            if (numPlayersToDisplay <= 0) return;

            if (numPlayersToDisplay <= 2) { // Si 1 ou 2 joueurs, afficher en ligne
                 gameGridsDisplayPanel.setLayout(new GridLayout(1, numPlayersToDisplay, 10, 5));
            } else if (numPlayersToDisplay <= 4) { // Pour 3 ou 4 joueurs
                 gameGridsDisplayPanel.setLayout(new GridLayout(2, 2, 10, 5));
            } else { // Pour 5 ou 6 joueurs (max 7 joueurs, donc 6 adversaires max)
                 gameGridsDisplayPanel.setLayout(new GridLayout(2, 3, 10, 5));
            }


            for (int i = 0; i < totalPlayersInGame; i++) {
                JPanel singlePlayerGameView = new JPanel(new BorderLayout(0,3));
                singlePlayerGameView.setBorder(BorderFactory.createTitledBorder(allPlayerNames.getOrDefault(i, "Joueur " + i)));
                GridPanel pGrid = new GridPanel(ModelConstants.TAILLE_GRILLE, false, i); // Non cliquable pour spectateur
                pGrid.setEnabled(false); // Désactiver toute interaction de tir
                playerGridPanels.put(i, pGrid);
                singlePlayerGameView.add(pGrid, BorderLayout.CENTER);
                gameGridsDisplayPanel.add(singlePlayerGameView);
            }
        } else { // Mode Joueur
            myGridPanel = new GridPanel(ModelConstants.TAILLE_GRILLE, true, playerIndex);
            playerGridPanels.put(playerIndex, myGridPanel); // Ajouter sa propre grille à la map

            JPanel myGridContainer = new JPanel(new BorderLayout());
            myGridContainer.setBorder(BorderFactory.createTitledBorder(this.playerName + " (Votre Grille)"));
            myGridContainer.add(new JScrollPane(myGridPanel), BorderLayout.CENTER);

            // Conteneur pour les grilles des adversaires
            JPanel opponentsContainer = new JPanel();
            int numOpponents = totalPlayersInGame - 1;
            if (numOpponents > 0) {
                 if (numOpponents <= 2) { // 1-2 adversaires : en ligne
                    opponentsContainer.setLayout(new GridLayout(1, numOpponents, 5, 5));
                } else { // 3+ adversaires : sur 2 lignes si besoin
                    int cols = (numOpponents + 1) / 2;
                    opponentsContainer.setLayout(new GridLayout(2, cols, 5, 5));
                }
            }


            for (int i = 0; i < totalPlayersInGame; i++) {
                if (i == playerIndex) continue;

                JPanel singleOpponentPanel = new JPanel(new BorderLayout(0,3));
                singleOpponentPanel.setBorder(BorderFactory.createTitledBorder("Tirer sur: " + allPlayerNames.getOrDefault(i, "Adversaire " + i)));
                GridPanel oppGrid = new GridPanel(ModelConstants.TAILLE_GRILLE, false, i);
                playerGridPanels.put(i, oppGrid); // Stocker la grille de l'adversaire
                singleOpponentPanel.add(oppGrid, BorderLayout.CENTER);
                opponentsContainer.add(singleOpponentPanel);
            }
            
            gameGridsDisplayPanel.setLayout(new BorderLayout(10,10));
            gameGridsDisplayPanel.add(myGridContainer, BorderLayout.WEST);
            if (numOpponents > 0) {
                 gameGridsDisplayPanel.add(new JScrollPane(opponentsContainer), BorderLayout.CENTER);
            }
        }
    }


    private void switchToPlacementView() {
        placementPhase = true;
        inGame = true; 
        amISpectator = false;
        
        setupPlayerGridsForGameView(); 
        myGridPanel = playerGridPanels.get(playerIndex); // Récupérer sa grille

        for(Map.Entry<Integer, GridPanel> entry : playerGridPanels.entrySet()){
            if (entry.getKey() != playerIndex) { // Grilles adverses
                entry.getValue().setEnabled(false); 
            } else { // Sa propre grille
                 entry.getValue().setEnabled(true); // Activer pour le placement
            }
        }


        placementControlsPanel = new JPanel(new FlowLayout());
        shipToPlaceLabel = new JLabel("Navire à placer: ");
        placementControlsPanel.add(shipToPlaceLabel);

        horizontalRadioButton = new JRadioButton("Horizontal", true);
        verticalRadioButton = new JRadioButton("Vertical");
        ButtonGroup orientationGroup = new ButtonGroup();
        orientationGroup.add(horizontalRadioButton);
        orientationGroup.add(verticalRadioButton);
        placementControlsPanel.add(horizontalRadioButton);
        placementControlsPanel.add(verticalRadioButton);
        
        mainGamePanel.removeAll(); 
        mainGamePanel.add(gameGridsDisplayPanel, BorderLayout.CENTER);
        mainGamePanel.add(placementControlsPanel, BorderLayout.SOUTH); 

        switchToView("PLACEMENT");
        statusLabel.setText("Phase de placement des navires.");
        quitGameButton.setText("Quitter la Partie"); 
    }
    
    private void switchToGameOrSpectatorView() { 
        placementPhase = false;
        inGame = true; 
        
        setupPlayerGridsForGameView();
        myGridPanel = playerGridPanels.get(playerIndex); 

        if (amISpectator) {
            if(myGridPanel != null) myGridPanel.setVisible(false); 
            for(GridPanel pGrid : playerGridPanels.values()){ 
                pGrid.setPlacementMode(false);
                pGrid.setEnabled(false); 
            }
            statusLabel.setText("Mode Spectateur - Partie en cours...");
        } else { // Joueur
            if (myGridPanel != null) myGridPanel.setPlacementMode(false); 
            for(Map.Entry<Integer, GridPanel> entry : playerGridPanels.entrySet()){
                if (entry.getKey() != playerIndex) { 
                    entry.getValue().setPlacementMode(false);
                    entry.getValue().setEnabled(myTurn); 
                } else { // Sa propre grille
                     entry.getValue().setPlacementMode(false); // Pas de placement en combat
                     entry.getValue().setEnabled(false); // Sa propre grille n'est pas cliquable pour tirer
                }
            }
            statusLabel.setText("Phase de combat !");
        }
        
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(quitGameButton);
        
        mainGamePanel.removeAll(); 
        mainGamePanel.add(gameGridsDisplayPanel, BorderLayout.CENTER);
        mainGamePanel.add(bottomPanel, BorderLayout.SOUTH);
        
        switchToView(amISpectator ? "SPECTATOR" : "COMBAT");
        quitGameButton.setText(amISpectator ? "Quitter (Spectateur)" : "Quitter la Partie");
    }


    private void connectAndSetName() {
        serverAddress = serverIpField.getText().trim();
        this.playerName = nameField.getText().trim(); 

        if (serverAddress.isEmpty()) {
            statusLabel.setText("L'adresse IP du serveur ne peut pas être vide.");
            return;
        }
        if (this.playerName.isEmpty()) { 
            statusLabel.setText("Votre nom ne peut pas être vide.");
            return;
        }
        if (this.playerName.length() > 15) {
            statusLabel.setText("Erreur: Le nom ne doit pas dépasser 15 caractères.");
            return;
        }

        try {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ignored) {}
            }
            socket = new Socket(serverAddress, serverPort);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            connectButton.setEnabled(false);
            serverIpField.setEditable(false);
            nameField.setEditable(false); 
            
            statusLabel.setText("Connecté. Envoi du nom au serveur...");
            out.println("SET_NAME:" + this.playerName); 
            this.nameSuccessfullySentThisSession = true; 
            
            new Thread(this::listenToServer).start();

        } catch (UnknownHostException ex) {
            statusLabel.setText("Erreur: Hôte inconnu " + serverAddress);
            connectButton.setEnabled(true);
            serverIpField.setEditable(true);
            nameField.setEditable(true);
            this.nameSuccessfullySentThisSession = false;
        } catch (IOException ex) {
            statusLabel.setText("Erreur: Connexion impossible à " + serverAddress + ":" + serverPort);
            connectButton.setEnabled(true);
            serverIpField.setEditable(true);
            nameField.setEditable(true);
            this.nameSuccessfullySentThisSession = false;
        }
    }

    private void listenToServer() {
        try {
            String serverMessage;
            while (socket != null && !socket.isClosed() && (serverMessage = in.readLine()) != null) {
                System.out.println("Serveur: " + serverMessage);
                String[] parts = serverMessage.split(":", 2);
                String command = parts[0];
                String payload = (parts.length > 1) ? parts[1] : "";

                SwingUtilities.invokeLater(() -> processServerMessage(command, payload));
            }
        } catch (IOException e) {
            if (socket != null && !socket.isClosed()) { 
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Déconnecté du serveur: " + e.getMessage());
                    switchToView("CONNECTION"); 
                });
            } else {
                System.out.println("Socket déjà fermé, erreur de lecture ignorée: " + e.getMessage());
            }
        } finally {
             try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ex) {}
             if (SwingUtilities.isEventDispatchThread()) {
                 switchToView("CONNECTION");
             } else {
                 SwingUtilities.invokeLater(() -> switchToView("CONNECTION"));
             }
        }
    }

    private void processServerMessage(String command, String payload) {
        switch (command) {
            case "REQ_NAME":
                statusLabel.setText("Le serveur demande votre nom pour une nouvelle session de lobby.");
                this.nameSuccessfullySentThisSession = false; 
                this.playerIndex = -1; 
                this.inGame = false;
                this.placementPhase = false;
                this.amISpectator = false;
                switchToView("CONNECTION"); 
                break;
            case "LOBBY_STATE":
                String[] lobbyData = payload.split(":", 4); 
                if (lobbyData.length >= 3) { 
                    int namedPlayerCount = Integer.parseInt(lobbyData[0]);
                    minPlayersToStartLobby = Integer.parseInt(lobbyData[1]);
                    String playerNamesList = (lobbyData.length > 3) ? lobbyData[3] : "";

                    String lobbyText = "Joueurs prêts (nom défini): " + namedPlayerCount +
                                       " (Min pour démarrer: " + minPlayersToStartLobby + ")\n";
                    if (!playerNamesList.isEmpty()) {
                        lobbyText += "Noms: " + playerNamesList;
                    } else {
                        lobbyText += "Noms: (aucun nom défini)";
                    }
                    String firstPlayerName = "";
                    boolean myNameIsInServerList = false;
                    if(!playerNamesList.isEmpty()){
                        String[] currentNamesInLobby = playerNamesList.split(",");
                         if (currentNamesInLobby.length > 0) {
                            firstPlayerName = currentNamesInLobby[0];
                         }
                         for(String name : currentNamesInLobby){
                             if(name.equals(this.playerName)){ 
                                 myNameIsInServerList = true;
                                 break;
                             }
                         }
                    }
                    
                    if (nameSuccessfullySentThisSession && myNameIsInServerList && !amISpectator) { 
                        switchToLobbyView(lobbyText, namedPlayerCount, minPlayersToStartLobby, firstPlayerName);
                    } else if (nameSuccessfullySentThisSession && !myNameIsInServerList && !amISpectator) {
                        statusLabel.setText("En attente de confirmation du nom dans le lobby...");
                        if(lobbyArea != null) lobbyArea.setText(lobbyText); 
                    } else if (!nameSuccessfullySentThisSession && connectionPanel.isVisible() && !amISpectator) { 
                        System.out.println("Lobby Update (sur écran connexion): " + lobbyText);
                        statusLabel.setText("Lobby: " + namedPlayerCount + " joueurs. Connectez-vous.");
                    } 

                } else {
                    System.err.println("LOBBY_STATE malformé: " + payload);
                }
                break;
            case "SPECTATE_MODE":
                amISpectator = true;
                statusLabel.setText("Mode Spectateur activé. En attente d'infos sur la partie...");
                break;
            case "SPECTATE_INFO":
                if (!amISpectator) break; 
                String[] spectateInfo = payload.split(":", 3);
                if(spectateInfo.length >= 3) {
                    this.totalPlayersInGame = Integer.parseInt(spectateInfo[1]);
                    String[] spectateNames = spectateInfo[2].split(",");
                    allPlayerNames.clear();
                     for(int i=0; i < spectateNames.length; i++) {
                        allPlayerNames.put(i, spectateNames[i]); 
                    }
                    statusLabel.setText("Spectateur de la partie avec " + this.totalPlayersInGame + " joueurs.");
                    switchToGameOrSpectatorView(); 
                } else {
                    statusLabel.setText("Erreur: Message SPECTATE_INFO malformé.");
                }
                break;

            case "GAME_START":
                String[] gameInfo = payload.split(":", 4);
                if (gameInfo.length >= 4) { 
                    this.playerIndex = Integer.parseInt(gameInfo[1]);
                    this.totalPlayersInGame = Integer.parseInt(gameInfo[2]);
                    String[] names = gameInfo[3].split(",");
                    allPlayerNames.clear();
                    for(int i=0; i < names.length; i++) {
                        allPlayerNames.put(i, names[i]);
                    }
                    if (allPlayerNames.containsKey(this.playerIndex)) {
                        this.playerName = allPlayerNames.get(this.playerIndex); 
                    }
                    amISpectator = false; 
                    statusLabel.setText("La partie commence ! Vous êtes " + this.playerName + " (J" + this.playerIndex + "). Placement.");
                    switchToPlacementView();
                } else {
                    statusLabel.setText("Erreur: Message GAME_START malformé.");
                }
                break;
            case "YOUR_TURN_PLACE_SHIP":
                if (amISpectator) break;
                Toolkit.getDefaultToolkit().beep(); 
                String[] shipInfo = payload.split(":");
                if (shipInfo.length >= 3) {
                    currentShipToPlace = ModelConstants.ShipType.valueOf(shipInfo[0]);
                    currentShipSize = Integer.parseInt(shipInfo[1]);
                    currentShipDisplayName = shipInfo[2];
                    if (shipToPlaceLabel != null) { 
                        shipToPlaceLabel.setText("Placez: " + currentShipDisplayName + " (taille " + currentShipSize + ")");
                    }
                    statusLabel.setText(allPlayerNames.getOrDefault(playerIndex, "Vous") + ", à vous de placer: " + currentShipDisplayName);
                    if (myGridPanel != null) {
                        myGridPanel.setEnabled(true); 
                        myGridPanel.setCurrentShipForPlacement(currentShipToPlace, horizontalRadioButton.isSelected());
                    }
                } else {
                     statusLabel.setText("Erreur: Message YOUR_TURN_PLACE_SHIP malformé.");
                }
                break;
            case "WAIT_PLACEMENT":
                 if (amISpectator) {
                    String[] waitSpectateInfo = payload.split(":");
                    if(waitSpectateInfo.length >=2) statusLabel.setText("Spectateur: " + waitSpectateInfo[0] + " place son " + waitSpectateInfo[1]);
                    else statusLabel.setText("Spectateur: Attente de placement...");
                    break;
                 }
                String[] waitInfo = payload.split(":");
                 if (waitInfo.length >= 2) {
                    statusLabel.setText("Attente: " + waitInfo[0] + " place son " + waitInfo[1]);
                 } else {
                    statusLabel.setText("Attente du placement par un adversaire...");
                 }
                if (myGridPanel != null) { 
                    myGridPanel.setEnabled(false);
                }
                break;
            case "PLACEMENT_ACCEPTED":
                 if (amISpectator) break;
                String[] acceptedInfo = payload.split(":");
                if (acceptedInfo.length >= 4) {
                    ModelConstants.ShipType placedType = ModelConstants.ShipType.valueOf(acceptedInfo[0]);
                    int pLigne = Integer.parseInt(acceptedInfo[1]);
                    int pCol = Integer.parseInt(acceptedInfo[2]);
                    boolean pHoriz = Boolean.parseBoolean(acceptedInfo[3]);
                    if (myGridPanel != null) {
                        myGridPanel.confirmShipPlacement(placedType, pLigne, pCol, pHoriz);
                    }
                    statusLabel.setText(placedType.getNom() + " placé avec succès par " + allPlayerNames.getOrDefault(playerIndex, "vous") + ".");
                    currentShipToPlace = null; 
                } else {
                    statusLabel.setText("Erreur: Message PLACEMENT_ACCEPTED malformé.");
                }
                break;
            case "PLACEMENT_REJECTED":
                 if (amISpectator) break;
                statusLabel.setText("Placement de " + payload + " refusé pour " + allPlayerNames.getOrDefault(playerIndex, "vous") + ". Réessayez.");
                break;
            case "ALL_SHIPS_PLACED":
                statusLabel.setText("Tous les navires sont placés. Début du combat !");
                switchToGameOrSpectatorView();
                break;
            case "YOUR_TURN_FIRE":
                if (amISpectator) break;
                Toolkit.getDefaultToolkit().beep(); 
                myTurn = true;
                statusLabel.setText(allPlayerNames.getOrDefault(playerIndex, "Vous") + ", à vous de tirer !");
                for(GridPanel oppGrid : playerGridPanels.values()){ 
                    if (oppGrid != myGridPanel) oppGrid.setEnabled(true);
                }
                break;
            case "OPPONENT_TURN_FIRE":
                statusLabel.setText("Au tour de " + payload + " de tirer.");
                if (!amISpectator) {
                    myTurn = false;
                    for(GridPanel oppGrid : playerGridPanels.values()){
                         if (oppGrid != myGridPanel) oppGrid.setEnabled(false);
                    }
                }
                break;
            case "SHOT_RESULT":
                String[] shotData = payload.split(":");
                if (shotData.length >= 5) {
                    int tireurIdx = Integer.parseInt(shotData[0]);
                    int cibleIdx = Integer.parseInt(shotData[1]);
                    int ligne = Integer.parseInt(shotData[2]);
                    int col = Integer.parseInt(shotData[3]);
                    ModelConstants.ShotResult resultat = ModelConstants.ShotResult.valueOf(shotData[4]);
                    String nomNavireCoule = (shotData.length > 5) ? shotData[5] : null;
                    String tireurName = allPlayerNames.getOrDefault(tireurIdx, "Joueur " + tireurIdx);
                    String cibleName = allPlayerNames.getOrDefault(cibleIdx, "Joueur " + cibleIdx);

                    GridPanel gridToUpdate = playerGridPanels.get(cibleIdx); // Mettre à jour la grille du joueur cible (qu'il soit moi ou un adversaire)
                    if (gridToUpdate != null) {
                        gridToUpdate.markShot(ligne, col, resultat);
                    }
                    
                    statusLabel.setText("Tir de " + tireurName + " sur " + cibleName + " en " + (char)('A'+col) + (ligne+1) + " -> " + resultat
                                           + (nomNavireCoule != null ? " (" + nomNavireCoule + " coulé!)" : ""));
                } else {
                    statusLabel.setText("Erreur: Message SHOT_RESULT malformé.");
                }
                break;
            case "GAME_OVER":
                String[] gameOverInfo = payload.split(":"); 
                String winnerMsg = "PARTIE TERMINÉE! Gagnant: " + gameOverInfo[0];
                JOptionPane.showMessageDialog(this, winnerMsg, "Fin de Partie", JOptionPane.INFORMATION_MESSAGE);
                statusLabel.setText(winnerMsg + ". Le serveur va réinitialiser le lobby.");
                myTurn = false;
                inGame = false;
                amISpectator = false; 
                if(myGridPanel != null) myGridPanel.setEnabled(false);
                for(GridPanel pGrid : playerGridPanels.values()){ pGrid.setEnabled(false); }
                quitGameButton.setVisible(false); 
                nameSuccessfullySentThisSession = false; 
                break;
            case "GAME_OVER_DISCONNECT":
                String disconnectMsg = "PARTIE TERMINÉE: " + payload + " s'est déconnecté.";
                JOptionPane.showMessageDialog(this, disconnectMsg, "Fin de Partie", JOptionPane.WARNING_MESSAGE);
                statusLabel.setText(disconnectMsg + ". Le serveur va réinitialiser le lobby.");
                myTurn = false;
                inGame = false;
                amISpectator = false;
                if(myGridPanel != null) myGridPanel.setEnabled(false);
                for(GridPanel pGrid : playerGridPanels.values()){ pGrid.setEnabled(false); }
                quitGameButton.setVisible(false);
                nameSuccessfullySentThisSession = false;
                break;
            case "PLAYER_LEFT": 
            case "PLAYER_LEFT_GAME_SPECTATOR": 
                statusLabel.setText("Joueur " + payload + " a quitté la partie.");
                GridPanel leftPlayerGrid = playerGridPanels.get(getPlayerIndexByName(payload));
                if(leftPlayerGrid != null) {
                    leftPlayerGrid.setBackground(Color.LIGHT_GRAY); 
                    leftPlayerGrid.setEnabled(false);
                }
                break;
            case "NEW_CHAT_MSG": 
                String[] chatParts = payload.split(":", 2);
                if (chatParts.length == 2) {
                    chatDisplayArea.append(chatParts[0] + ": " + chatParts[1] + "\n");
                } else {
                    chatDisplayArea.append(payload + "\n"); 
                }
                chatDisplayArea.setCaretPosition(chatDisplayArea.getDocument().getLength()); 
                break;
            case "LOBBY_COUNTDOWN_STARTED":
                 if(lobbyArea != null && lobbyPanelContainer.isVisible()) lobbyArea.append("\n\nCompte à rebours de " + payload + "s démarré !");
                 break;
            case "LOBBY_COUNTDOWN_CANCELLED":
                 if(lobbyArea != null && lobbyPanelContainer.isVisible()) lobbyArea.append("\n\nCompte à rebours annulé.");
                 break;
            case "LOBBY_TIMER_ENDED_NO_GAME":
                 if(lobbyArea != null && lobbyPanelContainer.isVisible()) lobbyArea.append("\n\nMinuteur terminé. " + payload + ". En attente...");
                 break;
            case "ERROR":
                statusLabel.setText("Erreur serveur: " + payload);
                if (payload.contains("nom est déjà pris")) { 
                    if(nameField != null) nameField.setEditable(true); 
                    if(connectButton != null) connectButton.setEnabled(true); 
                    this.nameSuccessfullySentThisSession = false; 
                } else if (payload.contains("Une partie est déjà en cours") || payload.contains("Serveur plein")) {
                    try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ex) {}
                    switchToView("CONNECTION");
                }
                break;
            default:
                System.out.println("Message serveur inconnu: " + command + " " + payload);
        }
    }
    
    private int getPlayerIndexByName(String name) {
        for(Map.Entry<Integer, String> entry : allPlayerNames.entrySet()) {
            if(entry.getValue().equals(name)) {
                return entry.getKey();
            }
        }
        return -1; // Non trouvé
    }

    // --- Classe interne pour la Grille ---
    class GridPanel extends JPanel {
        private JButton[][] buttons;
        private int taille;
        private boolean isMyBoard; 
        private final int boardOwnerPlayerIndex; 

        private boolean placementModeActive = false;
        private ModelConstants.ShipType shipToPlaceForPreview;
        private boolean previewHorizontal;
        private Point previewStartCell = null; 

        public GridPanel(int taille, boolean isMyBoard, int boardOwnerPlayerIndex) {
            this.taille = taille;
            this.isMyBoard = isMyBoard;
            this.boardOwnerPlayerIndex = boardOwnerPlayerIndex; 
            setLayout(new GridLayout(taille, taille, 1, 1));
            buttons = new JButton[taille][taille];
            Border thinBorder = BorderFactory.createLineBorder(Color.LIGHT_GRAY);

            for (int i = 0; i < taille; i++) {
                for (int j = 0; j < taille; j++) {
                    buttons[i][j] = new JButton();
                    buttons[i][j].setFont(new Font("Arial", Font.BOLD, Math.max(10, 200 / taille))); 
                    buttons[i][j].setFocusable(false);
                    buttons[i][j].setBorder(thinBorder);
                    buttons[i][j].setBackground(Color.CYAN.darker());
                    buttons[i][j].setOpaque(true);
                    buttons[i][j].setPreferredSize(new Dimension(Math.max(30,350/taille), Math.max(30,350/taille))); 
                    if (!isMyBoard) { 
                        buttons[i][j].setText(String.valueOf(ModelConstants.OPP_UNKNOWN));
                    } else {
                        buttons[i][j].setText(String.valueOf(ModelConstants.MY_EMPTY));
                    }


                    final int r = i;
                    final int c = j;

                    buttons[i][j].addActionListener(e -> {
                        if (amISpectator) return; 

                        if (isMyBoard && placementPhase && currentShipToPlace != null && BatailleNavaleClient.this.inGame) { 
                            System.out.println("Placement demandé pour " + currentShipToPlace.getNom() + " en " + r + "," + c + " H:" + horizontalRadioButton.isSelected());
                            out.println("PLACE_SHIP:" + currentShipToPlace.name() + ":" + r + ":" + c + ":" + horizontalRadioButton.isSelected());
                        } else if (!isMyBoard && myTurn && BatailleNavaleClient.this.inGame) { 
                            System.out.println("Tir demandé sur joueur " + this.boardOwnerPlayerIndex + " en " + r + "," + c);
                            out.println("FIRE_SHOT:" + this.boardOwnerPlayerIndex + ":" + r + ":" + c); 
                        }
                    });
                    
                    if (isMyBoard) {
                        buttons[i][j].addMouseListener(new java.awt.event.MouseAdapter() {
                            public void mouseEntered(java.awt.event.MouseEvent evt) {
                                if (placementModeActive && currentShipToPlace != null && horizontalRadioButton != null && !amISpectator) { 
                                    previewStartCell = new Point(r,c);
                                    previewHorizontal = horizontalRadioButton.isSelected();
                                    shipToPlaceForPreview = currentShipToPlace;
                                    repaint(); 
                                }
                            }
                            public void mouseExited(java.awt.event.MouseEvent evt) {
                                if (placementModeActive && currentShipToPlace != null && !amISpectator) {
                                    previewStartCell = null;
                                    repaint();
                                }
                            }
                        });
                    }
                    add(buttons[i][j]);
                }
            }
        }
        
        public void setCurrentShipForPlacement(ModelConstants.ShipType shipType, boolean horizontal) {
            this.shipToPlaceForPreview = shipType;
            this.previewHorizontal = horizontal; 
            this.placementModeActive = (shipType != null);
            repaint(); 
        }
        
        public void setPlacementMode(boolean mode) {
            this.placementModeActive = mode;
            if (!mode) { 
                previewStartCell = null;
                shipToPlaceForPreview = null;
                repaint();
            }
        }

        public void confirmShipPlacement(ModelConstants.ShipType type, int r, int c, boolean horizontal) {
            for (int i = 0; i < type.getTaille(); i++) {
                int currentL = r;
                int currentC = c;
                if (horizontal) {
                    currentC += i;
                } else {
                    currentL += i;
                }
                if (currentL >= 0 && currentL < taille && currentC >= 0 && currentC < taille) { 
                    buttons[currentL][currentC].setBackground(Color.DARK_GRAY); 
                    buttons[currentL][currentC].setText(String.valueOf(type.getSpriteChar())); 
                    buttons[currentL][currentC].setForeground(Color.WHITE); 
                    buttons[currentL][currentC].setEnabled(false); 
                }
            }
            previewStartCell = null; 
            shipToPlaceForPreview = null;
            repaint();
        }


        public void markShot(int r, int c, ModelConstants.ShotResult result) { 
            if (r < 0 || r >= taille || c < 0 || c >= taille) return;
            
            JButton button = buttons[r][c];
            button.setIcon(null); 
            switch (result) {
                case TOUCHE:
                    button.setText(String.valueOf(isMyBoard ? ModelConstants.MY_HIT_ON_ME : ModelConstants.OPP_HIT)); 
                    button.setBackground(isMyBoard ? Color.PINK : Color.ORANGE); 
                    button.setForeground(Color.BLACK);
                    button.setEnabled(false);
                    break;
                case COULE:
                    button.setText(String.valueOf(ModelConstants.OPP_SUNK)); 
                    button.setBackground(Color.RED); 
                    button.setForeground(Color.WHITE);
                    button.setEnabled(false);
                    break;
                case MANQUE:
                    button.setText(String.valueOf(isMyBoard ? ModelConstants.MY_MISS_ON_ME : ModelConstants.OPP_MISS));
                    button.setBackground(isMyBoard ? Color.BLUE.brighter() : Color.LIGHT_GRAY); 
                    button.setForeground(Color.BLACK);
                    button.setEnabled(false);
                    break;
                case DEJA_JOUE:
                    break;
                case ERREUR:
                    break;
            }
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (placementModeActive && shipToPlaceForPreview != null && previewStartCell != null && !amISpectator) { 
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setColor(new Color(100, 100, 100, 120)); 

                if (buttons[0][0] == null) return; 
                int cellWidth = buttons[0][0].getWidth();
                int cellHeight = buttons[0][0].getHeight();
                if (cellWidth <= 0 || cellHeight <= 0) return; 

                for (int i = 0; i < shipToPlaceForPreview.getTaille(); i++) {
                    int currentR_preview = previewStartCell.x;
                    int currentC_preview = previewStartCell.y;

                    if (previewHorizontal) { 
                        currentC_preview += i;
                    } else {
                        currentR_preview += i;
                    }

                    if (currentR_preview < taille && currentC_preview < taille && currentR_preview >= 0 && currentC_preview >=0 ) {
                        if (buttons[currentR_preview][currentC_preview].getBackground() != Color.DARK_GRAY) { 
                            Point buttonLocation = buttons[currentR_preview][currentC_preview].getLocation();
                            g2d.fillRect(buttonLocation.x, buttonLocation.y, cellWidth, cellHeight);
                        }
                    }
                }
                g2d.dispose();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (amISpectator && !isMyBoard) { 
                for (int i = 0; i < taille; i++) {
                    for (int j = 0; j < taille; j++) {
                        buttons[i][j].setEnabled(false);
                    }
                }
                return;
            }

            for (int i = 0; i < taille; i++) {
                for (int j = 0; j < taille; j++) {
                    String currentText = buttons[i][j].getText();
                    boolean isEmptyOrUnknown = currentText == null || 
                                               currentText.isEmpty() || 
                                               currentText.equals(String.valueOf(ModelConstants.OPP_UNKNOWN)) ||
                                               currentText.equals(String.valueOf(ModelConstants.MY_EMPTY));
                    
                    boolean isNotMyPlacedShip = !(isMyBoard && buttons[i][j].getBackground() == Color.DARK_GRAY);

                    if (isEmptyOrUnknown && isNotMyPlacedShip) {
                         buttons[i][j].setEnabled(enabled);
                    } else {
                         buttons[i][j].setEnabled(false); 
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BatailleNavaleClient::new);
    }
}
