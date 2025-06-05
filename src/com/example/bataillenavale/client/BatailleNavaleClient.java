package com.example.bataillenavale.client;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
// BufferedImage, MalformedURLException, URL non utilisés directement ici, peuvent être retirés si plus besoin
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
// import java.util.stream.Collectors; // Pas directement utilisé ici après refactorisation

public class BatailleNavaleClient extends JFrame {

    // Énumérations et constantes du modèle (inchangées)
    public static class ModelConstants {
        public enum ShipType {
            PORTE_AVIONS("Porte-avions", 5, 'P'), CROISEUR("Croiseur", 4, 'C'),
            CONTRE_TORPILLEUR("Contre-torpilleur", 3, 'T'), SOUS_MARIN("Sous-marin", 3, 'S'),
            TORPILLEUR("Torpilleur", 2, 'R');
            private final String nom; private final int taille; private final char spriteChar;
            ShipType(String n, int t, char s) { nom=n; taille=t; spriteChar=s; }
            public String getNom() { return nom; } public int getTaille() { return taille; } public char getSpriteChar() { return spriteChar; }
        }
        public enum ShotResult { MANQUE, TOUCHE, COULE, DEJA_JOUE, ERREUR }
        public static final int TAILLE_GRILLE = 10;
        public static final char OPP_UNKNOWN = '?'; public static final char OPP_MISS = 'O';
        public static final char OPP_HIT = 'X'; public static final char OPP_SUNK = '!';
        public static final char MY_EMPTY = '~'; public static final char MY_HIT_ON_ME = 'X';
        public static final char MY_MISS_ON_ME = 'M';
    }

    private ServerCommunicator serverCommunicator;

    // UI Panels principaux
    private JPanel connectionPanel;
    private JPanel mainGamePanel;     // Contient le lobby ou les grilles de jeu
    private JPanel lobbyPanelContainer; // Pour le contenu du lobby
    private JPanel eastSidePanel;     // Contient chat et log

    // UI Components spécifiques
    private JLabel statusLabel;
    private JButton connectButton;
    private JTextField nameField, serverIpField;
    private JTextArea lobbyArea;
    private JButton hostStartGameButton;
    private JButton quitGameButton;

    // UI Panels extraits
    private ChatUI chatUI;
    private GameLogUI gameLogUI;

    // Grilles de jeu et contrôles de placement
    private GridPanel myGridPanel;
    private JPanel gameGridsDisplayPanel; // Conteneur pour les GridPanel (ma grille, adversaires)
    private Map<Integer, GridPanel> playerGridPanels = new HashMap<>();
    private JPanel placementControlsPanel;
    private JRadioButton horizontalRadioButton, verticalRadioButton;
    private JLabel shipToPlaceLabel;

    // État du jeu
    private String serverAddress = "127.0.0.1";
    private int serverPort = 12347;
    private String playerName = "Joueur";
    private int playerIndex = -1;
    private boolean myTurn = false;
    private boolean placementPhase = false;
    private ModelConstants.ShipType currentShipToPlace = null;
    private boolean inGame = false;
    private boolean amISpectator = false;
    private int totalPlayersInGame = 0;
    private Map<Integer, String> allPlayerNames = new HashMap<>();
    private int minPlayersToStartLobby = 2;
    private boolean nameSuccessfullySetThisSession = false;


    public BatailleNavaleClient() {
        setTitle("Bataille Navale Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(10,10,10,10));

        initComponents(); // Initialiser les composants graphiques
        initServerCommunicator(); // Initialiser le communicateur réseau

        switchToView("CONNECTION"); // Afficher la vue de connexion initiale

        pack();
        setSize(getWidth() + 260, Math.max(700, getHeight())); // Ajuster la taille pour eastSidePanel
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void initServerCommunicator() {
        serverCommunicator = new ServerCommunicator(
            this::handleServerMessageRaw, // Méthode pour traiter les messages bruts du serveur
            this::handleServerDisconnect,   // Méthode pour gérer la déconnexion
            this::handleConnectionError   // Méthode pour gérer les erreurs de connexion initiales
        );
    }

    private void initComponents() {
        // Panneau de Connexion
        connectionPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbcConn = new GridBagConstraints();
        gbcConn.insets = new Insets(5,5,5,5); gbcConn.fill = GridBagConstraints.HORIZONTAL;
        gbcConn.gridx = 0; gbcConn.gridy = 0; connectionPanel.add(new JLabel("Serveur IP:"), gbcConn);
        serverIpField = new JTextField(serverAddress, 15);
        gbcConn.gridx = 1; gbcConn.gridy = 0; connectionPanel.add(serverIpField, gbcConn);
        gbcConn.gridx = 0; gbcConn.gridy = 1; connectionPanel.add(new JLabel("Votre Nom (max 15 car.):"), gbcConn);
        nameField = new JTextField(playerName, 15);
        gbcConn.gridx = 1; gbcConn.gridy = 1; connectionPanel.add(nameField, gbcConn);
        connectButton = new JButton("Se Connecter et Rejoindre");
        gbcConn.gridx = 0; gbcConn.gridy = 2; gbcConn.gridwidth = 2; gbcConn.insets = new Insets(10,5,5,5);
        connectionPanel.add(connectButton, gbcConn);
        connectButton.addActionListener(e -> connectAndSetName());

        // Panneau principal du jeu (contiendra lobby ou grilles)
        mainGamePanel = new JPanel(new BorderLayout(10,10));

        // Conteneur du Lobby
        lobbyPanelContainer = new JPanel(new BorderLayout(5,5));
        lobbyArea = new JTextArea(8, 40); lobbyArea.setEditable(false); lobbyArea.setLineWrap(true); lobbyArea.setWrapStyleWord(true);
        lobbyPanelContainer.add(new JScrollPane(lobbyArea), BorderLayout.CENTER);
        hostStartGameButton = new JButton("Démarrer la Partie (Hôte)"); hostStartGameButton.setVisible(false);
        hostStartGameButton.addActionListener(e -> {
            if (serverCommunicator.isConnected() && nameSuccessfullySetThisSession) {
                serverCommunicator.sendMessage("ADMIN_START_GAME");
            }
        });
        lobbyPanelContainer.add(hostStartGameButton, BorderLayout.SOUTH);

        // Label de statut global
        statusLabel = new JLabel("Bienvenue! Entrez l'IP du serveur, votre nom, et connectez-vous.");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        // Bouton Quitter global
        quitGameButton = new JButton("Quitter"); quitGameButton.setVisible(false);
        quitGameButton.addActionListener(e -> {
            if (serverCommunicator.isConnected()) {
                serverCommunicator.sendMessage("QUIT_GAME");
            }
            // La déconnexion et le retour à l'écran de connexion seront gérés par les callbacks du ServerCommunicator
        });

        // Panneaux Chat et Log (utilisant les nouvelles classes UI)
        chatUI = new ChatUI();
        gameLogUI = new GameLogUI();
        chatUI.addSendButtonListener(e -> sendChatMessageAction());
        chatUI.addInputFieldEnterListener(e -> sendChatMessageAction()); // Envoi sur Entrée

        eastSidePanel = new JPanel(new GridLayout(2,1,5,5));
        eastSidePanel.add(chatUI);
        eastSidePanel.add(gameLogUI);
        add(eastSidePanel, BorderLayout.EAST);
    }

    private void sendChatMessageAction() {
        String message = chatUI.getChatMessage(); // Récupère et vide le champ
        if (!message.isEmpty() && serverCommunicator.isConnected() && nameSuccessfullySetThisSession) {
            serverCommunicator.sendMessage("CHAT_MSG:" + message);
        } else if (!nameSuccessfullySetThisSession) {
            statusLabel.setText("Erreur: Définissez votre nom pour utiliser le chat.");
        }
    }

    private void connectAndSetName() {
        serverAddress = serverIpField.getText().trim();
        String tempPlayerName = nameField.getText().trim();

        if (serverAddress.isEmpty()) { statusLabel.setText("L'adresse IP du serveur ne peut pas être vide."); return; }
        if (tempPlayerName.isEmpty()) { statusLabel.setText("Votre nom ne peut pas être vide."); return; }
        if (tempPlayerName.length() > 15) { statusLabel.setText("Erreur: Le nom ne doit pas dépasser 15 caractères."); return; }
        
        this.playerName = tempPlayerName; // Assigner après validation

        statusLabel.setText("Connexion à " + serverAddress + "...");
        connectButton.setEnabled(false);
        serverIpField.setEditable(false);
        nameField.setEditable(false);

        boolean success = serverCommunicator.connect(serverAddress, serverPort, this.playerName);
        if (success) {
            statusLabel.setText("Connecté. Envoi du nom au serveur...");
            serverCommunicator.sendMessage("SET_NAME:" + this.playerName);
            // nameSuccessfullySetThisSession sera mis à true par le serveur implicitement ou via un message
        } else {
            // L'erreur est déjà affichée par le callback onConnectionError
            connectButton.setEnabled(true);
            serverIpField.setEditable(true);
            nameField.setEditable(true);
        }
    }

    // Callback pour les messages bruts du serveur
    private void handleServerMessageRaw(String rawMessage) {
        System.out.println("Serveur (brut): " + rawMessage); // Debug
        String[] parts = rawMessage.split(":", 2);
        String command = parts[0];
        String payload = (parts.length > 1) ? parts[1] : "";
        processServerMessage(command, payload); // L'ancienne méthode de traitement
    }

    // Callback pour la déconnexion
    private void handleServerDisconnect() {
        statusLabel.setText("Déconnecté du serveur. Veuillez vous reconnecter.");
        addGameLog("Déconnexion du serveur.");
        resetClientStateForNewConnection();
        switchToView("CONNECTION");
    }

    // Callback pour les erreurs de connexion initiales
    private void handleConnectionError(String message, Exception ex) {
        statusLabel.setText("Erreur de connexion: " + message);
        addGameLog("Erreur de connexion: " + message + (ex != null ? " (" + ex.getMessage() + ")" : ""));
        connectButton.setEnabled(true);
        serverIpField.setEditable(true);
        nameField.setEditable(true);
        nameSuccessfullySetThisSession = false;
    }
    
    private void resetClientStateForNewConnection() {
        nameSuccessfullySetThisSession = false;
        inGame = false;
        amISpectator = false;
        placementPhase = false;
        myTurn = false;
        playerIndex = -1;
        playerGridPanels.clear();
        myGridPanel = null;
        gameGridsDisplayPanel = null;
        allPlayerNames.clear();
        currentShipToPlace = null;
        // Les UI sont remises à zéro dans switchToView("CONNECTION")
    }


    private void switchToView(String viewName) {
        // Cacher tous les panneaux principaux
        if (connectionPanel.getParent() == getContentPane()) getContentPane().remove(connectionPanel);
        if (mainGamePanel.getParent() == getContentPane()) getContentPane().remove(mainGamePanel);

        connectionPanel.setVisible(false);
        mainGamePanel.setVisible(false);
        lobbyPanelContainer.setVisible(false);
        eastSidePanel.setVisible(false);


        switch (viewName) {
            case "CONNECTION":
                if (connectionPanel.getParent() == null) add(connectionPanel, BorderLayout.NORTH);
                connectionPanel.setVisible(true);
                if (nameField != null) { nameField.setText(this.playerName); nameField.setEditable(true); }
                if (serverIpField != null) serverIpField.setEditable(true);
                if (connectButton != null) connectButton.setEnabled(true);
                quitGameButton.setVisible(false); hostStartGameButton.setVisible(false);
                
                // Nettoyer l'état pour une nouvelle session de connexion
                playerIndex = -1; inGame = false; placementPhase = false; amISpectator = false;
                // nameSuccessfullySetThisSession est géré par la logique de connexion/déconnexion
                if (gameLogUI != null) gameLogUI.clearLog();
                if (chatUI != null) chatUI.clearChat();
                break;
            case "LOBBY":
                if (mainGamePanel.getParent() == null) add(mainGamePanel, BorderLayout.CENTER);
                mainGamePanel.removeAll();
                mainGamePanel.add(lobbyPanelContainer, BorderLayout.CENTER); // lobbyPanelContainer contient déjà le bouton quitter
                mainGamePanel.setVisible(true); lobbyPanelContainer.setVisible(true);
                // Le bouton Quitter est global, il sera affiché si mainGamePanel est visible et configuré
                quitGameButton.setText("Quitter le Lobby"); quitGameButton.setVisible(true);
                eastSidePanel.setVisible(true);
                break;
            case "PLACEMENT":
            case "COMBAT":
            case "SPECTATOR":
                if (mainGamePanel.getParent() == null) add(mainGamePanel, BorderLayout.CENTER);
                // Le contenu de mainGamePanel (grilles, contrôles) est géré par
                // switchToPlacementView ou switchToGameOrSpectatorView
                mainGamePanel.setVisible(true);
                quitGameButton.setText(amISpectator ? "Quitter (Spectateur)" : "Quitter la Partie");
                quitGameButton.setVisible(true);
                hostStartGameButton.setVisible(false);
                eastSidePanel.setVisible(true);
                break;
        }
        // Ajouter le bouton Quitter s'il est pertinent pour la vue (pas CONNECTION)
        if (!viewName.equals("CONNECTION")) {
            // Vérifier si mainGamePanel a un BorderLayout pour y ajouter le bouton Quitter en SOUTH
            // Ou le laisser global et gérer sa visibilité.
            // Pour l'instant, on le laisse global, il sera sous statusLabel.
        }

        getContentPane().revalidate();
        getContentPane().repaint();
    }

    private void switchToLobbyView(String lobbyMessage, int namedPlayerCount, int minPlayers, String firstPlayerNameInLobby) {
        inGame = false; placementPhase = false; amISpectator = false;
        lobbyArea.setText(lobbyMessage);
        boolean amIHost = nameSuccessfullySetThisSession && this.playerName.equals(firstPlayerNameInLobby) && !firstPlayerNameInLobby.isEmpty();
        hostStartGameButton.setVisible(amIHost);
        hostStartGameButton.setEnabled(amIHost && namedPlayerCount >= minPlayers);
        switchToView("LOBBY");
        statusLabel.setText("Dans le lobby. En attente du démarrage de la partie...");
    }

    private void setupPlayerGridsForGameView() {
        gameGridsDisplayPanel = new JPanel();
        playerGridPanels.clear();

        if (amISpectator) {
            int numPlayersToDisplay = totalPlayersInGame;
            if (numPlayersToDisplay <= 0) { gameGridsDisplayPanel.add(new JLabel("Aucun joueur à observer.")); return; }
            if (numPlayersToDisplay == 1) gameGridsDisplayPanel.setLayout(new GridLayout(1, 1, 10, 5));
            else if (numPlayersToDisplay == 2) gameGridsDisplayPanel.setLayout(new GridLayout(1, 2, 10, 5));
            else if (numPlayersToDisplay <= 4) gameGridsDisplayPanel.setLayout(new GridLayout(2, 2, 10, 5));
            else if (numPlayersToDisplay <= 6) gameGridsDisplayPanel.setLayout(new GridLayout(2, 3, 10, 5));
            else gameGridsDisplayPanel.setLayout(new GridLayout(3, 3, 10, 5));

            for (int i = 0; i < totalPlayersInGame; i++) {
                JPanel spgv = new JPanel(new BorderLayout(0,3)); String n = allPlayerNames.getOrDefault(i, "Joueur " + (i+1));
                spgv.setBorder(BorderFactory.createTitledBorder(n));
                GridPanel pg = new GridPanel(ModelConstants.TAILLE_GRILLE, false, i); pg.setEnabled(false);
                playerGridPanels.put(i, pg); spgv.add(new JScrollPane(pg), BorderLayout.CENTER); gameGridsDisplayPanel.add(spgv);
            }
        } else { // Mode Joueur
            myGridPanel = new GridPanel(ModelConstants.TAILLE_GRILLE, true, playerIndex);
            playerGridPanels.put(playerIndex, myGridPanel);
            JPanel myGC = new JPanel(new BorderLayout()); myGC.setBorder(BorderFactory.createTitledBorder(this.playerName + " (Votre Grille)"));
            myGC.add(new JScrollPane(myGridPanel), BorderLayout.CENTER);

            JPanel oppC = new JPanel(); int numOpp = totalPlayersInGame - 1;
            if (numOpp > 0) {
                if (numOpp == 1) oppC.setLayout(new GridLayout(1, 1, 5, 5)); else if (numOpp == 2) oppC.setLayout(new GridLayout(1, 2, 5, 5));
                else if (numOpp <= 4) oppC.setLayout(new GridLayout(2, 2, 5, 5)); else oppC.setLayout(new GridLayout(2, 3, 5, 5));
                for (int i = 0; i < totalPlayersInGame; i++) {
                    if (i == playerIndex) continue;
                    JPanel sop = new JPanel(new BorderLayout(0,3)); String n = allPlayerNames.getOrDefault(i, "Adversaire " + (i+1));
                    sop.setBorder(BorderFactory.createTitledBorder("Tirer sur: " + n));
                    GridPanel og = new GridPanel(ModelConstants.TAILLE_GRILLE, false, i);
                    playerGridPanels.put(i, og); sop.add(new JScrollPane(og), BorderLayout.CENTER); oppC.add(sop);
                }
            }
            gameGridsDisplayPanel.setLayout(new BorderLayout(10,10));
            gameGridsDisplayPanel.add(myGC, BorderLayout.WEST);
            if (numOpp > 0) gameGridsDisplayPanel.add(new JScrollPane(oppC), BorderLayout.CENTER);
            else gameGridsDisplayPanel.add(new JPanel(), BorderLayout.CENTER);
        }
    }

    private void switchToPlacementView() {
        placementPhase = true; inGame = true; amISpectator = false;
        setupPlayerGridsForGameView(); // Crée/Recrée myGridPanel et gameGridsDisplayPanel

        if (myGridPanel != null) { myGridPanel.setEnabled(true); myGridPanel.setPlacementMode(true); }
        for(Map.Entry<Integer, GridPanel> entry : playerGridPanels.entrySet()){
            if (entry.getKey() != playerIndex && entry.getValue() != null) entry.getValue().setEnabled(false);
        }

        placementControlsPanel = new JPanel(new FlowLayout());
        shipToPlaceLabel = new JLabel("Navire à placer: "); placementControlsPanel.add(shipToPlaceLabel);
        horizontalRadioButton = new JRadioButton("Horizontal", true); verticalRadioButton = new JRadioButton("Vertical");
        ButtonGroup og = new ButtonGroup(); og.add(horizontalRadioButton); og.add(verticalRadioButton);
        placementControlsPanel.add(horizontalRadioButton); placementControlsPanel.add(verticalRadioButton);
        ActionListener ol = e -> { if (myGridPanel != null && placementPhase && currentShipToPlace != null) {
            myGridPanel.setCurrentShipForPlacement(currentShipToPlace, horizontalRadioButton.isSelected());
            if (myGridPanel.previewStartCell != null) myGridPanel.repaint(); }};
        horizontalRadioButton.addActionListener(ol); verticalRadioButton.addActionListener(ol);

        mainGamePanel.removeAll();
        if (gameGridsDisplayPanel != null) mainGamePanel.add(new JScrollPane(gameGridsDisplayPanel), BorderLayout.CENTER);
        mainGamePanel.add(placementControlsPanel, BorderLayout.SOUTH);
        switchToView("PLACEMENT");
        statusLabel.setText("Phase de placement des navires."); quitGameButton.setText("Quitter la Partie");
    }

    private void switchToGameOrSpectatorView() {
        placementPhase = false;
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); bottomPanel.add(quitGameButton);

        if (amISpectator) {
            if (gameGridsDisplayPanel == null || playerGridPanels.isEmpty() || !mainGamePanel.isAncestorOf(gameGridsDisplayPanel) ) {
                setupPlayerGridsForGameView();
            }
            for(GridPanel pGrid : playerGridPanels.values()){ if (pGrid != null) { pGrid.setPlacementMode(false); pGrid.setEnabled(false);}}
            statusLabel.setText("Mode Spectateur - Phase de combat.");
        } else { // Joueur
            if (myGridPanel == null || !playerGridPanels.containsKey(playerIndex) || gameGridsDisplayPanel == null) {
                System.err.println("ERREUR CRITIQUE: Grilles du joueur non initialisées pour la phase de combat!");
                addGameLog("ERREUR CRITIQUE: Grilles non initialisées pour phase de combat."); statusLabel.setText("Erreur critique - Grilles non trouvées.");
                return;
            }
            myGridPanel.setPlacementMode(false); myGridPanel.setEnabled(false);
            for(Map.Entry<Integer, GridPanel> entry : playerGridPanels.entrySet()){
                if (entry.getKey() != playerIndex) { GridPanel oppGrid = entry.getValue(); if (oppGrid != null) {
                    oppGrid.setPlacementMode(false); oppGrid.setEnabled(myTurn); }}
            }
            statusLabel.setText("Phase de combat !");
        }

        mainGamePanel.removeAll();
        if (gameGridsDisplayPanel != null) mainGamePanel.add(new JScrollPane(gameGridsDisplayPanel), BorderLayout.CENTER);
        else { mainGamePanel.add(new JLabel("Erreur d'affichage des grilles."), BorderLayout.CENTER); }
        mainGamePanel.add(bottomPanel, BorderLayout.SOUTH);
        switchToView(amISpectator ? "SPECTATOR" : "COMBAT");
        quitGameButton.setText(amISpectator ? "Quitter (Spectateur)" : "Quitter la Partie");
    }

    // La méthode processServerMessage reste globalement la même, mais utilisera
    // chatUI.appendMessage(...) et gameLogUI.addLogEntry(...)
    private void processServerMessage(String command, String payload) {
        // ... (début de la méthode)
        switch (command) {
            case "REQ_NAME":
                statusLabel.setText("Le serveur demande votre nom.");
                gameLogUI.addLogEntry("Le serveur demande la saisie du nom."); // Modifié
                nameSuccessfullySetThisSession = false; playerIndex = -1; inGame = false; placementPhase = false; amISpectator = false;
                switchToView("CONNECTION");
                if (nameField != null) nameField.setEditable(true);
                if (connectButton != null) connectButton.setEnabled(true);
                break;
            case "LOBBY_STATE":
                String[] lobbyData = payload.split(":", 4);
                if (lobbyData.length >= 3) {
                    int namedPlayerCount = Integer.parseInt(lobbyData[0]);
                    minPlayersToStartLobby = Integer.parseInt(lobbyData[1]);
                    String playerNamesList = (lobbyData.length > 3) ? lobbyData[3] : "";
                    String lobbyText = "Joueurs prêts: " + namedPlayerCount + " (Min: " + minPlayersToStartLobby + ")\nNoms: " + (playerNamesList.isEmpty() ? "(aucun)" : playerNamesList);
                    String firstPlayerName = ""; boolean myNameConfirmed = false;
                    if(!playerNamesList.isEmpty()){ String[] names = playerNamesList.split(","); if (names.length > 0) firstPlayerName = names[0].trim();
                        for(String n : names) if(n.trim().equals(this.playerName)) { myNameConfirmed = true; nameSuccessfullySetThisSession = true; break; }}
                    if (!amISpectator) { if (myNameConfirmed || nameSuccessfullySetThisSession) switchToLobbyView(lobbyText, namedPlayerCount, minPlayersToStartLobby, firstPlayerName);
                        else if (connectionPanel.isVisible()) statusLabel.setText("Lobby: " + namedPlayerCount + " joueurs. Connectez-vous.");
                    } else gameLogUI.addLogEntry("Info Lobby (spectateur): " + lobbyText); // Modifié
                } else { statusLabel.setText("Erreur: LOBBY_STATE malformé."); gameLogUI.addLogEntry("Erreur: LOBBY_STATE malformé: " + payload); } // Modifié
                break;
            case "SPECTATE_MODE":
                amISpectator = true; statusLabel.setText("Mode Spectateur activé..."); gameLogUI.addLogEntry("Activation du mode spectateur."); // Modifié
                break;
            case "SPECTATE_INFO":
                if (!amISpectator) break; String[] si = payload.split(":", 3);
                if(si.length >= 3) { totalPlayersInGame = Integer.parseInt(si[1]); String[] sn = si[2].split(","); allPlayerNames.clear();
                    for(int i=0; i < sn.length; i++) allPlayerNames.put(i, sn[i].trim());
                    String sm = "Spectateur: " + totalPlayersInGame + " joueurs: " + si[2]; statusLabel.setText(sm); gameLogUI.addLogEntry(sm); // Modifié
                    switchToGameOrSpectatorView();
                } else { statusLabel.setText("Erreur: SPECTATE_INFO malformé."); gameLogUI.addLogEntry("Erreur: SPECTATE_INFO malformé: " + payload); } // Modifié
                break;
            case "GAME_START":
                String[] gi = payload.split(":", 4);
                if (gi.length >= 4) { playerIndex = Integer.parseInt(gi[1]); totalPlayersInGame = Integer.parseInt(gi[2]); String[] n = gi[3].split(","); allPlayerNames.clear();
                    for(int i=0; i < n.length; i++) allPlayerNames.put(i, n[i].trim()); if (allPlayerNames.containsKey(playerIndex)) playerName = allPlayerNames.get(playerIndex);
                    nameSuccessfullySetThisSession = true; amISpectator = false;
                    String gsm = "Partie commence ! Vous: " + playerName + " (J" + playerIndex + "). Placement."; statusLabel.setText(gsm); gameLogUI.addLogEntry(gsm); // Modifié
                    switchToPlacementView();
                } else { statusLabel.setText("Erreur: GAME_START malformé."); gameLogUI.addLogEntry("Erreur: GAME_START malformé: " + payload); } // Modifié
                break;
            case "YOUR_TURN_PLACE_SHIP":
                if (amISpectator) break; Toolkit.getDefaultToolkit().beep(); String[] shi = payload.split(":");
                if (shi.length >= 3) { currentShipToPlace = ModelConstants.ShipType.valueOf(shi[0]); currentShipSize = Integer.parseInt(shi[1]); currentShipDisplayName = shi[2];
                    if (shipToPlaceLabel != null) shipToPlaceLabel.setText("Placez: " + currentShipDisplayName + " (taille " + currentShipSize + ")");
                    String ptm = allPlayerNames.getOrDefault(playerIndex, "Vous") + ", placez: " + currentShipDisplayName; statusLabel.setText(ptm); gameLogUI.addLogEntry("Placement: " + ptm); // Modifié
                    if (myGridPanel != null) { myGridPanel.setEnabled(true); myGridPanel.setPlacementMode(true); myGridPanel.setCurrentShipForPlacement(currentShipToPlace, horizontalRadioButton.isSelected()); }
                } else { statusLabel.setText("Erreur: YOUR_TURN_PLACE_SHIP malformé."); gameLogUI.addLogEntry("Erreur: YOUR_TURN_PLACE_SHIP malformé: " + payload); } // Modifié
                break;
            case "WAIT_PLACEMENT":
                 String wm; if (amISpectator) { String[] wsi = payload.split(":"); wm = (wsi.length >=2) ? "Spectateur: " + wsi[0] + " place " + wsi[1] : "Spectateur: Attente placement..."; statusLabel.setText(wm); gameLogUI.addLogEntry(wm); break; } // Modifié
                 String[] wi = payload.split(":"); wm = (wi.length >= 2) ? "Attente: " + wi[0] + " place " + wi[1] : "Attente placement adversaire..."; statusLabel.setText(wm); gameLogUI.addLogEntry("Placement: " + wm); // Modifié
                 if (myGridPanel != null) { myGridPanel.setEnabled(false); myGridPanel.setPlacementMode(false); }
                 break;
            case "PLACEMENT_ACCEPTED":
                 if (amISpectator) break; String[] ai = payload.split(":");
                 if (ai.length >= 4) { ModelConstants.ShipType pt = ModelConstants.ShipType.valueOf(ai[0]); int pl = Integer.parseInt(ai[1]); int pc = Integer.parseInt(ai[2]); boolean ph = Boolean.parseBoolean(ai[3]);
                    if (myGridPanel != null) myGridPanel.confirmShipPlacement(pt, pl, pc, ph);
                    String pam = pt.getNom() + " placé par " + allPlayerNames.getOrDefault(playerIndex, "vous") + "."; statusLabel.setText(pam); gameLogUI.addLogEntry("Placement: " + pam); // Modifié
                    currentShipToPlace = null; if(myGridPanel != null) myGridPanel.setPlacementMode(false);
                 } else { statusLabel.setText("Erreur: PLACEMENT_ACCEPTED malformé."); gameLogUI.addLogEntry("Erreur: PLACEMENT_ACCEPTED malformé: " + payload); } // Modifié
                 break;
            case "PLACEMENT_REJECTED":
                 if (amISpectator) break; String prm = "Placement de " + payload + " refusé par " + allPlayerNames.getOrDefault(playerIndex, "vous") + ". Réessayez."; statusLabel.setText(prm); gameLogUI.addLogEntry("Placement: " + prm); // Modifié
                 if (myGridPanel != null && currentShipToPlace != null) { myGridPanel.setPlacementMode(true); myGridPanel.setCurrentShipForPlacement(currentShipToPlace, horizontalRadioButton.isSelected());}
                 break;
            case "ALL_SHIPS_PLACED":
                statusLabel.setText("Navires placés. Début du combat !"); gameLogUI.addLogEntry("Navires placés. Phase de combat !"); // Modifié
                switchToGameOrSpectatorView();
                break;
            case "YOUR_TURN_FIRE":
                if (amISpectator) break; Toolkit.getDefaultToolkit().beep(); myTurn = true;
                String ftm = allPlayerNames.getOrDefault(playerIndex, "Vous") + ", à vous de tirer !"; statusLabel.setText(ftm); gameLogUI.addLogEntry("Combat: " + ftm); // Modifié
                for(Map.Entry<Integer, GridPanel> entry : playerGridPanels.entrySet()){ if (entry.getKey() != playerIndex && entry.getValue() != null) entry.getValue().setEnabled(true);
                    else if (entry.getValue() != null) entry.getValue().setEnabled(false); }
                break;
            case "OPPONENT_TURN_FIRE":
                String otm = "Au tour de " + payload + " de tirer."; statusLabel.setText(otm); gameLogUI.addLogEntry("Combat: " + otm + (amISpectator ? " (Spectateur)" : "")); // Modifié
                if (!amISpectator) { myTurn = false; for(Map.Entry<Integer, GridPanel> entry : playerGridPanels.entrySet()){ if (entry.getKey() != playerIndex && entry.getValue() != null) entry.getValue().setEnabled(false);}}
                break;
            case "SHOT_RESULT":
                String[] sd = payload.split(":", 6);
                if (sd.length >= 5) { int ti = Integer.parseInt(sd[0]); int ci = Integer.parseInt(sd[1]); int l = Integer.parseInt(sd[2]); int c = Integer.parseInt(sd[3]); ModelConstants.ShotResult r = ModelConstants.ShotResult.valueOf(sd[4]);
                    String nsc = (sd.length > 5 && !sd[5].equals("UNKNOWN_SHIP")) ? sd[5] : null; String tName = allPlayerNames.getOrDefault(ti, "J" + (ti+1)); String cName = allPlayerNames.getOrDefault(ci, "J" + (ci+1));
                    GridPanel gtu = playerGridPanels.get(ci); if (gtu != null) gtu.markShot(l, c, r);
                    String srm = "Tir de " + tName + " sur " + cName + " en " + (char)('A'+c) + (l+1) + " -> " + r + (nsc != null ? " (" + nsc + " COULÉ!)" : ""); statusLabel.setText(srm); gameLogUI.addLogEntry(srm); // Modifié
                } else { statusLabel.setText("Erreur: SHOT_RESULT malformé."); gameLogUI.addLogEntry("Erreur: SHOT_RESULT malformé: " + payload); } // Modifié
                break;
            case "GAME_OVER":
                String winMsg; if (payload.equals("DRAW") || payload.equals("GAME_OVER_DRAW")) winMsg = "PARTIE TERMINÉE! Match Nul."; else { String[] goi = payload.split(":",2); winMsg = "PARTIE TERMINÉE! Gagnant: " + goi[0];}
                JOptionPane.showMessageDialog(this, winMsg, "Fin de Partie", JOptionPane.INFORMATION_MESSAGE); statusLabel.setText(winMsg + ". Retour au lobby..."); gameLogUI.addLogEntry(winMsg + ". Retour au lobby."); // Modifié
                myTurn = false; inGame = false;
                break;
            case "GAME_OVER_DISCONNECT":
                String dPlayer = payload; String dMsg = "PARTIE TERMINÉE: " + dPlayer + " déconnecté.";
                JOptionPane.showMessageDialog(this, dMsg, "Fin de Partie", JOptionPane.WARNING_MESSAGE); statusLabel.setText(dMsg + ". Retour au lobby..."); gameLogUI.addLogEntry(dMsg + ". Retour au lobby."); // Modifié
                myTurn = false; inGame = false;
                break;
            case "PLAYER_LEFT":
            case "PLAYER_LEFT_GAME_SPECTATOR":
                String[] lInfo = payload.split(":"); String lPlayerName = lInfo[0]; int lPlayerIdx = (lInfo.length > 1 && command.equals("PLAYER_LEFT")) ? Integer.parseInt(lInfo[1]) : getPlayerIndexByName(lPlayerName);
                String plm = "Joueur " + lPlayerName + " a quitté."; statusLabel.setText(plm); gameLogUI.addLogEntry(plm); // Modifié
                if (lPlayerIdx != -1) { GridPanel lpg = playerGridPanels.get(lPlayerIdx); if(lpg != null) { lpg.setBackground(Color.GRAY.brighter()); lpg.setEnabled(false);
                    for(int r=0; r < ModelConstants.TAILLE_GRILLE; r++) for(int c=0; c < ModelConstants.TAILLE_GRILLE; c++) if (lpg.buttons[r][c] != null) { lpg.buttons[r][c].setBackground(Color.GRAY.brighter()); lpg.buttons[r][c].setText("OUT"); lpg.buttons[r][c].setEnabled(false);}}
                    allPlayerNames.remove(lPlayerIdx); }
                break;
            case "NEW_CHAT_MSG":
                String[] cp = payload.split(":", 2);
                chatUI.appendMessage(cp.length == 2 ? cp[0] + ": " + cp[1] : payload); // Modifié
                break;
            case "LOBBY_COUNTDOWN_STARTED":
                 String csMsg = "\nCompte à rebours de " + payload + "s démarré !"; if(lobbyArea != null && lobbyPanelContainer.isVisible()) lobbyArea.append(csMsg); gameLogUI.addLogEntry("Lobby: " + csMsg.trim()); break; // Modifié
            case "LOBBY_COUNTDOWN_CANCELLED":
                 String ccMsg = "\nCompte à rebours annulé."; if(lobbyArea != null && lobbyPanelContainer.isVisible()) lobbyArea.append(ccMsg); gameLogUI.addLogEntry("Lobby: " + ccMsg.trim()); break; // Modifié
            case "LOBBY_TIMER_ENDED_NO_GAME":
                 String tenMsg = "\nMinuteur terminé. " + payload + ". En attente..."; if(lobbyArea != null && lobbyPanelContainer.isVisible()) lobbyArea.append(tenMsg); gameLogUI.addLogEntry("Lobby: " + tenMsg.trim()); break; // Modifié
            case "ERROR":
                statusLabel.setText("Erreur serveur: " + payload); gameLogUI.addLogEntry("Erreur serveur: " + payload); // Modifié
                if (payload.contains("nom est déjà utilisé") || payload.contains("nom ne peut pas être vide")) {
                    if(nameField != null) nameField.setEditable(true); if(connectButton != null) connectButton.setEnabled(true); nameSuccessfullySetThisSession = false;}
                else if (payload.contains("Serveur plein")) { connectButton.setEnabled(true); if (serverIpField != null) serverIpField.setEditable(true); if (nameField != null) nameField.setEditable(true);}
                break;
            default: System.out.println("Message serveur inconnu: " + command + " " + payload); gameLogUI.addLogEntry("Message serveur non traité: " + command + (payload.isEmpty() ? "" : (":" + payload))); // Modifié
        }
    }

    // GridPanel reste une classe interne pour l'instant
    // ... (copier la classe GridPanel ici, identique à la version précédente)
    class GridPanel extends JPanel {
        private JButton[][] buttons;
        private int taille;
        private boolean isMyBoard;
        public final int boardOwnerPlayerIndex;

        private boolean placementModeActive = false;
        private ModelConstants.ShipType shipToPlaceForPreview;
        private boolean previewHorizontal;
        public Point previewStartCell = null;

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
                    int preferredCellSize = Math.max(25, Math.min(40, 300 / taille));
                    buttons[i][j].setPreferredSize(new Dimension(preferredCellSize, preferredCellSize));

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
                            serverCommunicator.sendMessage("PLACE_SHIP:" + currentShipToPlace.name() + ":" + r + ":" + c + ":" + horizontalRadioButton.isSelected());
                        } else if (!isMyBoard && myTurn && BatailleNavaleClient.this.inGame && !placementPhase) {
                             serverCommunicator.sendMessage("FIRE_SHOT:" + this.boardOwnerPlayerIndex + ":" + r + ":" + c);
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
            if (this.placementModeActive && previewStartCell != null) {
                 repaint();
            } else if (!this.placementModeActive) {
                previewStartCell = null;
                repaint();
            }
        }

        public void setPlacementMode(boolean mode) {
            this.placementModeActive = mode;
            if (!mode) {
                previewStartCell = null;
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
            repaint();
        }


        public void markShot(int r, int c, ModelConstants.ShotResult result) {
            if (r < 0 || r >= taille || c < 0 || c >= taille) return;
            JButton button = buttons[r][c]; if (button == null) return;
            button.setIcon(null);
            switch (result) {
                case TOUCHE: button.setText(String.valueOf(isMyBoard ? ModelConstants.MY_HIT_ON_ME : ModelConstants.OPP_HIT)); button.setBackground(isMyBoard ? Color.PINK : Color.ORANGE); button.setForeground(Color.BLACK); button.setEnabled(false); break;
                case COULE: button.setText(String.valueOf(isMyBoard ? ModelConstants.MY_HIT_ON_ME : ModelConstants.OPP_SUNK)); button.setBackground(Color.RED); button.setForeground(Color.WHITE); button.setEnabled(false); break;
                case MANQUE: button.setText(String.valueOf(isMyBoard ? ModelConstants.MY_MISS_ON_ME : ModelConstants.OPP_MISS)); button.setBackground(isMyBoard ? Color.BLUE.brighter() : Color.LIGHT_GRAY); button.setForeground(Color.BLACK); button.setEnabled(false); break;
                case DEJA_JOUE: case ERREUR: break;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (isMyBoard && placementModeActive && shipToPlaceForPreview != null && previewStartCell != null && !amISpectator) {
                Graphics2D g2d = (Graphics2D) g.create();
                if (buttons[0][0] == null) return; int cellWidth = buttons[0][0].getWidth(); int cellHeight = buttons[0][0].getHeight(); if (cellWidth <= 0 || cellHeight <= 0) return;
                boolean placementEstValide = true; List<Point> cellulesPreview = new ArrayList<>(); boolean currentOrientation = horizontalRadioButton.isSelected();
                for (int i = 0; i < shipToPlaceForPreview.getTaille(); i++) {
                    int rP = previewStartCell.x; int cP = previewStartCell.y;
                    if (currentOrientation) cP += i; else rP += i;
                    cellulesPreview.add(new Point(rP, cP));
                    if (rP < 0 || rP >= taille || cP < 0 || cP >= taille) { placementEstValide = false; break; }
                    if (!buttons[rP][cP].getText().equals(String.valueOf(ModelConstants.MY_EMPTY))) { placementEstValide = false; break; }
                }
                g2d.setColor(placementEstValide ? new Color(100, 100, 100, 120) : new Color(255, 0, 0, 120));
                for (Point cell : cellulesPreview) if (cell.x >= 0 && cell.x < taille && cell.y >= 0 && cell.y < taille) {
                    Point loc = buttons[cell.x][cell.y].getLocation(); g2d.fillRect(loc.x, loc.y, cellWidth, cellHeight); }
                g2d.dispose();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            for (int i = 0; i < taille; i++) for (int j = 0; j < taille; j++) {
                if (buttons[i][j] == null) continue;
                if (amISpectator) { buttons[i][j].setEnabled(false); continue; }
                String txt = buttons[i][j].getText();
                if (isMyBoard) { if (placementPhase && currentShipToPlace != null) buttons[i][j].setEnabled(enabled && txt.equals(String.valueOf(ModelConstants.MY_EMPTY)));
                    else buttons[i][j].setEnabled(false);
                } else { if (BatailleNavaleClient.this.inGame && !placementPhase && myTurn) buttons[i][j].setEnabled(enabled && txt.equals(String.valueOf(ModelConstants.OPP_UNKNOWN)));
                    else buttons[i][j].setEnabled(false); }
            }
        }
    } // Fin GridPanel


    public static void main(String[] args) {
        SwingUtilities.invokeLater(BatailleNavaleClient::new);
    }
}