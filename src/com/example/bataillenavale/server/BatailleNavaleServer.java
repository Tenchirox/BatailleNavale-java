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

public class BatailleNavaleServer {
    private static final int PORT = 12347;
    private ServerSocket serverSocket;
    private final List<ClientHandler> connectedClients = new ArrayList<>();
    private final List<ClientHandler> playersInGame = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private BatailleNavaleGame game;

    private static final int MIN_PLAYERS_TO_START_TIMER = 2;
    private static final int MAX_PLAYERS_ALLOWED = 7;
    private boolean gameInProgressFlag = false;

    private Timer lobbyCountdownTimer;
    private TimerTask currentLobbyCountdownTask;
    private static final long LOBBY_COUNTDOWN_MS = 20000; // 20 secondes
    private boolean lobbyCountdownActive = false;


    public BatailleNavaleServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Serveur Bataille Navale démarré sur le port " + PORT);
        } catch (IOException e) {
            System.err.println("Erreur au démarrage du serveur Bataille Navale: " + e.getMessage());
            System.exit(1);
        }
    }

    public void startServer() {
        System.out.println("En attente de connexions pour " + MIN_PLAYERS_TO_START_TIMER + " à " + MAX_PLAYERS_ALLOWED + " participants...");
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                synchronized (this) {
                    if (connectedClients.size() >= MAX_PLAYERS_ALLOWED && !gameInProgressFlag) {
                        if (!(gameInProgressFlag && connectedClients.size() < MAX_PLAYERS_ALLOWED)) {
                             PrintWriter tempOut = new PrintWriter(clientSocket.getOutputStream(), true);
                             tempOut.println("ERROR:Serveur plein (max " + MAX_PLAYERS_ALLOWED + " participants).");
                             clientSocket.close();
                             continue;
                        }
                    }

                    ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                    connectedClients.add(clientHandler);
                    pool.execute(clientHandler);
                    System.out.println("Nouveau participant connecté: " + clientSocket.getRemoteSocketAddress() + ". Total connectés: " + connectedClients.size());

                    if (gameInProgressFlag && game != null && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                        clientHandler.setRole(ClientHandler.ClientRole.SPECTATOR);
                        System.out.println("Client " + clientSocket.getRemoteSocketAddress() + " est un spectateur potentiel (jeu en cours).");
                        // Le client enverra SET_NAME, et playerHasSetName gérera l'envoi de SPECTATE_MODE et SPECTATE_INFO.
                    } else {
                        clientHandler.setRole(ClientHandler.ClientRole.PLAYER_IN_LOBBY);
                    }
                }
            } catch (IOException e) {
                System.err.println("Erreur d'acceptation client: " + e.getMessage());
            }
        }
    }

    public synchronized void playerHasSetName(ClientHandler clientHandler) {
        if (clientHandler.getRole() == ClientHandler.ClientRole.SPECTATOR) {
            System.out.println("Spectateur " + clientHandler.getNomJoueur() + " a défini son nom.");

            // CORRECTION : Gérer différemment si une partie est active ou non.
            if (gameInProgressFlag && game != null && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                // Partie active: Envoyer les informations de spectateur UNIQUEMENT à ce client.
                // NE PAS appeler broadcastLobbyState() ici pour éviter d'interrompre les joueurs.
                clientHandler.sendMessage("SPECTATE_MODE");
                String allPlayerNamesStr = playersInGame.stream()
                                                      .map(ClientHandler::getNomJoueur)
                                                      .collect(Collectors.joining(","));
                clientHandler.sendMessage("SPECTATE_INFO:" + PlayerBoard.TAILLE_GRILLE + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
                handleChatMessage(clientHandler, "[A rejoint le chat en tant que spectateur]");
                System.out.println(clientHandler.getNomJoueur() + " a reçu les infos pour spectateur.");
            } else {
                // Partie non active (ou terminée) : Ce "spectateur" devient un joueur dans le lobby.
                System.out.println("Jeu non en cours ou terminé. " + clientHandler.getNomJoueur() + " devient joueur dans le lobby.");
                clientHandler.setRole(ClientHandler.ClientRole.PLAYER_IN_LOBBY);
                // Maintenant, traiter ce client comme n'importe quel autre joueur du lobby qui vient de définir son nom.
                // La suite de cette méthode (hors de ce bloc 'if') s'en chargera.
                // On appelle broadcastLobbyState() et la logique de démarrage de partie ci-dessous.
                broadcastLobbyState(); // Informer tout le monde de l'état du lobby
                handleChatMessage(clientHandler, "[A rejoint le chat du lobby]");
                // La logique de comptage et de démarrage de partie suivra.
            }
             // Si le client était spectateur d'une partie active, on retourne pour ne pas exécuter la logique du lobby.
            if (clientHandler.getRole() == ClientHandler.ClientRole.SPECTATOR && gameInProgressFlag) {
                return;
            }
        }

        // Ce code s'exécute si le client est PLAYER_IN_LOBBY
        // (soit initialement, soit après avoir été re-rolé depuis SPECTATOR car aucune partie n'était active)
        if (clientHandler.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
             // S'il n'a pas été appelé juste au-dessus (cas du spectateur devenant lobby player)
             if (!(gameInProgressFlag && clientHandler.getRole() == ClientHandler.ClientRole.SPECTATOR)) { // Eviter double broadcast si transition
                 broadcastLobbyState(); // Assurer que l'état du lobby est diffusé
             }

            int namedPlayerCount = 0;
            synchronized (connectedClients) {
                for (ClientHandler ch : connectedClients) {
                    if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
                        namedPlayerCount++;
                    }
                }
            }
            System.out.println("Joueurs avec nom dans le lobby: " + namedPlayerCount);

            if (!gameInProgressFlag && !lobbyCountdownActive && namedPlayerCount >= MIN_PLAYERS_TO_START_TIMER) {
                System.out.println(namedPlayerCount + " joueurs ont défini leur nom. Démarrage du compte à rebours du lobby.");
                startLobbyCountdown();
            } else if (!gameInProgressFlag && namedPlayerCount >= MAX_PLAYERS_ALLOWED ) { // Si le lobby est plein (et >= min), démarrer direct
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
                synchronized (BatailleNavaleServer.this) { // Synchroniser sur l'instance du serveur
                    lobbyCountdownActive = false;
                    if (gameInProgressFlag) return;

                    int namedPlayerCount = 0;
                    for (ClientHandler ch : connectedClients) { // Pas besoin de copier ici grâce à la synchro externe
                        if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) namedPlayerCount++;
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

        List<ClientHandler> joueursPretsPourPartie = new ArrayList<>();
        synchronized (connectedClients) {
            for (ClientHandler ch : connectedClients) {
                if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY && ch.isSocketActive()) {
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

        synchronized(playersInGame) {
            playersInGame.clear();
            playersInGame.addAll(joueursPretsPourPartie);
            for(int i=0; i < playersInGame.size(); i++) { 
                ClientHandler player = playersInGame.get(i);
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

        for (ClientHandler client : playersInGame) {
            client.sendMessage("GAME_START:" + PlayerBoard.TAILLE_GRILLE + ":" + client.getPlayerIndex() + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
        }
        
        synchronized(connectedClients) {
            for(ClientHandler ch : connectedClients) {
                if (!playersInGame.contains(ch)) { 
                    ch.setRole(ClientHandler.ClientRole.SPECTATOR); 
                    if (ch.isNameSet()) { 
                        ch.sendMessage("SPECTATE_MODE");
                        ch.sendMessage("SPECTATE_INFO:" + PlayerBoard.TAILLE_GRILLE + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
                         System.out.println(ch.getNomJoueur() + " est maintenant spectateur de la nouvelle partie.");
                    } else {
                        ch.sendMessage("REQ_NAME"); 
                    }
                }
            }
        }
        passerAuPlacementSuivant();
    }

    public synchronized void handleAdminStartGame(ClientHandler adminClient) {
        boolean isAdminHost = false;
        synchronized(connectedClients) {
            if (!connectedClients.isEmpty()) {
                ClientHandler firstPotentialHost = null;
                for (ClientHandler ch_loop : connectedClients) {
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
        synchronized(connectedClients) {
            for(ClientHandler ch_loop : connectedClients) {
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

            ClientHandler clientActif = getClientHandlerByGlobalIndexInGame(gameCurrentPlayerGlobalIndex);
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

    public synchronized void handlePlacementNavire(ClientHandler client, Ship.ShipType type, int ligne, int col, boolean horizontal) {
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

        ClientHandler clientActif = getClientHandlerByGlobalIndexInGame(joueurCourantGlobalIndex);
        if (clientActif == null) {
            System.err.println("Erreur dans informerTourCombat: ClientHandler non trouvé pour l'index global " + joueurCourantGlobalIndex + ".");
            game.passerAuJoueurSuivantPourCombat(); 
            informerTourCombat(); 
            return;
        }

        clientActif.sendMessage("YOUR_TURN_FIRE");
        broadcastSaufAUnJoueurEnPartie(clientActif, "OPPONENT_TURN_FIRE:" + clientActif.getNomJoueur());
        System.out.println("Phase de combat: Au tour de " + clientActif.getNomJoueur());
    }

    public synchronized void handleTir(ClientHandler clientTireur, int targetPlayerGlobalIndex, int ligne, int col) {
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
            for(Ship s : boardCibleEffective.getNavires()){
                if(s.estCoule()){ 
                    boolean segmentToucheSurCeNavire = false;
                    for(java.awt.Point p : s.getPositions()){ 
                        if(p.x == ligne && p.y == col) {
                            segmentToucheSurCeNavire = true;
                            break;
                        }
                    }
                    if (segmentToucheSurCeNavire) {
                        navireCouleDetecte = s;
                        break;
                    }
                    if (navireCouleDetecte == null) navireCouleDetecte = s; 
                }
            }
            if(navireCouleDetecte != null){
                messageBase += ":" + navireCouleDetecte.getType().getNom();
            } else {
                 messageBase += ":UNKNOWN_SHIP";
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

    public synchronized void handleClientQuitte(ClientHandler client) {
        System.out.println("Déconnexion demandée/détectée pour: " + client.getNomJoueur() + " (rôle: " + client.getRole() + ", index: " + client.getPlayerIndex() + ")");

        boolean clientEtaitDansConnectedClients = connectedClients.remove(client);
        boolean clientEtaitDansPlayersInGame = playersInGame.remove(client);

        if (gameInProgressFlag && clientEtaitDansPlayersInGame && game != null) {
            System.out.println("Joueur " + client.getNomJoueur() + " a quitté une partie en cours.");
            boolean gamePeutContinuer = game.handlePlayerDisconnect(client.getPlayerIndex());

            if (gamePeutContinuer && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                broadcastToAllParticipants("PLAYER_LEFT:" + client.getNomJoueur() + ":" + client.getPlayerIndex());
                System.out.println("La partie continue sans " + client.getNomJoueur() + ".");
                if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.PLACEMENT_BATEAUX) {
                    passerAuPlacementSuivant();
                } else if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.COMBAT) {
                    informerTourCombat();
                }
            } else {
                String raisonFin = game.getGagnantIndex() != -1 && game.getPlayerBoard(game.getGagnantIndex()) != null ?
                                   game.getPlayerBoard(game.getGagnantIndex()).getNomJoueur() :
                                   (client.getNomJoueur() + " (déconnexion)");

                String messageFin = game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.TERMINE && game.getGagnantIndex() != -1 && game.getPlayerBoard(game.getGagnantIndex()) != null ?
                                   "GAME_OVER:" + game.getPlayerBoard(game.getGagnantIndex()).getNomJoueur() + ":" + game.getGagnantIndex():
                                   "GAME_OVER_DISCONNECT:" + client.getNomJoueur();

                broadcastToAllParticipants(messageFin);
                System.out.println("Partie terminée. Raison approx: " + raisonFin);
                resetServerForNewLobby();
            }
        } else if (clientEtaitDansConnectedClients) { 
            System.out.println("Participant " + client.getNomJoueur() + " a quitté (hors partie active).");
            if (client.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY && lobbyCountdownActive) {
                int playerCountInLobby = 0;
                synchronized(connectedClients) { 
                    for (ClientHandler ch : connectedClients) {
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
            System.out.println("Client " + client.getNomJoueur() + " non trouvé dans les listes actives (peut-être déjà retiré).");
        }
    }

    public synchronized void handleChatMessage(ClientHandler sender, String message) {
        if (sender.isNameSet() && !message.trim().isEmpty()) {
            System.out.println("CHAT [" + sender.getNomJoueur() + "]: " + message);
            broadcast("NEW_CHAT_MSG:" + sender.getNomJoueur() + ":" + message);
        } else if (!sender.isNameSet()){
            sender.sendMessage("ERROR:Vous devez définir votre nom pour envoyer des messages dans le chat.");
        }
    }

    private synchronized void resetServerForNewLobby() {
        System.out.println("Réinitialisation du serveur pour un nouveau lobby.");
        game = null; 
        gameInProgressFlag = false;
        cancelLobbyCountdown();
        playersInGame.clear();

        List<ClientHandler> clientsASynchroniser;
        synchronized (connectedClients) {
            clientsASynchroniser = new ArrayList<>(connectedClients);
        }

        for (ClientHandler ch : clientsASynchroniser) {
            if (ch.isSocketActive()) {
                ch.resetForNewLobby();
                ch.sendMessage("REQ_NAME"); 
            }
        }
        synchronized (connectedClients) { 
            if (connectedClients.isEmpty()) {
                 System.out.println("Aucun client actif restant après reset. En attente de nouvelles connexions.");
            } else {
                System.out.println(connectedClients.size() + " clients potentiels pour le nouveau lobby (doivent redonner leur nom).");
            }
        }
        broadcastLobbyState(); 
    }
    
    private void broadcast(String message) {
        List<ClientHandler> clientsSnapshot;
        synchronized (connectedClients) {
            clientsSnapshot = new ArrayList<>(connectedClients);
        }
        for (ClientHandler client : clientsSnapshot) {
            if (client != null && client.isSocketActive()) client.sendMessage(message);
        }
    }
    
    private void broadcastToPlayersInGame(String message) { 
        List<ClientHandler> gamePlayersSnapshot;
        synchronized (playersInGame) {
            gamePlayersSnapshot = new ArrayList<>(playersInGame);
        }
        for (ClientHandler client : gamePlayersSnapshot) {
            if (client != null && client.isSocketActive()) client.sendMessage(message);
        }
    }

    private void broadcastToAllParticipants(String message) { 
        List<ClientHandler> participants = new ArrayList<>();
        synchronized(playersInGame) { 
            participants.addAll(playersInGame);
        }
        synchronized(connectedClients) { 
            for(ClientHandler ch : connectedClients) {
                if(ch.getRole() == ClientHandler.ClientRole.SPECTATOR && !participants.contains(ch)) {
                    participants.add(ch);
                }
            }
        }
        for (ClientHandler participant : participants) {
            if (participant != null && participant.isSocketActive()) participant.sendMessage(message);
        }
    }

    private void broadcastSaufAUnJoueurEnPartie(ClientHandler exclure, String message) {
        List<ClientHandler> gamePlayersSnapshot;
        synchronized (playersInGame) {
            gamePlayersSnapshot = new ArrayList<>(playersInGame);
        }
        for (ClientHandler client : gamePlayersSnapshot) {
            if (client != null && client != exclure && client.isSocketActive()) {
                client.sendMessage(message);
            }
        }
    }
    
    private ClientHandler getClientHandlerByGlobalIndexInGame(int globalGameIndex) {
        synchronized(playersInGame) {
            for(ClientHandler ch : playersInGame){
                 if(ch.getPlayerIndex() == globalGameIndex && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_GAME) {
                     return ch;
                 }
            }
        }
        return null;
    }
    
    private int nombreJoueursInitialDeLaPartie() {
        if (game != null) {
            return game.getNombreJoueursInitial(); 
        }
        return 0;
    }
    
    private void broadcastLobbyState() {
        synchronized(connectedClients) { 
            String nomsJoueursDansLobbyAyantNom = connectedClients.stream()
                                             .filter(ch -> ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) 
                                             .map(ClientHandler::getNomJoueur)
                                             .collect(Collectors.joining(","));
            long nombreDeNomsDefinisDansLobby = connectedClients.stream()
                                             .filter(ch -> ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY)
                                             .count();
            broadcast("LOBBY_STATE:" + nombreDeNomsDefinisDansLobby + ":" + MIN_PLAYERS_TO_START_TIMER + ":" + MAX_PLAYERS_ALLOWED + ":" + nomsJoueursDansLobbyAyantNom);
        }
    }

    public static void main(String[] args) {
        BatailleNavaleServer server = new BatailleNavaleServer();
        server.startServer();
    }

    static class ClientHandler implements Runnable {
        enum ClientRole { PLAYER_IN_LOBBY, PLAYER_IN_GAME, SPECTATOR }

        private Socket clientSocket;
        private BatailleNavaleServer server;
        private PrintWriter out;
        private BufferedReader in;
        private String nomJoueur = "JoueurAnonyme";
        private int playerIndex = -1;
        private boolean nameIsSet = false;
        private ClientRole role = ClientRole.PLAYER_IN_LOBBY;

        public ClientHandler(Socket socket, BatailleNavaleServer server) {
            this.clientSocket = socket;
            this.server = server;
            try {
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            } catch (IOException e) {
                System.err.println("Erreur I/O pour ClientHandler: " + e.getMessage());
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                } catch (IOException ex) { /* Ignored */ }
            }
        }

        public String getNomJoueur() { return nomJoueur; }
        public boolean isNameSet() { return nameIsSet; }
        public void setPlayerIndex(int index) { this.playerIndex = index; }
        public int getPlayerIndex() { return playerIndex; }
        public ClientRole getRole() { return role; }
        public void setRole(ClientRole role) { this.role = role; }
        public boolean isSocketActive() {
            return clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected() && out != null && !out.checkError();
        }
        public void resetForNewLobby() {
            this.nameIsSet = false;
            this.playerIndex = -1;
            this.role = ClientRole.PLAYER_IN_LOBBY;
        }

        @Override
        public void run() {
            try {
                synchronized(server) { // S'assurer que le rôle initial est bien géré par rapport à l'état du serveur
                    if (server.gameInProgressFlag && server.game != null && server.game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                        if (this.role != ClientRole.SPECTATOR) { // Si pas déjà mis en spectateur par startServer (ex: reconnexion rapide)
                             this.role = ClientRole.SPECTATOR;
                             System.out.println("ClientHandler pour " + clientSocket.getRemoteSocketAddress() + " passe en mode SPECTATOR (jeu en cours).");
                        }
                    } else {
                        if (this.role != ClientRole.PLAYER_IN_LOBBY) { // Si pas déjà en lobby (ex: jeu vient de finir)
                            this.role = ClientRole.PLAYER_IN_LOBBY;
                            System.out.println("ClientHandler pour " + clientSocket.getRemoteSocketAddress() + " passe en mode PLAYER_IN_LOBBY.");
                        }
                    }
                }


                if (!nameIsSet) { // Si le nom n'a pas encore été défini par le client
                     sendMessage("REQ_NAME");
                }


                String messageClient;
                while (isSocketActive() && (messageClient = in.readLine()) != null) {
                    System.out.println("Reçu de " + (nameIsSet ? nomJoueur : clientSocket.getRemoteSocketAddress()) + " (rôle " + role + ", idx " + playerIndex + ", nameSet: "+nameIsSet+"): " + messageClient);
                    String[] parts = messageClient.split(":", 2);
                    String command = parts[0].toUpperCase();
                    String payload = (parts.length > 1) ? parts[1] : "";

                    if (command.equals("SET_NAME")) {
                        String potentialName = payload.trim();
                        if (!potentialName.isEmpty() && potentialName.length() <= 15) {
                            boolean nameTaken = false;
                            synchronized(server.connectedClients) {
                                for(ClientHandler ch : server.connectedClients) {
                                    if (ch != this && ch.isNameSet() && ch.getNomJoueur().equalsIgnoreCase(potentialName)) {
                                        nameTaken = true;
                                        break;
                                    }
                                }
                            }
                            if (nameTaken) {
                                sendMessage("ERROR:Ce nom est déjà utilisé. Veuillez en choisir un autre.");
                                sendMessage("REQ_NAME"); 
                            } else {
                                this.nomJoueur = potentialName;
                                this.nameIsSet = true;
                                System.out.println("Client (socket: "+clientSocket.getPort()+") s'appelle maintenant " + this.nomJoueur);
                                server.playerHasSetName(this);
                            }
                        } else {
                            sendMessage("ERROR:Le nom ne peut pas être vide et doit faire 15 caractères max.");
                            if (!nameIsSet) sendMessage("REQ_NAME");
                        }
                        continue;
                    }

                    if (!nameIsSet && !command.equals("QUIT_GAME")) { 
                        sendMessage("ERROR:Veuillez d'abord définir votre nom avec SET_NAME:votreNom.");
                        sendMessage("REQ_NAME"); 
                        continue;
                    }

                    switch (command) {
                        case "PLACE_SHIP":
                            if (role != ClientRole.PLAYER_IN_GAME) { sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                            String[] placementArgs = payload.split(":");
                            if (placementArgs.length == 4) {
                                try {
                                    Ship.ShipType type = Ship.ShipType.valueOf(placementArgs[0].toUpperCase());
                                    int ligne = Integer.parseInt(placementArgs[1]);
                                    int col = Integer.parseInt(placementArgs[2]);
                                    boolean horizontal = Boolean.parseBoolean(placementArgs[3]);
                                    server.handlePlacementNavire(this, type, ligne, col, horizontal);
                                } catch (IllegalArgumentException e) {
                                    sendMessage("ERROR:Arguments de placement invalides. " + e.getMessage());
                                }
                            } else {
                                 sendMessage("ERROR:Commande PLACE_SHIP malformée.");
                            }
                            break;
                        case "FIRE_SHOT":
                            if (role != ClientRole.PLAYER_IN_GAME) { sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                            String[] tirArgs = payload.split(":");
                            if (tirArgs.length == 3) {
                                try {
                                    int targetIdx = Integer.parseInt(tirArgs[0]);
                                    int ligne = Integer.parseInt(tirArgs[1]);
                                    int col = Integer.parseInt(tirArgs[2]);
                                    server.handleTir(this, targetIdx, ligne, col);
                                } catch (NumberFormatException e) {
                                    sendMessage("ERROR:Coordonnées de tir ou index cible invalides.");
                                }
                            } else {
                                sendMessage("ERROR:Commande FIRE_SHOT malformée (attendu: FIRE_SHOT:targetIdx:ligne:col).");
                            }
                            break;
                        case "ADMIN_START_GAME":
                            if (role != ClientRole.PLAYER_IN_LOBBY) { sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                            server.handleAdminStartGame(this);
                            break;
                        case "QUIT_GAME":
                            System.out.println("Client " + nomJoueur + " a envoyé QUIT_GAME.");
                            try { if(clientSocket != null && !clientSocket.isClosed()) clientSocket.close(); } catch (IOException ignored) {}
                            return; 
                        case "CHAT_MSG":
                            if (!payload.isEmpty()) {
                                server.handleChatMessage(this, payload);
                            }
                            break;
                        default:
                            sendMessage("ERROR:Commande inconnue '" + command + "'.");
                            break;
                    }
                }
            } catch (IOException e) {
                if (isSocketActive()){
                    System.out.println("Déconnexion (IOException) de " + (nameIsSet ? nomJoueur : clientSocket.getRemoteSocketAddress()) + ": " + e.getMessage());
                }
            } catch (Exception e) {
                System.err.println("Exception inattendue dans ClientHandler pour " + (nameIsSet ? nomJoueur : clientSocket.getRemoteSocketAddress()) + ": " + e.getMessage());
                e.printStackTrace();
            }
            finally {
                server.handleClientQuitte(this); 
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                } catch (IOException e) { /* Ignored */ }
                System.out.println("Fin du thread pour le client: " + (nameIsSet ? nomJoueur : "Socket " + clientSocket.getRemoteSocketAddress()));
            }
        }

        public void sendMessage(String message) {
            if (isSocketActive()) {
                out.println(message);
            }
        }
    }
    // --- FIN DU CODE A REPRENDRE ---
}