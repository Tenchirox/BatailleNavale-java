package com.example.bataillenavale.server; // Exemple de package

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

// Assurez-vous que les classes du modèle sont accessibles
import com.example.bataillenavale.model.BatailleNavaleGame;
import com.example.bataillenavale.model.PlayerBoard;
import com.example.bataillenavale.model.Ship;

// Imports for Java-WebSocket
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer; // WebSocketServer itself
import java.net.InetSocketAddress;
import java.nio.ByteBuffer; // For potential binary messages, though String is primary for this app

// New interface for client connections
interface ClientConnection {
    String getNomJoueur();
    boolean isNameSet();
    void setPlayerIndex(int index);
    int getPlayerIndex();
    BatailleNavaleServer.ClientHandler.ClientRole getRole(); // Use existing enum from ClientHandler
    void setRole(BatailleNavaleServer.ClientHandler.ClientRole role);
    boolean isActive();
    void sendMessage(String message);
    void closeConnection(boolean notifyServer); // notifyServer if server needs to run handleClientQuitte
    String getRemoteAddressString();
    void resetForNewLobby();
    Object getUnderlyingHandle(); // Returns Socket or WebSocket for identity/removal
}


public class BatailleNavaleServer {
    private static final int DEFAULT_TCP_PORT = 12350;
    private static final int DEFAULT_WS_PORT = 12349;

    private final int actualTcpPort;
    private final int actualWsPort;

    private ServerSocket legacyServerSocket; // For TCP
    private GameWebSocketServer webSocketServer;     // For WS

    // Unified list of connections
    private final List<ClientConnection> allClientConnections = new ArrayList<>();
    // playersInGame needs to store ClientConnection objects too
    private final List<ClientConnection> playersInGame = new ArrayList<>(); // Stores ClientConnection
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private BatailleNavaleGame game;

    private static final int MIN_PLAYERS_TO_START_TIMER = 2;
    private static final int MAX_PLAYERS_ALLOWED = 7;
    private boolean gameInProgressFlag = false;
    private Timer lobbyCountdownTimer;
    private TimerTask currentLobbyCountdownTask;
    private static final long LOBBY_COUNTDOWN_MS = 20000; // 20 secondes
    private boolean lobbyCountdownActive = false;


    public BatailleNavaleServer(int tcpPort, int wsPort) {
        this.actualTcpPort = tcpPort;
        this.actualWsPort = wsPort;

        // Start WebSocket server
        try {
            InetSocketAddress wsBindAddr = new InetSocketAddress(this.actualWsPort);
            webSocketServer = new GameWebSocketServer(wsBindAddr, this); // Pass server ref
            System.out.println("Serveur Bataille Navale (WebSocket) en préparation sur le port " + this.actualWsPort);
        } catch (Exception e) {
            System.err.println("Erreur au démarrage du serveur WebSocket: " + e.getMessage());
            webSocketServer = null; // Ensure it's null if failed
        }

        // Start TCP server
        try {
            legacyServerSocket = new ServerSocket(this.actualTcpPort);
            System.out.println("Serveur Bataille Navale (TCP) démarré sur le port " + this.actualTcpPort);
        } catch (IOException e) {
            System.err.println("Erreur au démarrage du serveur TCP Bataille Navale: " + e.getMessage());
            // Consider exiting if TCP server is essential and fails
            // System.exit(1); 
            legacyServerSocket = null; // Ensure it's null if failed
        }
    }
    public BatailleNavaleServer() {
        this(DEFAULT_TCP_PORT, DEFAULT_WS_PORT);
    }


    public void startServer() {
        if (webSocketServer != null) {
            webSocketServer.start(); // Starts the WebSocket server in a new thread
            // System.out.println("Serveur WebSocket démarré."); // Message now in GameWebSocketServer.onStart
        } else {
            System.err.println("Impossible de démarrer le serveur WebSocket car il n'a pas été initialisé.");
        }

        if (legacyServerSocket == null) {
            System.err.println("Impossible de démarrer l'écoute TCP car le serveur TCP n'a pas été initialisé.");
            return; // Cannot proceed without TCP server socket
        }

        System.out.println("En attente de connexions TCP sur le port " + actualTcpPort + " pour " + MIN_PLAYERS_TO_START_TIMER + " à " + MAX_PLAYERS_ALLOWED + " participants...");
        // TCP Connection Accept Loop
        pool.execute(() -> {
            while (legacyServerSocket != null && !legacyServerSocket.isClosed()) {
                try {
                    Socket clientSocket = legacyServerSocket.accept();
                    synchronized (this) { 
                         if (allClientConnections.size() >= MAX_PLAYERS_ALLOWED && !gameInProgressFlag) {
                            if (!(gameInProgressFlag && allClientConnections.size() < MAX_PLAYERS_ALLOWED)) {
                                PrintWriter tempOut = new PrintWriter(clientSocket.getOutputStream(), true);
                                tempOut.println("ERROR:Serveur plein (max " + MAX_PLAYERS_ALLOWED + " participants).");
                                clientSocket.close();
                                continue;
                            }
                        }

                        ClientHandler tcpConnection = new ClientHandler(clientSocket, this);
                        allClientConnections.add(tcpConnection);
                        pool.execute(tcpConnection); 
                        System.out.println("Nouveau participant TCP connecté: " + clientSocket.getRemoteSocketAddress() + ". Total connectés (tous types): " + allClientConnections.size());

                        if (gameInProgressFlag && game != null && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                            tcpConnection.setRole(ClientHandler.ClientRole.SPECTATOR);
                            System.out.println("Client TCP " + clientSocket.getRemoteSocketAddress() + " est un spectateur potentiel (jeu en cours).");
                        } else {
                            tcpConnection.setRole(ClientHandler.ClientRole.PLAYER_IN_LOBBY);
                        }
                    }
                } catch (IOException e) {
                    if (legacyServerSocket == null || legacyServerSocket.isClosed()) {
                        System.out.println("Serveur TCP arrêté.");
                        break;
                    }
                    System.err.println("Erreur d'acceptation client TCP: " + e.getMessage());
                }
            }
        });
    }

    public synchronized void playerHasSetName(ClientConnection client) { //
        if (client.getRole() == ClientHandler.ClientRole.SPECTATOR) {
            System.out.println("Spectateur " + client.getNomJoueur() + " a défini son nom.");
            if (gameInProgressFlag && game != null && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                client.sendMessage("SPECTATE_MODE");
                String allPlayerNamesStr = playersInGame.stream()
                                                      .map(ClientConnection::getNomJoueur) 
                                                      .collect(Collectors.joining(","));
                client.sendMessage("SPECTATE_INFO:" + PlayerBoard.TAILLE_GRILLE + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
                handleChatMessage(client, "[A rejoint le chat en tant que spectateur]");
                System.out.println(client.getNomJoueur() + " a reçu les infos pour spectateur.");
            } else {
                System.out.println("Jeu non en cours ou terminé. " + client.getNomJoueur() + " devient joueur dans le lobby.");
                client.setRole(ClientHandler.ClientRole.PLAYER_IN_LOBBY);
                broadcastLobbyState();
                handleChatMessage(client, "[A rejoint le chat du lobby]");
            }
            if (client.getRole() == ClientHandler.ClientRole.SPECTATOR && gameInProgressFlag) {
                return;
            }
        }

        if (client.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
            if (!(gameInProgressFlag && client.getRole() == ClientHandler.ClientRole.SPECTATOR)) { // Avoid double broadcast if transition
                 broadcastLobbyState();
             }
            int namedPlayerCount = 0;
            synchronized (allClientConnections) {
                for (ClientConnection ch : allClientConnections) {
                    if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
                        namedPlayerCount++;
                    }
                }
            }
            System.out.println("Joueurs avec nom dans le lobby: " + namedPlayerCount);

            if (!gameInProgressFlag && !lobbyCountdownActive && namedPlayerCount >= MIN_PLAYERS_TO_START_TIMER) {
                System.out.println(namedPlayerCount + " joueurs ont défini leur nom. Démarrage du compte à rebours du lobby.");
                startLobbyCountdown();
            } else if (!gameInProgressFlag && namedPlayerCount >= MAX_PLAYERS_ALLOWED ) { 
                 System.out.println("Nombre maximum de joueurs (" + namedPlayerCount + ") atteint et noms définis. Démarrage anticipé.");
                 if (lobbyCountdownActive) cancelLobbyCountdown();
                 prepareAndStartGameWithReadyPlayers();
            }
        }
    }
    
    private synchronized void startLobbyCountdown() {
        if (lobbyCountdownActive || gameInProgressFlag) {
            return;
        }
        lobbyCountdownActive = true;
        if (lobbyCountdownTimer == null) {
            lobbyCountdownTimer = new Timer("LobbyTimer", true);
        }
        currentLobbyCountdownTask = new TimerTask() {
            @Override
            public void run() {
                synchronized (BatailleNavaleServer.this) { 
                    lobbyCountdownActive = false;
                    if (gameInProgressFlag) return;

                    int namedPlayerCount = 0;
                    synchronized (allClientConnections) {
                        for (ClientConnection ch : allClientConnections) { 
                            if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) namedPlayerCount++;
                        }
                    }

                    if (namedPlayerCount >= MIN_PLAYERS_TO_START_TIMER) {
                        System.out.println("Compte à rebours du lobby terminé. Démarrage du jeu avec " + namedPlayerCount + " joueurs.");
                        prepareAndStartGameWithReadyPlayers();
                    } else {
                        System.out.println("Compte à rebours du lobby terminé, mais pas assez de joueurs ayant défini un nom. En attente...");
                        broadcast("LOBBY_TIMER_ENDED_NO_GAME:Pas assez de joueurs prêts.");
                    }
                }
            }
        };
        lobbyCountdownTimer.schedule(currentLobbyCountdownTask, LOBBY_COUNTDOWN_MS);
        broadcast("LOBBY_COUNTDOWN_STARTED:" + (LOBBY_COUNTDOWN_MS / 1000));
        System.out.println("Compte à rebours du lobby de " + (LOBBY_COUNTDOWN_MS / 1000) + "s démarré.");
    }

    private synchronized void cancelLobbyCountdown() {
        if (currentLobbyCountdownTask != null) {
            currentLobbyCountdownTask.cancel();
            currentLobbyCountdownTask = null;
        }
        if (lobbyCountdownActive) {
            lobbyCountdownActive = false;
            broadcast("LOBBY_COUNTDOWN_CANCELLED");
            System.out.println("Compte à rebours du lobby annulé.");
        }
    }

    private synchronized void prepareAndStartGameWithReadyPlayers() {
        if (gameInProgressFlag) {
            System.out.println("prepareAndStartGameWithReadyPlayers appelée alors que gameInProgressFlag est true.");
            return;
        }

        List<ClientConnection> joueursPretsPourPartie = new ArrayList<>();
        synchronized (allClientConnections) { 
            for (ClientConnection ch : allClientConnections) {
                if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY && ch.isActive()) {
                    joueursPretsPourPartie.add(ch);
                }
            }
        }

        if (joueursPretsPourPartie.size() < MIN_PLAYERS_TO_START_TIMER) {
            System.out.println("Tentative de démarrage, mais pas assez de joueurs prêts (" + joueursPretsPourPartie.size() + ").");
            broadcast("ERROR:Pas assez de joueurs prêts pour démarrer.");
            return;
        }
        
        gameInProgressFlag = true;
        cancelLobbyCountdown();

        synchronized(playersInGame) { //
            playersInGame.clear();
            playersInGame.addAll(joueursPretsPourPartie);
            for(int i=0; i < playersInGame.size(); i++) { 
                ClientConnection player = playersInGame.get(i);
                player.setPlayerIndex(i);
                player.setRole(ClientHandler.ClientRole.PLAYER_IN_GAME);
            }
        }
        
        String[] nomsJoueursEnPartie = new String[playersInGame.size()];
        for (int i = 0; i < playersInGame.size(); i++) {
            nomsJoueursEnPartie[i] = playersInGame.get(i).getNomJoueur();
        }
        
        game = new BatailleNavaleGame(nomsJoueursEnPartie);
        System.out.println("Partie de Bataille Navale démarrée avec : " + Arrays.toString(nomsJoueursEnPartie));

        String allPlayerNamesStr = Arrays.stream(nomsJoueursEnPartie).collect(Collectors.joining(","));

        for (ClientConnection client : playersInGame) { 
            client.sendMessage("GAME_START:" + PlayerBoard.TAILLE_GRILLE + ":" + client.getPlayerIndex() + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
        }
        
        synchronized(allClientConnections) { 
            for(ClientConnection ch : allClientConnections) {
                if (!playersInGame.contains(ch)) { 
                    ch.setRole(ClientHandler.ClientRole.SPECTATOR); 
                    if (ch.isNameSet()) { 
                        ch.sendMessage("SPECTATE_MODE");
                        ch.sendMessage("SPECTATE_INFO:" + PlayerBoard.TAILLE_GRILLE + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
                         System.out.println(ch.getNomJoueur() + " est maintenant spectateur de la nouvelle partie.");
                    } else {
                        if (ch.getUnderlyingHandle() instanceof WebSocket) { 
                            ch.sendMessage("REQ_NAME"); 
                        }
                    }
                }
            }
        }
        passerAuPlacementSuivant();
    }

    public synchronized void handleAdminStartGame(ClientConnection adminClient) { //
        boolean isAdminHost = false;
        synchronized(allClientConnections) { 
            if (!allClientConnections.isEmpty()) {
                ClientConnection firstPotentialHost = null;
                for (ClientConnection ch_loop : allClientConnections) {
                    if (ch_loop.isNameSet() && ch_loop.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
                        firstPotentialHost = ch_loop;
                        break;
                    }
                }
                if (firstPotentialHost == adminClient) {
                    isAdminHost = true;
                }
            }
        }

        if (!isAdminHost) {
            adminClient.sendMessage("ERROR:Seul l'hôte (premier joueur connecté ayant un nom dans le lobby) peut démarrer la partie.");
            return;
        }
         if (gameInProgressFlag) {
            adminClient.sendMessage("ERROR:La partie est déjà en cours ou en démarrage.");
            return;
        }
        
        int namedPlayerCount = 0;
        synchronized(allClientConnections) { 
            for(ClientConnection ch_loop : allClientConnections) {
                if(ch_loop.isNameSet() && ch_loop.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) namedPlayerCount++;
            }
        }

        if (namedPlayerCount < MIN_PLAYERS_TO_START_TIMER) {
            adminClient.sendMessage("ERROR:Pas assez de joueurs prêts (min " + MIN_PLAYERS_TO_START_TIMER + " avec nom défini).");
            return;
        }

        System.out.println("Démarrage de la partie par l'administrateur/hôte: " + adminClient.getNomJoueur());
        cancelLobbyCountdown();
        prepareAndStartGameWithReadyPlayers();
    }


    private synchronized void passerAuPlacementSuivant() {
        if (game == null) {
             System.err.println("passerAuPlacementSuivant appelé alors que game est null.");
            if (gameInProgressFlag) { 
                 broadcastToAllParticipants("ERROR:Erreur critique du jeu, retour au lobby.");
                 resetServerForNewLobby();
            }
            return;
        }
        if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.COMBAT) {
            System.out.println("Tous les navires placés. Début de la phase de combat.");
            broadcastToPlayersInGame("ALL_SHIPS_PLACED");
            informerTourCombat();
            return;
        }

        if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.PLACEMENT_BATEAUX) {
            int gameCurrentPlayerGlobalIndex = game.getJoueurCourantIndex();
             if (gameCurrentPlayerGlobalIndex == -1) { 
                System.err.println("Erreur: Aucun joueur actif pour la phase de placement.");
                if (game.getNombreJoueursActifs() == 0 && !playersInGame.isEmpty()) { 
                    broadcastToAllParticipants("GAME_OVER_DISCONNECT:Tous les joueurs ont quitté pendant le placement.");
                } else {
                     broadcastToAllParticipants("ERROR:Problème de joueur pour le placement.");
                }
                resetServerForNewLobby();
                return;
            }

            ClientConnection clientActif = getClientConnectionByGlobalIndexInGame(gameCurrentPlayerGlobalIndex); 
            if (clientActif == null) {
                System.err.println("Erreur: Joueur courant (" + gameCurrentPlayerGlobalIndex + ") pour placement non trouvé ou inactif côté serveur.");
                game.passerAuJoueurSuivantPourPlacement(); 
                passerAuPlacementSuivant(); 
                return;
            }
            List<Ship.ShipType> naviresAPlacer = game.getNaviresAPlacerPourJoueurCourant();
            if (!naviresAPlacer.isEmpty()) {
                Ship.ShipType prochainNavire = naviresAPlacer.get(0);
                clientActif.sendMessage("YOUR_TURN_PLACE_SHIP:" + prochainNavire.name() + ":" + prochainNavire.getTaille() + ":" + prochainNavire.getNom());
                broadcastSaufAUnJoueurEnPartie(clientActif,"WAIT_PLACEMENT:" + clientActif.getNomJoueur() + ":" + prochainNavire.getNom());
            } else {
                System.out.println("Joueur " + clientActif.getNomJoueur() + " a fini ses placements. Demande de passage au suivant.");
                game.passerAuJoueurSuivantPourPlacement(); 
                passerAuPlacementSuivant(); 
            }
        } else if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.TERMINE) {
             System.out.println("Jeu terminé, pas de placement suivant.");
             if (gameInProgressFlag) { 
                 resetServerForNewLobby();
             }
        } else {
            System.err.println("Phase de jeu inattendue dans passerAuPlacementSuivant: " + game.getPhaseActuelle());
        }
    }
    
    public synchronized void handlePlacementNavire(ClientConnection client, Ship.ShipType type, int ligne, int col, boolean horizontal) { //
        if (client.getRole() != ClientHandler.ClientRole.PLAYER_IN_GAME) {
             client.sendMessage("ERROR:Les spectateurs ne peuvent pas placer de navires."); return;
        }
        if (game == null || game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.PLACEMENT_BATEAUX) {
            client.sendMessage("ERROR:Pas en phase de placement.");
            return;
        }
        if (client.getPlayerIndex() != game.getJoueurCourantIndex()) {
            client.sendMessage("ERROR:Pas votre tour de placer.");
            return;
        }

        List<Ship.ShipType> naviresAPlacer = game.getNaviresAPlacerPourJoueurCourant();
        if (naviresAPlacer.isEmpty() || naviresAPlacer.get(0) != type) {
            client.sendMessage("ERROR:Ce n'est pas le navire attendu (" + (naviresAPlacer.isEmpty() ? "aucun" : naviresAPlacer.get(0).getNom()) + ")");
            return;
        }
        if (game.placerNavireJoueurCourant(type, ligne, col, horizontal)) {
            client.sendMessage("PLACEMENT_ACCEPTED:" + type.name() + ":" + ligne + ":" + col + ":" + horizontal);
            broadcastSaufAUnJoueurEnPartie(client, "PLAYER_PLACED_SHIP:" + client.getNomJoueur() + ":" + type.getNom());
            passerAuPlacementSuivant();
        } else {
            client.sendMessage("PLACEMENT_REJECTED:" + type.name());
            List<Ship.ShipType> currentNaviresAPlacer = game.getNaviresAPlacerPourJoueurCourant(); 
            if (!currentNaviresAPlacer.isEmpty() && currentNaviresAPlacer.get(0) == type) {
                 Ship.ShipType prochainNavire = currentNaviresAPlacer.get(0);
                 client.sendMessage("YOUR_TURN_PLACE_SHIP:" + prochainNavire.name() + ":" + prochainNavire.getTaille() + ":" + prochainNavire.getNom());
            } else if (!currentNaviresAPlacer.isEmpty()) { 
                 Ship.ShipType prochainNavire = currentNaviresAPlacer.get(0);
                 client.sendMessage("YOUR_TURN_PLACE_SHIP:" + prochainNavire.name() + ":" + prochainNavire.getTaille() + ":" + prochainNavire.getNom());
                 System.out.println("Placement rejeté, mais le navire attendu a changé pour " + client.getNomJoueur());
            } else { 
                 System.err.println("Erreur critique: Rejet de placement mais plus de navires à placer pour " + client.getNomJoueur() + " ou liste vide.");
                 passerAuPlacementSuivant(); 
            }
        }
    }

    private synchronized void informerTourCombat() {
        if (game == null || game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.COMBAT) {
            if (game != null && game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.TERMINE && gameInProgressFlag) {
                 System.out.println("informerTourCombat appelé alors que le jeu est terminé.");
            }
            return;
        }

        int joueurCourantGlobalIndex = game.getJoueurCourantIndex();
        if (joueurCourantGlobalIndex == -1) { 
             System.err.println("Erreur dans informerTourCombat: Aucun joueur courant actif.");
             if (game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) { 
                broadcastToAllParticipants("GAME_OVER_DRAW: Aucun joueur actif restant en combat.");
                resetServerForNewLobby();
             }
             return;
        }

        ClientConnection clientActif = getClientConnectionByGlobalIndexInGame(joueurCourantGlobalIndex); 
        if (clientActif == null) {
            System.err.println("Erreur dans informerTourCombat: ClientConnection non trouvé pour l'index global " + joueurCourantGlobalIndex + ".");
            game.passerAuJoueurSuivantPourCombat(); 
            informerTourCombat(); 
            return;
        }
        clientActif.sendMessage("YOUR_TURN_FIRE");
        broadcastSaufAUnJoueurEnPartie(clientActif, "OPPONENT_TURN_FIRE:" + clientActif.getNomJoueur());
        System.out.println("Phase de combat: Au tour de " + clientActif.getNomJoueur());
    }

    public synchronized void handleTir(ClientConnection clientTireur, int targetPlayerGlobalIndex, int ligne, int col) { //
         if (clientTireur.getRole() != ClientHandler.ClientRole.PLAYER_IN_GAME) {
             clientTireur.sendMessage("ERROR:Les spectateurs ne peuvent pas tirer."); return;
        }
        if (game == null || game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.COMBAT) {
            clientTireur.sendMessage("ERROR:Pas en phase de combat.");
            return;
        }
        if (clientTireur.getPlayerIndex() != game.getJoueurCourantIndex()) {
            clientTireur.sendMessage("ERROR:Pas votre tour de tirer.");
            return;
        }
        PlayerBoard targetBoard = game.getPlayerBoard(targetPlayerGlobalIndex); 
        if (targetBoard == null || targetPlayerGlobalIndex == clientTireur.getPlayerIndex() ||
            (game.getJoueursActifsIndices() != null && !game.getJoueursActifsIndices().contains(targetPlayerGlobalIndex))) {
            clientTireur.sendMessage("ERROR:Cible de tir invalide ou joueur inactif.");
            clientTireur.sendMessage("YOUR_TURN_FIRE"); 
            return;
        }

        PlayerBoard.ShotResult resultat = game.tirerSurAdversaire(targetPlayerGlobalIndex, ligne, col);
        String nomJoueurCible = game.getPlayerBoard(targetPlayerGlobalIndex).getNomJoueur();
        int joueurTireurIndex = clientTireur.getPlayerIndex();
        String messageBase = "SHOT_RESULT:" + joueurTireurIndex + ":" + targetPlayerGlobalIndex + ":" + ligne + ":" + col + ":" + resultat.name();

        if (resultat == PlayerBoard.ShotResult.COULE) {
            Ship navireCouleDetecte = null;
            PlayerBoard boardCibleEffective = game.getPlayerBoard(targetPlayerGlobalIndex); 
            for(Ship s : boardCibleEffective.getNavires()){ //
                if(s.estCoule()){  //
                    boolean segmentToucheSurCeNavire = false;
                    for(java.awt.Point p : s.getPositions()){ //
                        if(p.x == ligne && p.y == col) {
                            segmentToucheSurCeNavire = true;
                            break;
                        }
                    }
                    if (segmentToucheSurCeNavire) {
                        navireCouleDetecte = s;
                        break;
                    }
                    if (navireCouleDetecte == null) { 
                        boolean hitThisShip = false;
                         for(java.awt.Point p : s.getPositions()){ if(p.x == ligne && p.y == col) {hitThisShip = true; break;}}
                         if(hitThisShip) navireCouleDetecte = s;
                    }
                }
            } 
             if(navireCouleDetecte != null){ 
                messageBase += ":" + navireCouleDetecte.getType().getNom(); //
            } else {
                 PlayerBoard boardCible = game.getPlayerBoard(targetPlayerGlobalIndex);
                 for(Ship s : boardCible.getNavires()){ //
                     if(s.estCoule()){ //
                         boolean justSunkByThisHit = false;
                         for(java.awt.Point p : s.getPositions()){ //
                             if(p.x == ligne && p.y == col && s.getNombreTouchees() == s.getTaille()){ //
                                 justSunkByThisHit = true;
                                 break;
                             }
                         }
                         if(justSunkByThisHit){
                             navireCouleDetecte = s;
                             break;
                         }
                     }
                 }
                 if(navireCouleDetecte != null){
                    messageBase += ":" + navireCouleDetecte.getType().getNom(); //
                 } else {
                    messageBase += ":UNKNOWN_SHIP"; 
                    System.err.println("SHOT_RESULT was COULE, but could not identify which ship for player " + nomJoueurCible);
                 }
            }
        } 
        broadcastToAllParticipants(messageBase);
        System.out.println("Tir de " + clientTireur.getNomJoueur() + " sur " + nomJoueurCible + " en " + ligne + "," + col + " -> " + resultat);

        if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.TERMINE) {
            if (game.getGagnantIndex() != -1) {
                String nomGagnant = game.getPlayerBoard(game.getGagnantIndex()).getNomJoueur();
                broadcastToAllParticipants("GAME_OVER:" + nomGagnant + ":" + game.getGagnantIndex());
                System.out.println("Partie terminée. Gagnant: " + nomGagnant);
            } else {
                broadcastToAllParticipants("GAME_OVER_DRAW");
                System.out.println("Partie terminée. Aucun survivant ou match nul.");
            }
            resetServerForNewLobby();
        } else if (resultat != PlayerBoard.ShotResult.DEJA_JOUE && resultat != PlayerBoard.ShotResult.ERREUR) {
            informerTourCombat();
        } else { 
            clientTireur.sendMessage("YOUR_TURN_FIRE");
        }
    }

    public synchronized void processClientMessage(ClientConnection client, String messageLine) { //
        System.out.println("Reçu de " + (client.isNameSet() ? client.getNomJoueur() : client.getRemoteAddressString()) + 
                           " (rôle " + client.getRole() + ", idx " + client.getPlayerIndex() + ", nameSet: "+client.isNameSet()+"): " + messageLine);
        
        String[] parts = messageLine.split(":", 2);
        String command = parts[0].toUpperCase();
        String payload = (parts.length > 1) ? parts[1] : "";

        if (command.equals("SET_NAME")) {
            String potentialName = payload.trim();
            if (!potentialName.isEmpty() && potentialName.length() <= 15) {
                boolean nameTaken = false;
                synchronized(allClientConnections) {
                    for(ClientConnection ch : allClientConnections) {
                        if (ch != client && ch.isNameSet() && ch.getNomJoueur().equalsIgnoreCase(potentialName)) {
                            nameTaken = true;
                            break;
                        }
                    }
                }
                if (nameTaken) {
                    client.sendMessage("ERROR:Ce nom est déjà utilisé. Veuillez en choisir un autre.");
                    client.sendMessage("REQ_NAME"); 
                } else {
                    if (client instanceof ClientHandler) { 
                        ((ClientHandler)client).nomJoueur = potentialName;
                        ((ClientHandler)client).nameIsSet = true;
                    } else if (client instanceof WebSocketClientConnection) { 
                        ((WebSocketClientConnection)client).nomJoueur = potentialName;
                        ((WebSocketClientConnection)client).nameIsSet = true;
                    }
                    System.out.println("Client ("+client.getRemoteAddressString()+") s'appelle maintenant " + client.getNomJoueur());
                    playerHasSetName(client);
                }
            } else {
                client.sendMessage("ERROR:Le nom ne peut pas être vide et doit faire 15 caractères max.");
                if (!client.isNameSet()) client.sendMessage("REQ_NAME");
            }
            return; 
        }

        if (!client.isNameSet() && !command.equals("QUIT_GAME")) { 
            client.sendMessage("ERROR:Veuillez d'abord définir votre nom avec SET_NAME:votreNom.");
            client.sendMessage("REQ_NAME"); 
            return;
        }

        switch (command) {
            case "PLACE_SHIP":
                if (client.getRole() != ClientHandler.ClientRole.PLAYER_IN_GAME) { client.sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                String[] placementArgs = payload.split(":");
                if (placementArgs.length == 4) {
                    try {
                        Ship.ShipType type = Ship.ShipType.valueOf(placementArgs[0].toUpperCase());
                        int ligne = Integer.parseInt(placementArgs[1]);
                        int col = Integer.parseInt(placementArgs[2]);
                        boolean horizontal = Boolean.parseBoolean(placementArgs[3]);
                        handlePlacementNavire(client, type, ligne, col, horizontal);
                    } catch (IllegalArgumentException e) {
                        client.sendMessage("ERROR:Arguments de placement invalides. " + e.getMessage());
                    }
                } else {
                     client.sendMessage("ERROR:Commande PLACE_SHIP malformée.");
                }
                break;
            case "FIRE_SHOT":
                if (client.getRole() != ClientHandler.ClientRole.PLAYER_IN_GAME) { client.sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                String[] tirArgs = payload.split(":");
                if (tirArgs.length == 3) {
                    try {
                        int targetIdx = Integer.parseInt(tirArgs[0]);
                        int ligne = Integer.parseInt(tirArgs[1]);
                        int col = Integer.parseInt(tirArgs[2]);
                        handleTir(client, targetIdx, ligne, col);
                    } catch (NumberFormatException e) {
                        client.sendMessage("ERROR:Coordonnées de tir ou index cible invalides.");
                    }
                } else {
                    client.sendMessage("ERROR:Commande FIRE_SHOT malformée (attendu: FIRE_SHOT:targetIdx:ligne:col).");
                }
                break;
            case "ADMIN_START_GAME":
                if (client.getRole() != ClientHandler.ClientRole.PLAYER_IN_LOBBY) { client.sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                handleAdminStartGame(client);
                break;
            case "QUIT_GAME":
                System.out.println("Client " + client.getNomJoueur() + " a envoyé QUIT_GAME.");
                client.closeConnection(true); 
                break; 
            case "CHAT_MSG":
                if (!payload.isEmpty()) {
                    handleChatMessage(client, payload);
                }
                break;
            default:
                client.sendMessage("ERROR:Commande inconnue '" + command + "'.");
                break;
        }
    }


    public synchronized void handleClientQuitte(ClientConnection client) { //
        System.out.println("Déconnexion demandée/détectée pour: " + client.getNomJoueur() + 
                           " (rôle: " + client.getRole() + ", index: " + client.getPlayerIndex() + ")");

        boolean clientWasInAllConnections = allClientConnections.remove(client);
        boolean clientWasInPlayersInGame = playersInGame.remove(client); 

        if (gameInProgressFlag && clientWasInPlayersInGame && game != null) { 
            System.out.println("Joueur " + client.getNomJoueur() + " a quitté une partie en cours.");
            boolean gamePeutContinuer = game.handlePlayerDisconnect(client.getPlayerIndex());

            if (gamePeutContinuer && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                broadcastToAllParticipants("PLAYER_LEFT:" + client.getNomJoueur() + ":" + client.getPlayerIndex());
                System.out.println("La partie continue sans " + client.getNomJoueur() + ".");
                if (game.getJoueurCourantIndex() == client.getPlayerIndex() || 
                    (getClientConnectionByGlobalIndexInGame(game.getJoueurCourantIndex()) != null && !getClientConnectionByGlobalIndexInGame(game.getJoueurCourantIndex()).isActive()) ) {
                    
                    if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.PLACEMENT_BATEAUX) {
                        passerAuPlacementSuivant(); 
                    } else if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.COMBAT) {
                        informerTourCombat(); 
                    }
                } 
            } else { 
                 String raisonFin = game.getGagnantIndex() != -1 && game.getPlayerBoard(game.getGagnantIndex()) != null ?
                                   game.getPlayerBoard(game.getGagnantIndex()).getNomJoueur() :
                                   (client.getNomJoueur() + " (déconnexion)");

                String messageFin = game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.TERMINE && game.getGagnantIndex() != -1 && game.getPlayerBoard(game.getGagnantIndex()) != null ?
                                   "GAME_OVER:" + game.getPlayerBoard(game.getGagnantIndex()).getNomJoueur() + ":" + game.getGagnantIndex():
                                   "GAME_OVER_DISCONNECT:" + client.getNomJoueur();
                broadcastToAllParticipants(messageFin);
                System.out.println("Partie terminée suite à déconnexion. Raison approx: " + raisonFin);
                resetServerForNewLobby();
            }
        } else if (clientWasInAllConnections) {  
            System.out.println("Participant " + client.getNomJoueur() + " a quitté (hors partie active).");
            if (client.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY && lobbyCountdownActive) {
                int playerCountInLobby = 0;
                synchronized(allClientConnections) { 
                    for (ClientConnection ch : allClientConnections) {
                        if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
                            playerCountInLobby++;
                        }
                    }
                }
                if (playerCountInLobby < MIN_PLAYERS_TO_START_TIMER) {
                    System.out.println("Moins de " + MIN_PLAYERS_TO_START_TIMER + " joueurs prêts restants dans le lobby, annulation du compte à rebours.");
                    cancelLobbyCountdown();
                }
            }
            broadcastLobbyState(); 
            if(client.isNameSet()) { 
                handleChatMessage(client, "[A quitté le chat]");
            }
        } else {
             System.out.println("Client " + client.getNomJoueur() + " non trouvé dans les listes actives lors de la déconnexion (peut-être déjà retiré).");
        }
    }
    
    public synchronized void handleChatMessage(ClientConnection sender, String message) { //
        if (sender.isNameSet() && !message.trim().isEmpty()) {
            System.out.println("CHAT [" + sender.getNomJoueur() + "]: " + message);
            broadcast("NEW_CHAT_MSG:" + sender.getNomJoueur() + ":" + message);
        } else if (!sender.isNameSet()){
            sender.sendMessage("ERROR:Vous devez définir votre nom pour envoyer des messages dans le chat.");
        }
    }

    private synchronized void resetServerForNewLobby() { //
        System.out.println("Réinitialisation du serveur pour un nouveau lobby.");
        game = null; 
        gameInProgressFlag = false;
        cancelLobbyCountdown();
        playersInGame.clear(); 

        List<ClientConnection> clientsASynchroniser; 
        synchronized (allClientConnections) {
            clientsASynchroniser = new ArrayList<>(allClientConnections);
        }

        for (ClientConnection ch : clientsASynchroniser) {
            if (ch.isActive()) {
                ch.resetForNewLobby();
                ch.sendMessage("REQ_NAME"); 
            }
        }
        synchronized (allClientConnections) { 
            if (allClientConnections.isEmpty()) {
                 System.out.println("Aucun client actif restant après reset. En attente de nouvelles connexions.");
            } else {
                System.out.println(allClientConnections.size() + " clients potentiels pour le nouveau lobby (doivent redonner leur nom).");
            }
        }
        broadcastLobbyState(); 
    }

    private void broadcast(String message) { //
        List<ClientConnection> clientsSnapshot;
        synchronized (allClientConnections) {
            clientsSnapshot = new ArrayList<>(allClientConnections);
        }
        for (ClientConnection client : clientsSnapshot) {
            if (client != null && client.isActive()) client.sendMessage(message);
        }
    }
    
    private void broadcastToPlayersInGame(String message) {  //
        List<ClientConnection> gamePlayersSnapshot;
        synchronized (playersInGame) {
            gamePlayersSnapshot = new ArrayList<>(playersInGame);
        }
        for (ClientConnection client : gamePlayersSnapshot) {
            if (client != null && client.isActive()) client.sendMessage(message);
        }
    }

    private void broadcastToAllParticipants(String message) {  //
        List<ClientConnection> participants = new ArrayList<>();
        synchronized(playersInGame) { 
            participants.addAll(playersInGame);
        }
        synchronized(allClientConnections) { 
            for(ClientConnection ch : allClientConnections) {
                if(ch.getRole() == ClientHandler.ClientRole.SPECTATOR && !participants.contains(ch)) {
                    participants.add(ch);
                }
            }
        }
        for (ClientConnection participant : participants) {
            if (participant != null && participant.isActive()) participant.sendMessage(message);
        }
    }

    private void broadcastSaufAUnJoueurEnPartie(ClientConnection exclure, String message) { //
        List<ClientConnection> gamePlayersSnapshot;
        synchronized (playersInGame) {
            gamePlayersSnapshot = new ArrayList<>(playersInGame);
        }
        for (ClientConnection client : gamePlayersSnapshot) {
            if (client != null && client != exclure && client.isActive()) {
                client.sendMessage(message);
            }
        }
    }
    
    private ClientConnection getClientConnectionByGlobalIndexInGame(int globalGameIndex) { //
        synchronized(playersInGame) {
            for(ClientConnection ch : playersInGame){ 
                 if(ch.getPlayerIndex() == globalGameIndex && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_GAME) {
                     return ch;
                 }
            }
        }
        return null;
    }
        
    private void broadcastLobbyState() { //
        synchronized(allClientConnections) { 
            String nomsJoueursDansLobbyAyantNom = allClientConnections.stream()
                                             .filter(ch -> ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) 
                                             .map(ClientConnection::getNomJoueur) 
                                             .collect(Collectors.joining(","));
            long nombreDeNomsDefinisDansLobby = allClientConnections.stream()
                                             .filter(ch -> ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY)
                                             .count();
            broadcast("LOBBY_STATE:" + nombreDeNomsDefinisDansLobby + ":" + MIN_PLAYERS_TO_START_TIMER + ":" + MAX_PLAYERS_ALLOWED + ":" + nomsJoueursDansLobbyAyantNom);
        }
    }


    static class ClientHandler implements Runnable, ClientConnection { //
        enum ClientRole { PLAYER_IN_LOBBY, PLAYER_IN_GAME, SPECTATOR }

        private Socket clientSocket;
        private BatailleNavaleServer server; 
        private PrintWriter out;
        private BufferedReader in;
        String nomJoueur = "JoueurAnonyme"; 
        int playerIndex = -1;
        boolean nameIsSet = false; 
        private ClientRole role = ClientRole.PLAYER_IN_LOBBY;
        private volatile boolean socketActive = true;


        public ClientHandler(Socket socket, BatailleNavaleServer server) {
            this.clientSocket = socket;
            this.server = server;
            try {
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            } catch (IOException e) {
                System.err.println("Erreur I/O pour ClientHandler: " + e.getMessage());
                socketActive = false;
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                } catch (IOException ex) { /* Ignored */ }
            }
        }
        
        @Override public String getNomJoueur() { return nomJoueur; }
        @Override public boolean isNameSet() { return nameIsSet; }
        @Override public void setPlayerIndex(int index) { this.playerIndex = index; }
        @Override public int getPlayerIndex() { return playerIndex; }
        @Override public ClientRole getRole() { return role; }
        @Override public void setRole(ClientRole role) { this.role = role; }
        @Override public boolean isActive() {
             return socketActive && clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected() && out != null && !out.checkError();
        }
        @Override public String getRemoteAddressString() { return clientSocket.getRemoteSocketAddress().toString(); }
        @Override public Object getUnderlyingHandle() { return clientSocket; }

        @Override
        public void resetForNewLobby() {
            this.nameIsSet = false;
            this.playerIndex = -1;
            this.role = ClientRole.PLAYER_IN_LOBBY;
        }
        
        @Override
        public void closeConnection(boolean notifyServer) {
            if (!socketActive) return; // Already closed or being closed
            socketActive = false; // Prevent further operations
            try {
                // Closing the socket will cause the readLine in run() to throw an IOException or return null
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close(); // This should interrupt the blocking read in run()
                }
            } catch (IOException e) { 
                System.err.println("Exception en fermant le socket TCP pour " + getRemoteAddressString() + ": " + e.getMessage());
            }
            // Do not close in/out here as it might be done by the run() method's finally block.
            // Or ensure this method is the single point of truth for closing.
            // For now, let run() handle its own stream closures in finally.
            System.out.println("Fermeture de la connexion TCP demandée pour " + getRemoteAddressString());
            if (notifyServer) {
                server.handleClientQuitte(this);
            }
        }


        @Override
        public void run() { //
            try {
                synchronized(server) { 
                    if (server.gameInProgressFlag && server.game != null && server.game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                        if (this.role != ClientRole.SPECTATOR) { 
                             this.role = ClientRole.SPECTATOR;
                        }
                    } else {
                        if (this.role != ClientRole.PLAYER_IN_LOBBY) { 
                            this.role = ClientRole.PLAYER_IN_LOBBY;
                        }
                    }
                }

                if (!nameIsSet && isActive()) { // Check isActive before sending
                     sendMessage("REQ_NAME");
                }

                String messageClient;
                // The loop condition `isActive()` is crucial if `closeConnection` is called externally.
                // `in.readLine()` will return null if the stream is closed (e.g., by socket.close()).
                while (isActive() && (messageClient = in.readLine()) != null) {
                    server.processClientMessage(this, messageClient); 
                }
            } catch (IOException e) {
                if (isActive()){ 
                    System.out.println("Déconnexion (IOException) de ClientHandler " + (nameIsSet ? nomJoueur : getRemoteAddressString()) + ": " + e.getMessage());
                }
                // else, socket was likely closed intentionally, and isActive is false.
            } catch (Exception e) { 
                System.err.println("Exception inattendue dans ClientHandler (TCP) pour " + (nameIsSet ? nomJoueur : getRemoteAddressString()) + ": " + e.getMessage());
                e.printStackTrace();
            }
            finally {
                boolean stillNeedsNotification = isActive(); // Was it an unexpected drop?
                socketActive = false; // Mark as definitively inactive now.
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                } catch (IOException e) { /* Ignored */ }
                
                if (stillNeedsNotification) { // If it dropped without a proper QUIT_GAME or server-side close.
                    server.handleClientQuitte(this);
                }
                System.out.println("Fin du thread pour le client TCP: " + (nameIsSet ? nomJoueur : "Socket " + getRemoteAddressString()));
            }
        }

        @Override
        public void sendMessage(String message) {
            if (isActive()) {
                out.println(message);
                if(out.checkError()) { // Check for errors after sending
                    System.err.println("Erreur PrintWriter pour TCP client " + getNomJoueur() + ". Fermeture.");
                    this.closeConnection(true); // Problem with stream, close and notify
                }
            }
        }
    } 

    class WebSocketClientConnection implements ClientConnection { //
        private final WebSocket webSocketConnection;
        private final BatailleNavaleServer server; 
        String nomJoueur = "JoueurAnonyme"; 
        int playerIndex = -1; 
        boolean nameIsSet = false; 
        private ClientHandler.ClientRole role = ClientHandler.ClientRole.PLAYER_IN_LOBBY; 
        volatile boolean active = true;


        public WebSocketClientConnection(WebSocket conn, BatailleNavaleServer server) {
            this.webSocketConnection = conn;
            this.server = server;
        }

        @Override public String getNomJoueur() { return nomJoueur; }
        @Override public boolean isNameSet() { return nameIsSet; }
        @Override public void setPlayerIndex(int index) { this.playerIndex = index; }
        @Override public int getPlayerIndex() { return playerIndex; }
        @Override public ClientHandler.ClientRole getRole() { return role; }
        @Override public void setRole(ClientHandler.ClientRole role) { this.role = role; }
        @Override public boolean isActive() { return active && webSocketConnection != null && webSocketConnection.isOpen(); }
        
        @Override public void sendMessage(String message) {
            if (isActive()) {
                try {
                    webSocketConnection.send(message);
                } catch (Exception e) { // Catch potential exceptions from send, e.g., if socket closes abruptly
                    System.err.println("Exception en envoyant un message WebSocket à " + getRemoteAddressString() + ": " + e.getMessage());
                    this.closeConnection(true); // Treat as a disconnect
                }
            }
        }
        @Override public void closeConnection(boolean notifyServer) {
            if (!active) return;
            active = false;
            if (webSocketConnection != null && webSocketConnection.isOpen()) {
                webSocketConnection.close();
            }
            System.out.println("Connexion WebSocket fermée pour " + getRemoteAddressString());
            if (notifyServer) {
                server.handleClientQuitte(this);
            }
        }
        @Override public String getRemoteAddressString() { 
            return webSocketConnection != null && webSocketConnection.getRemoteSocketAddress() != null ? 
                   webSocketConnection.getRemoteSocketAddress().toString() : "WebSocket (adresse inconnue)"; 
        }
        @Override public void resetForNewLobby() {
            this.nameIsSet = false;
            this.playerIndex = -1;
            this.role = ClientHandler.ClientRole.PLAYER_IN_LOBBY;
        }
        @Override public Object getUnderlyingHandle() { return webSocketConnection; }

    } 


    private static class GameWebSocketServer extends WebSocketServer { //
        private final BatailleNavaleServer mainServer; 

        public GameWebSocketServer(InetSocketAddress address, BatailleNavaleServer mainServer) {
            super(address);
            this.mainServer = mainServer;
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) { //
            System.out.println("Nouvelle connexion WebSocket de: " + conn.getRemoteSocketAddress());
            WebSocketClientConnection wsConnection = mainServer.new WebSocketClientConnection(conn, mainServer);
            conn.setAttachment(wsConnection); 

            synchronized (mainServer) { 
                if (mainServer.allClientConnections.size() >= MAX_PLAYERS_ALLOWED && !mainServer.gameInProgressFlag) {
                     if (!(mainServer.gameInProgressFlag && mainServer.allClientConnections.size() < MAX_PLAYERS_ALLOWED)) {
                        conn.send("ERROR:Serveur plein (max " + MAX_PLAYERS_ALLOWED + " participants).");
                        conn.close();
                        return;
                    }
                }
                mainServer.allClientConnections.add(wsConnection);
                System.out.println("Nouveau participant WebSocket connecté: " + conn.getRemoteSocketAddress() + ". Total connectés (tous types): " + mainServer.allClientConnections.size());


                if (mainServer.gameInProgressFlag && mainServer.game != null && mainServer.game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                    wsConnection.setRole(ClientHandler.ClientRole.SPECTATOR);
                     System.out.println("Client WS " + conn.getRemoteSocketAddress() + " est un spectateur potentiel (jeu en cours).");
                } else {
                    wsConnection.setRole(ClientHandler.ClientRole.PLAYER_IN_LOBBY);
                }
                if (!wsConnection.isNameSet()) { //
                    wsConnection.sendMessage("REQ_NAME");
                }
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) { //
            System.out.println("Connexion WebSocket fermée: " + (conn != null ? conn.getRemoteSocketAddress() : "unknown") + " Code: " + code + " Raison: " + reason + " Distant: " + remote);
            ClientConnection wsConnection = conn != null ? conn.getAttachment() : null;
            if (wsConnection instanceof WebSocketClientConnection) {
                ((WebSocketClientConnection)wsConnection).active = false; 
                mainServer.handleClientQuitte(wsConnection);
            } else if (conn != null) { // Fallback if attachment was lost or incorrect
                // This removal is less safe as it doesn't update 'active' flag before handleClientQuitte
                mainServer.allClientConnections.removeIf(c -> c.getUnderlyingHandle() == conn);
                System.err.println("ClientConnection non trouvé ou type incorrect pour WebSocket onClose: " + conn.getRemoteSocketAddress());
            }
        }

        @Override
        public void onMessage(WebSocket conn, String message) { //
            ClientConnection wsConnection = conn.getAttachment();
            if (wsConnection != null && wsConnection.isActive()) {
                mainServer.processClientMessage(wsConnection, message);
            } else {
                System.err.println("Message reçu sur une connexion WebSocket inactive ou sans attachement: " + conn.getRemoteSocketAddress());
            }
        }
        
        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            System.out.println("Message WebSocket binaire reçu de " + conn.getRemoteSocketAddress() + " - non géré.");
            if (conn != null && conn.isOpen()) {
                conn.send("ERROR:Messages binaires non supportés.");
            }
        }


        @Override
        public void onError(WebSocket conn, Exception ex) { //
            System.err.println("Erreur sur la connexion WebSocket " + (conn != null && conn.getRemoteSocketAddress() != null ? conn.getRemoteSocketAddress() : "(connexion inconnue)") + ": " + ex.getMessage());
            // ex.printStackTrace(); // For debugging
            if (conn != null) { // conn can be null if error is in server startup itself
                ClientConnection wsConnection = conn.getAttachment();
                if (wsConnection instanceof WebSocketClientConnection) {
                    ((WebSocketClientConnection)wsConnection).active = false;
                    // onClose will usually be called by the library after an error, which then calls handleClientQuitte.
                    // To be safe, ensure it's handled if onClose isn't guaranteed.
                    // However, calling it here and in onClose might lead to double processing if not careful.
                    // The library typically ensures onClose is called.
                } else if (conn.isOpen() || conn.isClosing()) { // If no proper attachment but conn exists
                     mainServer.allClientConnections.removeIf(c -> c.getUnderlyingHandle() == conn);
                     System.err.println("ClientConnection non trouvé ou type incorrect pour WebSocket onError: " + conn.getRemoteSocketAddress());
                }
                // Do not call conn.close() here directly, as the library manages the state after an error.
                // It will typically transition to onClose.
            }
        }

        @Override
        public void onStart() {
            System.out.println("GameWebSocketServer (composant interne de WebSocket) démarré avec succès sur le port " + getPort() + "!");
            setConnectionLostTimeout(0); 
            // setConnectionLostTimeout(60); // Example: 60 seconds timeout for dead connections
        }
    }


    public static void main(String[] args) {
        BatailleNavaleServer server = new BatailleNavaleServer();
        server.startServer();
    }
}