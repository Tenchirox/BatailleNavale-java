package com.example.bataillenavale.server; // Exemple de package

// package com.example.bataillenavale.server; // Exemple de package

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
    // Liste de tous les clients connectés au serveur (joueurs potentiels ou spectateurs)
    private final List<ClientHandler> connectedClients = new ArrayList<>();
    // Liste des clients participant activement à la partie en cours (rôle JOUEUR)
    private final List<ClientHandler> playersInGame = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private BatailleNavaleGame game;
    
    private static final int MIN_PLAYERS_TO_START_TIMER = 2; 
    private static final int MAX_PLAYERS_ALLOWED = 7; // Capacité totale du serveur (joueurs + spectateurs potentiels avant refus)
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
                synchronized (connectedClients) {
                    if (connectedClients.size() >= MAX_PLAYERS_ALLOWED) {
                        PrintWriter tempOut = new PrintWriter(clientSocket.getOutputStream(), true);
                        tempOut.println("ERROR:Serveur plein (max " + MAX_PLAYERS_ALLOWED + " participants).");
                        clientSocket.close();
                        continue;
                    }

                    ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                    connectedClients.add(clientHandler);
                    pool.execute(clientHandler);
                    System.out.println("Nouveau participant connecté: " + clientSocket.getRemoteSocketAddress() + ". Total connectés: " + connectedClients.size());

                    if (gameInProgressFlag && game != null && game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.TERMINE) {
                        clientHandler.setRole(ClientHandler.ClientRole.SPECTATOR);
                        clientHandler.sendMessage("SPECTATE_MODE");
                        // Envoyer l'état actuel du jeu aux spectateurs (simplifié : noms des joueurs et taille grille)
                        String allPlayerNamesStr = playersInGame.stream()
                                                               .map(ClientHandler::getNomJoueur)
                                                               .collect(Collectors.joining(","));
                        // SPECTATE_INFO:tailleGrille:nbJoueursEnJeu:nomJ0,nomJ1,...
                        clientHandler.sendMessage("SPECTATE_INFO:" + PlayerBoard.TAILLE_GRILLE + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
                        // TODO: Pourrait envoyer l'état actuel des grilles (coups déjà joués) aux spectateurs
                        System.out.println(clientHandler.getNomJoueur() + " (socket " + clientSocket.getPort() + ") a rejoint en tant que spectateur.");
                    } else {
                        clientHandler.setRole(ClientHandler.ClientRole.PLAYER_IN_LOBBY);
                        // Le client recevra REQ_NAME et enverra SET_NAME
                    }
                }
            } catch (IOException e) {
                System.err.println("Erreur d'acceptation client: " + e.getMessage());
            }
        }
    }

    public synchronized void playerHasSetName(ClientHandler clientHandler) {
        // Si le joueur définit son nom et qu'une partie est en cours où il pourrait être spectateur
        if (clientHandler.getRole() == ClientHandler.ClientRole.SPECTATOR) {
            broadcastLobbyState(); // Pour le chat, que les spectateurs voient aussi qui est qui
            // Envoyer les infos de la partie aux spectateurs, y compris le nom du joueur qui vient de se nommer
            String allPlayerNamesStr = playersInGame.stream()
                                                   .map(ClientHandler::getNomJoueur)
                                                   .collect(Collectors.joining(","));
            String allConnectedNamesStr = connectedClients.stream()
                                                        .filter(ClientHandler::isNameSet)
                                                        .map(ClientHandler::getNomJoueur)
                                                        .collect(Collectors.joining(","));
            
            // Informer tous les spectateurs de la liste des noms (y compris le nouveau spectateur)
            for(ClientHandler ch : connectedClients) {
                if (ch.getRole() == ClientHandler.ClientRole.SPECTATOR) {
                    // SPECTATE_INFO:tailleGrille:nbJoueursEnJeu:nomJ0,nomJ1,...:nomSpectateur1,nomSpectateur2...
                    // Pour l'instant, envoyons juste la liste des joueurs en jeu.
                    // Le chat gérera l'affichage des noms des spectateurs.
                    // Le client spectateur saura qui sont les joueurs via SPECTATE_INFO.
                }
            }
             // Envoyer les messages de chat existants (TODO: stocker et envoyer l'historique du chat)
            handleChatMessage(clientHandler, "[A rejoint le chat en tant que spectateur]");


            return; // Pas de logique de démarrage de jeu si c'est un spectateur
        }


        broadcastLobbyState(); 

        int namedPlayerCount = 0;
        synchronized (connectedClients) {
            for (ClientHandler ch : connectedClients) {
                if (ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) {
                    namedPlayerCount++;
                }
            }
        }

        if (!gameInProgressFlag && !lobbyCountdownActive && namedPlayerCount >= MIN_PLAYERS_TO_START_TIMER) {
            System.out.println(namedPlayerCount + " joueurs ont défini leur nom. Démarrage du compte à rebours du lobby.");
            startLobbyCountdown();
        } else if (!gameInProgressFlag && lobbyCountdownActive && namedPlayerCount == MAX_PLAYERS_ALLOWED) {
            // Vérifier si tous ceux dans connectedClients (qui ne sont pas déjà spectateurs) sont prêts
            boolean allPotentialPlayersReady = true;
            int potentialPlayerCount = 0;
            synchronized(connectedClients){
                for(ClientHandler ch : connectedClients){
                    if(ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY){
                        potentialPlayerCount++;
                        if(!ch.isNameSet()){
                            allPotentialPlayersReady = false;
                            break;
                        }
                    }
                }
            }
            if(allPotentialPlayersReady && potentialPlayerCount == MAX_PLAYERS_ALLOWED){ // Max joueurs du jeu, pas du serveur total
                System.out.println("Nombre maximum de joueurs (" + potentialPlayerCount + ") atteint et noms définis. Démarrage anticipé.");
                cancelLobbyCountdown();
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
                synchronized (connectedClients) { 
                    lobbyCountdownActive = false; 
                    if (gameInProgressFlag) return; 

                    int namedPlayerCount = 0;
                    for (ClientHandler ch : connectedClients) {
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
                player.setRole(ClientHandler.ClientRole.PLAYER_IN_GAME); // Mettre à jour le rôle
            }
        }
        
        String[] nomsJoueursEnPartie = new String[playersInGame.size()];
        for (int i = 0; i < playersInGame.size(); i++) {
            nomsJoueursEnPartie[i] = playersInGame.get(i).getNomJoueur();
        }
        
        game = new BatailleNavaleGame(nomsJoueursEnPartie);
        System.out.println("Partie de Bataille Navale démarrée avec : " + Arrays.toString(nomsJoueursEnPartie));

        String allPlayerNamesStr = Arrays.stream(nomsJoueursEnPartie).collect(Collectors.joining(","));

        // Informer les joueurs de la partie
        for (ClientHandler client : playersInGame) { 
            client.sendMessage("GAME_START:" + PlayerBoard.TAILLE_GRILLE + ":" + client.getPlayerIndex() + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
        }
        // Informer les spectateurs potentiels (ceux dans connectedClients mais pas dans playersInGame)
        synchronized(connectedClients) {
            for(ClientHandler ch : connectedClients) {
                if (!playersInGame.contains(ch)) { // Si c'est un spectateur
                    ch.setRole(ClientHandler.ClientRole.SPECTATOR);
                    ch.sendMessage("SPECTATE_MODE");
                    ch.sendMessage("SPECTATE_INFO:" + PlayerBoard.TAILLE_GRILLE + ":" + playersInGame.size() + ":" + allPlayerNamesStr);
                }
            }
        }

        passerAuPlacementSuivant();
    }

    public synchronized void handleAdminStartGame(ClientHandler adminClient) {
        if (connectedClients.isEmpty() || connectedClients.get(0) != adminClient) {
            adminClient.sendMessage("ERROR:Seul l'hôte (premier joueur connecté et ayant défini son nom) peut démarrer la partie.");
            return;
        }
        if (gameInProgressFlag) {
            adminClient.sendMessage("ERROR:La partie est déjà en cours ou en démarrage.");
            return;
        }
        
        int namedPlayerCount = 0;
        synchronized(connectedClients) {
            for(ClientHandler ch : connectedClients) {
                if(ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) namedPlayerCount++;
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
            resetServerForNewLobby(); 
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
             if (gameCurrentPlayerGlobalIndex < 0 || gameCurrentPlayerGlobalIndex >= game.getNombreJoueursInitial() ) { 
                System.err.println("Erreur: Index de joueur courant global invalide ("+gameCurrentPlayerGlobalIndex+") pour la phase de placement.");
                resetServerForNewLobby();
                return;
            }
            ClientHandler clientActif = getClientHandlerByGlobalIndexInGame(gameCurrentPlayerGlobalIndex);
            if (clientActif == null) {
                System.err.println("Erreur: Impossible de trouver le ClientHandler (en jeu) pour l'index global " + gameCurrentPlayerGlobalIndex);
                // Cela peut arriver si un joueur s'est déconnecté pendant le placement.
                // La logique de `game.placerNavireJoueurCourant` ou `passerAuJoueurSuivantPourPlacement`
                // dans BatailleNavaleGame devrait gérer l'élimination de joueurs et le passage correct.
                // Si on arrive ici, c'est un état potentiellement incohérent.
                resetServerForNewLobby();
                return;
            }


            List<Ship.ShipType> naviresAPlacer = game.getNaviresAPlacerPourJoueurCourant();

            if (!naviresAPlacer.isEmpty()) {
                Ship.ShipType prochainNavire = naviresAPlacer.get(0);
                clientActif.sendMessage("YOUR_TURN_PLACE_SHIP:" + prochainNavire.name() + ":" + prochainNavire.getTaille() + ":" + prochainNavire.getNom());
                broadcastSaufAUnJoueurEnPartie(clientActif,"WAIT_PLACEMENT:" + clientActif.getNomJoueur() + ":" + prochainNavire.getNom());
            } else {
                System.out.println("Joueur " + clientActif.getNomJoueur() + " a fini ses placements. Vérification pour le suivant ou phase de combat.");
                passerAuPlacementSuivant();
            }
        } else if (game.getPhaseActuelle() == BatailleNavaleGame.GamePhase.TERMINE) {
             System.out.println("Jeu terminé, pas de placement suivant.");
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
            if (!currentNaviresAPlacer.isEmpty()) {
                 Ship.ShipType prochainNavire = currentNaviresAPlacer.get(0);
                 client.sendMessage("YOUR_TURN_PLACE_SHIP:" + prochainNavire.name() + ":" + prochainNavire.getTaille() + ":" + prochainNavire.getNom());
            } else {
                 System.err.println("Erreur critique: Rejet de placement mais plus de navires à placer pour " + client.getNomJoueur());
            }
        }
    }

    private synchronized void informerTourCombat() {
        if (game == null || game.getPhaseActuelle() != BatailleNavaleGame.GamePhase.COMBAT) return;
        
        int joueurCourantGlobalIndex = game.getJoueurCourantIndex();
        if (joueurCourantGlobalIndex == -1 || playersInGame.isEmpty()) {
             System.err.println("Erreur dans informerTourCombat: Aucun joueur courant ou liste des joueurs en jeu vide.");
             resetServerForNewLobby();
             return;
        }
        
        ClientHandler clientActif = getClientHandlerByGlobalIndexInGame(joueurCourantGlobalIndex);
        if (clientActif == null) {
            System.err.println("Erreur dans informerTourCombat: ClientHandler non trouvé pour l'index global " + joueurCourantGlobalIndex);
            resetServerForNewLobby();
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
        if (targetPlayerGlobalIndex < 0 || targetPlayerGlobalIndex >= game.getNombreJoueursInitial() || targetPlayerGlobalIndex == clientTireur.getPlayerIndex()) {
            clientTireur.sendMessage("ERROR:Cible de tir invalide.");
            return;
        }
        if (game.getJoueursActifsIndices() != null && !game.getJoueursActifsIndices().contains(targetPlayerGlobalIndex)) { 
             clientTireur.sendMessage("ERROR:Ce joueur n'est plus actif ou a été éliminé.");
             clientTireur.sendMessage("YOUR_TURN_FIRE"); 
             return;
        }


        PlayerBoard.ShotResult resultat = game.tirerSurAdversaire(targetPlayerGlobalIndex, ligne, col);
        String nomJoueurCible = game.getPlayerBoard(targetPlayerGlobalIndex).getNomJoueur(); 
        int joueurTireurIndex = clientTireur.getPlayerIndex();

        String messageBase = "SHOT_RESULT:" + joueurTireurIndex + ":" + targetPlayerGlobalIndex + ":" + ligne + ":" + col + ":" + resultat.name();
        
        if (resultat == PlayerBoard.ShotResult.COULE) {
            Ship navireCoule = null;
            PlayerBoard boardCible = game.getPlayerBoard(targetPlayerGlobalIndex);
            for(Ship s : boardCible.getNavires()){
                boolean segmentToucheSurCeNavire = false;
                for(java.awt.Point p : s.getPositions()){
                    if(p.x == ligne && p.y == col) {
                        segmentToucheSurCeNavire = true;
                        break;
                    }
                }
                if(segmentToucheSurCeNavire && s.estCoule()){
                    navireCoule = s;
                    break;
                }
            }
            if(navireCoule != null){
                messageBase += ":" + navireCoule.getType().getNom();
            } else {
                 messageBase += ":UNKNOWN_SHIP"; 
            }
        }
        broadcastToAllParticipants(messageBase); // Envoyer aux joueurs ET aux spectateurs
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
        System.out.println("Participant " + client.getNomJoueur() + " (rôle: " + client.getRole() + ") a quitté/s'est déconnecté.");
        boolean clientEtaitConnecteGeneral = connectedClients.remove(client);
        boolean clientEtaitEnJeu = playersInGame.remove(client); 


        if (gameInProgressFlag && clientEtaitEnJeu) { 
            broadcastToPlayersInGame("PLAYER_LEFT:" + client.getNomJoueur()); 
            // Informer aussi les spectateurs
            synchronized(connectedClients) {
                for(ClientHandler spec : connectedClients) {
                    if (spec.getRole() == ClientHandler.ClientRole.SPECTATOR) {
                        spec.sendMessage("PLAYER_LEFT_GAME_SPECTATOR:" + client.getNomJoueur());
                    }
                }
            }

            System.out.println("La partie est terminée car un joueur ("+client.getNomJoueur()+") a quitté.");
            broadcastToPlayersInGame("GAME_OVER_DISCONNECT:" + client.getNomJoueur());
            synchronized(connectedClients) { // Informer les spectateurs de la fin de partie
                 for(ClientHandler spec : connectedClients) {
                    if (spec.getRole() == ClientHandler.ClientRole.SPECTATOR) {
                       spec.sendMessage("GAME_OVER_DISCONNECT_SPECTATOR:" + client.getNomJoueur());
                    }
                }
            }
            resetServerForNewLobby();
        } else if (clientEtaitConnecte) { 
            System.out.println("Un participant a quitté le lobby.");
            if (lobbyCountdownActive) {
                int namedPlayerCount = 0;
                for(ClientHandler ch : connectedClients) if(ch.isNameSet() && ch.getRole() == ClientHandler.ClientRole.PLAYER_IN_LOBBY) namedPlayerCount++;
                if (namedPlayerCount < MIN_PLAYERS_TO_START_TIMER) {
                    System.out.println("Moins de " + MIN_PLAYERS_TO_START_TIMER + " joueurs prêts restants, annulation du compte à rebours.");
                    cancelLobbyCountdown();
                }
            }
            broadcastLobbyState(); 
        }
    }

    public synchronized void handleChatMessage(ClientHandler sender, String message) {
        if (sender.isNameSet() && !message.trim().isEmpty()) {
            System.out.println("CHAT [" + sender.getNomJoueur() + "]: " + message);
            // Diffuser le message de chat à TOUS les clients connectés (joueurs et spectateurs)
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

        List<ClientHandler> clientsEncoreConnectes = new ArrayList<>();
        synchronized(connectedClients) { 
            clientsEncoreConnectes.addAll(connectedClients);
        }

        for (ClientHandler ch : clientsEncoreConnectes) {
            if (ch.isSocketActive()) {
                ch.resetForNewLobby(); 
                ch.sendMessage("REQ_NAME"); 
            } else {
                // Si le socket n'est plus actif, le retirer de connectedClients
                // Cela devrait déjà être géré par le finally du ClientHandler, mais double sécurité.
                connectedClients.remove(ch); // Attention ConcurrentModification si on itère sur connectedClients directement
                System.out.println("Client " + ch.getNomJoueur() + " (socket inactif) complètement retiré lors du reset.");
            }
        }
        
        if (connectedClients.isEmpty()) {
             System.out.println("Aucun client actif restant. En attente de nouvelles connexions.");
        } else {
            System.out.println(connectedClients.size() + " clients potentiels pour le nouveau lobby (doivent redonner leur nom).");
        }
        broadcastLobbyState();
    }


    private void broadcast(String message) { // Diffuse à TOUS les clients connectés
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

    private void broadcastToAllParticipants(String message) { // Joueurs en jeu ET spectateurs
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
            // L'index de jeu est maintenant l'index dans la liste playersInGame
            if (globalGameIndex >= 0 && globalGameIndex < playersInGame.size()) {
                 // On s'attend à ce que playerIndex du ClientHandler soit aussi cet index global de jeu
                 for(ClientHandler ch : playersInGame){
                     if(ch.getPlayerIndex() == globalGameIndex) return ch;
                 }
            }
        }
        System.err.println("getClientHandlerByGlobalIndexInGame: Aucun client trouvé pour l'index de jeu " + globalGameIndex);
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
            // LOBBY_STATE:nbNomsDefinisPourJeu:minPourDemarrerTimer:maxJoueursPourJeu:nomsDesJoueursAvecNomPourJeu
            broadcast("LOBBY_STATE:" + nombreDeNomsDefinisDansLobby + ":" + MIN_PLAYERS_TO_START_TIMER + ":" + MAX_PLAYERS_ALLOWED + ":" + nomsJoueursDansLobbyAyantNom);
        }
    }

    public static void main(String[] args) {
        BatailleNavaleServer server = new BatailleNavaleServer();
        server.startServer();
    }

    // --- Classe interne ClientHandler ---
    class ClientHandler implements Runnable {
        enum ClientRole { PLAYER_IN_LOBBY, PLAYER_IN_GAME, SPECTATOR }

        private Socket clientSocket;
        private BatailleNavaleServer server;
        private PrintWriter out;
        private BufferedReader in;
        private String nomJoueur = "JoueurAnonyme"; 
        private int playerIndex = -1; // Index dans la partie (0 à N-1), ou -1 si pas encore en jeu/spectateur
        private boolean nameIsSet = false; 
        private ClientRole role = ClientRole.PLAYER_IN_LOBBY; // Rôle initial

        public ClientHandler(Socket socket, BatailleNavaleServer server) { // Retrait de tempLobbyIndex
            this.clientSocket = socket;
            this.server = server;
            try {
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                out.println("REQ_NAME"); 
            } catch (IOException e) {
                System.err.println("Erreur I/O pour ClientHandler: " + e.getMessage());
            }
        }

        public String getNomJoueur() {
            return nomJoueur;
        }
        
        public boolean isNameSet() { 
            return nameIsSet;
        }
        
        public void setPlayerIndex(int index) { 
            this.playerIndex = index;
        }
        
        public int getPlayerIndex() {
            return playerIndex;
        }

        public ClientRole getRole() { return role; }
        public void setRole(ClientRole role) { this.role = role; }
        
        public boolean isSocketActive() {
            return clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected() && out != null && !out.checkError();
        }
        
        public void resetForNewLobby() {
            this.nameIsSet = false;
            this.playerIndex = -1; 
            this.role = ClientRole.PLAYER_IN_LOBBY; // Réinitialiser au rôle de lobby
        }


        @Override
        public void run() {
            try {
                String messageClient;
                while (isSocketActive() && (messageClient = in.readLine()) != null) {
                    System.out.println("Reçu de " + nomJoueur + " (rôle " + role + ", idx " + playerIndex + ", nameSet: "+nameIsSet+"): " + messageClient);
                    String[] parts = messageClient.split(":", 2);
                    String command = parts[0].toUpperCase();
                    String payload = (parts.length > 1) ? parts[1] : "";

                    switch (command) {
                        case "SET_NAME":
                            if (!payload.isEmpty()) {
                                boolean nameTaken = false;
                                synchronized(server.connectedClients) { 
                                    for(ClientHandler ch : server.connectedClients) {
                                        if (ch != this && ch.isNameSet() && ch.getNomJoueur().equalsIgnoreCase(payload)) {
                                            nameTaken = true;
                                            break;
                                        }
                                    }
                                }
                                if (nameTaken) {
                                    sendMessage("ERROR:Ce nom est déjà utilisé. Veuillez en choisir un autre.");
                                    this.nameIsSet = false; 
                                } else {
                                    this.nomJoueur = payload;
                                    this.nameIsSet = true; 
                                    System.out.println("Client (socket: "+clientSocket.getPort()+") s'appelle maintenant " + this.nomJoueur);
                                    server.playerHasSetName(this); 
                                }
                            } else {
                                sendMessage("ERROR:Le nom ne peut pas être vide.");
                            }
                            break;
                        case "PLACE_SHIP":
                            if (role != ClientRole.PLAYER_IN_GAME) { sendMessage("ERROR:Action non autorisée pour votre rôle."); break;}
                            if (!nameIsSet) { sendMessage("ERROR:Veuillez d'abord définir votre nom."); break; }
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
                            if (!nameIsSet) { sendMessage("ERROR:Veuillez d'abord définir votre nom."); break; }
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
                            if (!nameIsSet) { sendMessage("ERROR:Veuillez d'abord définir votre nom."); break; }
                            server.handleAdminStartGame(this);
                            break;
                        case "QUIT_GAME": // Géré par le client, le serveur le traite via handleClientQuitte
                            System.out.println("Client " + nomJoueur + " a envoyé QUIT_GAME. Fermeture de la connexion pour ce client.");
                            try { clientSocket.close(); } catch (IOException ignored) {}
                            return; 
                        case "CHAT_MSG": 
                            if (!nameIsSet) { sendMessage("ERROR:Veuillez d'abord définir votre nom pour chatter."); break; }
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
                    System.out.println("Déconnexion de " + nomJoueur + " (rôle " + role + ", nameSet: "+nameIsSet+"): " + e.getMessage());
                } else {
                    // System.out.println("Client " + nomJoueur + " (nameSet: "+nameIsSet+") déjà déconnecté ou socket fermé, erreur de lecture ignorée.");
                }
            } finally {
                server.handleClientQuitte(this);
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                } catch (IOException e) {
                    // System.err.println("Erreur à la fermeture des ressources du ClientHandler: " + e.getMessage());
                }
            }
        }

        public void sendMessage(String message) {
            if (isSocketActive()) {
                out.println(message);
            } else {
                // System.err.println("Impossible d'envoyer un message à " + nomJoueur + ", flux fermé ou socket fermé.");
            }
        }
    }
}
