// server.js

// Dépendances
const net = require('net'); // Pour le serveur TCP
const { WebSocketServer } = require('ws'); // Pour le serveur WebSocket
const { v4: uuidv4 } = require('uuid'); // Pour donner un ID unique à chaque connexion

// =======================================================================================
// 1. CONSTANTES ET CONFIGURATION
// =======================================================================================

const TCP_PORT = 12351; //client lourd
const WS_PORT = 12352; //client web

const MIN_PLAYERS_TO_START_TIMER = 2;
const MAX_PLAYERS_ALLOWED = 7;
const LOBBY_COUNTDOWN_MS = 20000; // 20 secondes

const GamePhase = {
    PLACEMENT_BATEAUX: 'PLACEMENT_BATEAUX',
    COMBAT: 'COMBAT',
    TERMINE: 'TERMINE'
};

const ClientRole = {
    PLAYER_IN_LOBBY: 'PLAYER_IN_LOBBY',
    PLAYER_IN_GAME: 'PLAYER_IN_GAME',
    SPECTATOR: 'SPECTATOR'
};

const ShotResult = {
    MANQUE: 'MANQUE',
    TOUCHE: 'TOUCHE',
    COULE: 'COULE',
    DEJA_JOUE: 'DEJA_JOUE',
    ERREUR: 'ERREUR'
};

// =======================================================================================
// 2. CLASSES DU MODÈLE DE JEU (transcodées depuis Java)
// =======================================================================================

/**
 * Ship.java -> class Ship
 */
class Ship {
    static ShipType = {
        PORTE_AVIONS: { nom: "Porte-avions", taille: 5 },
        CROISEUR: { nom: "Croiseur", taille: 4 },
        CONTRE_TORPILLEUR: { nom: "Contre-torpilleur", taille: 3 },
        SOUS_MARIN: { nom: "Sous-marin", taille: 3 },
        TORPILLEUR: { nom: "Torpilleur", taille: 2 }
    };

    constructor(type) {
        this.type = type;
        this.positions = []; // Array of {x, y} points
        this.hits = new Array(type.taille).fill(false);
        this.estHorizontal = false;
        this.explicitementCoule = false;
    }

    getTaille() { return this.type.taille; }
    getTypeName() { return this.type.nom; }
    getEnumName() {
        for (const key in Ship.ShipType) {
            if (Ship.ShipType[key] === this.type) {
                return key;
            }
        }
        return 'UNKNOWN';
    }

    registerHit(shotPosition) {
        if (this.explicitementCoule) return false;
        const hitIndex = this.positions.findIndex(p => p.x === shotPosition.x && p.y === shotPosition.y);
        if (hitIndex !== -1 && !this.hits[hitIndex]) {
            this.hits[hitIndex] = true;
            return true; // Touché pour la première fois
        }
        return false; // Déjà touché ou pas sur ce navire
    }

    isSunk() {
        if (this.explicitementCoule) return true;
        if (this.positions.length === 0) return false;
        return this.hits.every(h => h === true);
    }
}

/**
 * PlayerBoard.java -> class PlayerBoard
 */
class PlayerBoard {
    static TAILLE_GRILLE = 10;
    static CASE_VIDE = ' ';
    static CASE_NAVIRE = 'N';
    static CASE_NAVIRE_TOUCHE = 'X';
    static CASE_MANQUE = 'O';

    constructor(nomJoueur) {
        this.nomJoueur = nomJoueur;
        this.grille = Array.from({ length: PlayerBoard.TAILLE_GRILLE }, () =>
            Array(PlayerBoard.TAILLE_GRILLE).fill(PlayerBoard.CASE_VIDE)
        );
        this.navires = [];
        this.aAbandonne = false;
    }

    placerNavire(navire, ligne, colonne, horizontal) {
        if (this.aAbandonne) return false;

        const positionsPotentielles = [];
        for (let i = 0; i < navire.getTaille(); i++) {
            const currentLigne = horizontal ? ligne : ligne + i;
            const currentCol = horizontal ? colonne + i : colonne;

            // Vérifier les limites de la grille
            if (currentLigne >= PlayerBoard.TAILLE_GRILLE || currentCol >= PlayerBoard.TAILLE_GRILLE) {
                return false;
            }
            // Vérifier les chevauchements
            if (this.grille[currentLigne][currentCol] !== PlayerBoard.CASE_VIDE) {
                return false;
            }
            positionsPotentielles.push({ x: currentLigne, y: currentCol });
        }

        navire.positions = positionsPotentielles;
        navire.estHorizontal = horizontal;
        this.navires.push(navire);
        positionsPotentielles.forEach(p => {
            this.grille[p.x][p.y] = PlayerBoard.CASE_NAVIRE;
        });
        return true;
    }

    recevoirTir(ligne, colonne) {
        if (this.aAbandonne || ligne < 0 || ligne >= PlayerBoard.TAILLE_GRILLE || colonne < 0 || colonne >= PlayerBoard.TAILLE_GRILLE) {
            return ShotResult.ERREUR;
        }

        const etatCase = this.grille[ligne][colonne];
        if (etatCase === PlayerBoard.CASE_NAVIRE_TOUCHE || etatCase === PlayerBoard.CASE_MANQUE) {
            return ShotResult.DEJA_JOUE;
        }

        if (etatCase === PlayerBoard.CASE_NAVIRE) {
            this.grille[ligne][colonne] = PlayerBoard.CASE_NAVIRE_TOUCHE;
            for (const navire of this.navires) {
                if (navire.registerHit({ x: ligne, y: colonne })) {
                    if (navire.isSunk()) {
                        return ShotResult.COULE;
                    }
                    return ShotResult.TOUCHE;
                }
            }
            return ShotResult.TOUCHE; // Should have been caught by the loop, but as a fallback
        }

        if (etatCase === PlayerBoard.CASE_VIDE) {
            this.grille[ligne][colonne] = PlayerBoard.CASE_MANQUE;
            return ShotResult.MANQUE;
        }
        return ShotResult.ERREUR;
    }

    tousNaviresCoules() {
        if (this.aAbandonne) return true;
        if (this.navires.length === 0) return false;
        return this.navires.every(n => n.isSunk());
    }
}


/**
 * BatailleNavaleGame.java -> class BatailleNavaleGame
 */
class BatailleNavaleGame {
    constructor(nomsJoueurs) {
        this.nombreJoueursInitial = nomsJoueurs.length;
        this.playerBoards = nomsJoueurs.map(nom => new PlayerBoard(nom));
        this.joueursActifsIndices = nomsJoueurs.map((_, i) => i);
        this.joueurCourantIndexDansListeActifs = 0;
        this.phaseActuelle = GamePhase.PLACEMENT_BATEAUX;
        this.gagnantIndex = -1;

        const typesDeNaviresAPlacer = Object.values(Ship.ShipType);
        this.naviresRestantsAPlacerParJoueur = nomsJoueurs.map(() => [...typesDeNaviresAPlacer]);
    }

    getJoueurCourantIndex() {
        if (this.phaseActuelle === GamePhase.TERMINE || this.joueursActifsIndices.length === 0) {
            return -1;
        }
        return this.joueursActifsIndices[this.joueurCourantIndexDansListeActifs];
    }

    getNaviresAPlacerPourJoueurCourant() {
        const joueurIdx = this.getJoueurCourantIndex();
        if (joueurIdx !== -1 && this.phaseActuelle === GamePhase.PLACEMENT_BATEAUX) {
            return this.naviresRestantsAPlacerParJoueur[joueurIdx];
        }
        return [];
    }

    placerNavireJoueurCourant(type, ligne, col, horizontal) {
        const joueurIdx = this.getJoueurCourantIndex();
        if (joueurIdx === -1 || this.phaseActuelle !== GamePhase.PLACEMENT_BATEAUX) return false;

        const naviresAPlacer = this.naviresRestantsAPlacerParJoueur[joueurIdx];
        const typeIndex = naviresAPlacer.findIndex(t => t.nom === type.nom);
        if (typeIndex === -1) return false;

        const nouveauNavire = new Ship(type);
        if (this.playerBoards[joueurIdx].placerNavire(nouveauNavire, ligne, col, horizontal)) {
            naviresAPlacer.splice(typeIndex, 1); // Retirer le navire placé

            if (this.tousLesJoueursActifsOntPlaceLeursNavires()) {
                this.phaseActuelle = GamePhase.COMBAT;
                this.joueurCourantIndexDansListeActifs = 0;
            }
            return true;
        }
        return false;
    }

    tousLesJoueursActifsOntPlaceLeursNavires() {
        return this.joueursActifsIndices.every(idx => this.naviresRestantsAPlacerParJoueur[idx].length === 0);
    }
    
    passerAuJoueurSuivantPourPlacement() {
        if (this.phaseActuelle !== GamePhase.PLACEMENT_BATEAUX) return;
        
        const initialIndex = this.joueurCourantIndexDansListeActifs;
        do {
            this.joueurCourantIndexDansListeActifs = (this.joueurCourantIndexDansListeActifs + 1) % this.joueursActifsIndices.length;
            const joueurGlobalSuivant = this.joueursActifsIndices[this.joueurCourantIndexDansListeActifs];
            if (this.naviresRestantsAPlacerParJoueur[joueurGlobalSuivant].length > 0) {
                return; // Joueur suivant trouvé
            }
        } while (this.joueurCourantIndexDansListeActifs !== initialIndex);
    }
    
    tirerSurAdversaire(targetPlayerGlobalIndex, ligne, col) {
        if (this.phaseActuelle !== GamePhase.COMBAT) return ShotResult.ERREUR;
        
        const tireurGlobalIndex = this.getJoueurCourantIndex();
        if (targetPlayerGlobalIndex === tireurGlobalIndex || !this.joueursActifsIndices.includes(targetPlayerGlobalIndex)) {
            return ShotResult.ERREUR;
        }

        const cibleBoard = this.playerBoards[targetPlayerGlobalIndex];
        const resultat = cibleBoard.recevoirTir(ligne, col);

        if (resultat !== ShotResult.DEJA_JOUE && resultat !== ShotResult.ERREUR) {
            if (cibleBoard.tousNaviresCoules()) {
                this.eliminerJoueur(targetPlayerGlobalIndex);
            }
            if (this.phaseActuelle === GamePhase.COMBAT) {
                this.passerAuJoueurSuivantPourCombat();
            }
        }
        return resultat;
    }

    eliminerJoueur(joueurGlobalIndexAEliminer) {
        const indexDansActifs = this.joueursActifsIndices.indexOf(joueurGlobalIndexAEliminer);
        if (indexDansActifs > -1) {
            this.joueursActifsIndices.splice(indexDansActifs, 1);
            if (this.joueurCourantIndexDansListeActifs >= indexDansActifs && this.joueurCourantIndexDansListeActifs > 0) {
                 // S'assurer que l'index du joueur courant reste valide après la suppression
                 // Si on a supprimé un joueur avant ou à la position courante, on décale l'index
                 if (this.joueurCourantIndexDansListeActifs >= this.joueursActifsIndices.length) {
                     this.joueurCourantIndexDansListeActifs = 0; // Wrap around
                 }
            }
        }

        if (this.joueursActifsIndices.length <= 1) {
            this.phaseActuelle = GamePhase.TERMINE;
            if (this.joueursActifsIndices.length === 1) {
                this.gagnantIndex = this.joueursActifsIndices[0];
            }
        }
    }
    
    handlePlayerDisconnect(playerGlobalIndex) {
        this.playerBoards[playerGlobalIndex].aAbandonne = true;
        this.eliminerJoueur(playerGlobalIndex);
        return this.phaseActuelle !== GamePhase.TERMINE;
    }

    passerAuJoueurSuivantPourCombat() {
        if (this.phaseActuelle !== GamePhase.COMBAT || this.joueursActifsIndices.length === 0) return;
        this.joueurCourantIndexDansListeActifs = (this.joueurCourantIndexDansListeActifs + 1) % this.joueursActifsIndices.length;
    }
}

// =======================================================================================
// 3. GESTION DES CONNEXIONS ET DE L'ÉTAT DU SERVEUR
// =======================================================================================

let allClientConnections = [];
let playersInGame = [];
let game = null;
let gameInProgressFlag = false;
let lobbyCountdownActive = false;
let lobbyCountdownTimer = null;

/**
 * Classe unifiée pour gérer les connexions TCP et WebSocket
 */
class ClientConnection {
    constructor(socket, type) {
        this.id = uuidv4(); // ID unique pour chaque connexion
        this.socket = socket;
        this.type = type; // 'TCP' ou 'WS'

        this.nomJoueur = "JoueurAnonyme";
        this.nameIsSet = false;
        this.role = ClientRole.PLAYER_IN_LOBBY;
        this.playerIndex = -1;

        this.setupEventListeners();
    }

    setupEventListeners() {
        if (this.type === 'TCP') {
            // Le TCP est un stream, on doit gérer le buffer pour séparer les messages
            let buffer = '';
            this.socket.on('data', (data) => {
                buffer += data.toString();
                let boundary = buffer.indexOf('\n');
                while (boundary !== -1) {
                    const message = buffer.substring(0, boundary).trim();
                    buffer = buffer.substring(boundary + 1);
                    if (message) {
                        processClientMessage(this, message);
                    }
                    boundary = buffer.indexOf('\n');
                }
            });
            this.socket.on('close', () => handleClientQuit(this));
            this.socket.on('error', (err) => {
                console.error(`Erreur socket TCP (${this.getRemoteAddressString()}):`, err.message);
                handleClientQuit(this);
            });
        } else if (this.type === 'WS') {
            this.socket.on('message', (message) => processClientMessage(this, message.toString()));
            this.socket.on('close', () => handleClientQuit(this));
            this.socket.on('error', (err) => {
                console.error(`Erreur socket WS (${this.getRemoteAddressString()}):`, err.message);
                handleClientQuit(this);
            });
        }
    }

    sendMessage(message) {
        try {
            if (this.type === 'TCP') {
                if (!this.socket.destroyed) this.socket.write(message + '\n');
            } else if (this.type === 'WS') {
                if (this.socket.readyState === 1) this.socket.send(message); // 1 = OPEN
            }
        } catch (e) {
            console.error(`Impossible d'envoyer un message à ${this.getRemoteAddressString()}: ${e.message}`);
        }
    }
    
    closeConnection() {
        if (this.type === 'TCP') this.socket.destroy();
        else if (this.type === 'WS') this.socket.terminate();
    }

    getRemoteAddressString() {
        if (this.type === 'TCP') return `${this.socket.remoteAddress}:${this.socket.remotePort}`;
        // Pour WS, l'objet socket a une propriété _socket qui est le socket TCP sous-jacent
        else if (this.type === 'WS' && this.socket._socket) return `${this.socket._socket.remoteAddress}:${this.socket._socket.remotePort}`;
        return "Adresse inconnue";
    }

    resetForNewLobby() {
        this.nameIsSet = false;
        this.playerIndex = -1;
        this.role = ClientRole.PLAYER_IN_LOBBY;
    }
    
    isActive() {
        if (this.type === 'TCP') return !this.socket.destroyed;
        if (this.type === 'WS') return this.socket.readyState === 1; // OPEN
        return false;
    }
}

// =======================================================================================
// 4. LOGIQUE PRINCIPALE DU SERVEUR (fonctions)
// =======================================================================================

function broadcast(message, exceptId = null) {
    allClientConnections.forEach(client => {
        if (client.id !== exceptId && client.isActive()) {
            client.sendMessage(message);
        }
    });
}

function broadcastToPlayersInGame(message, exceptId = null) {
    playersInGame.forEach(client => {
        if (client.id !== exceptId && client.isActive()) {
            client.sendMessage(message);
        }
    });
}

function broadcastToAllParticipants(message) {
    const participantIds = new Set(playersInGame.map(p => p.id));
    allClientConnections.forEach(client => {
        if (client.role === ClientRole.SPECTATOR) {
            participantIds.add(client.id);
        }
    });
    
    allClientConnections.forEach(client => {
        if (participantIds.has(client.id) && client.isActive()) {
            client.sendMessage(message);
        }
    });
}

function broadcastSaufAUnJoueurEnPartie(exclureClient, message) {
     playersInGame.forEach(client => {
        if (client.id !== exclureClient.id && client.isActive()) {
            client.sendMessage(message);
        }
    });
}

function broadcastLobbyState() {
    const lobbyPlayers = allClientConnections.filter(c => c.isNameSet && c.role === ClientRole.PLAYER_IN_LOBBY);
    const names = lobbyPlayers.map(c => c.nomJoueur).join(',');
    const message = `LOBBY_STATE:${lobbyPlayers.length}:${MIN_PLAYERS_TO_START_TIMER}:${MAX_PLAYERS_ALLOWED}:${names}`;
    broadcast(message);
}

function playerHasSetName(client) {
    if (client.role === ClientRole.SPECTATOR) {
        console.log(`Spectateur ${client.nomJoueur} a défini son nom.`);
        if (gameInProgressFlag && game && game.phaseActuelle !== GamePhase.TERMINE) {
            client.sendMessage("SPECTATE_MODE");
            const allPlayerNamesStr = playersInGame.map(p => p.nomJoueur).join(',');
            client.sendMessage(`SPECTATE_INFO:${PlayerBoard.TAILLE_GRILLE}:${playersInGame.length}:${allPlayerNamesStr}`);
            broadcast(`NEW_CHAT_MSG:Serveur:[${client.nomJoueur} a rejoint le chat en tant que spectateur]`);
            return;
        } else {
            console.log(`Jeu non en cours. ${client.nomJoueur} devient joueur dans le lobby.`);
            client.role = ClientRole.PLAYER_IN_LOBBY;
            broadcast(`NEW_CHAT_MSG:Serveur:[${client.nomJoueur} a rejoint le chat du lobby]`);
        }
    }
    
    broadcastLobbyState();

    const namedPlayerCount = allClientConnections.filter(c => c.isNameSet && c.role === ClientRole.PLAYER_IN_LOBBY).length;

    if (!gameInProgressFlag && !lobbyCountdownActive && namedPlayerCount >= MIN_PLAYERS_TO_START_TIMER) {
        console.log(`${namedPlayerCount} joueurs prêts. Démarrage du compte à rebours.`);
        startLobbyCountdown();
    } else if (!gameInProgressFlag && namedPlayerCount >= MAX_PLAYERS_ALLOWED) {
        console.log(`Maximum de joueurs atteint (${namedPlayerCount}). Démarrage anticipé.`);
        if (lobbyCountdownActive) cancelLobbyCountdown();
        prepareAndStartGameWithReadyPlayers();
    }
}

function startLobbyCountdown() {
    if (lobbyCountdownActive || gameInProgressFlag) return;
    lobbyCountdownActive = true;
    
    broadcast(`LOBBY_COUNTDOWN_STARTED:${LOBBY_COUNTDOWN_MS / 1000}`);
    console.log(`Compte à rebours de ${LOBBY_COUNTDOWN_MS / 1000}s démarré.`);

    lobbyCountdownTimer = setTimeout(() => {
        lobbyCountdownActive = false;
        if (gameInProgressFlag) return;

        const namedPlayerCount = allClientConnections.filter(c => c.isNameSet && c.role === ClientRole.PLAYER_IN_LOBBY).length;
        if (namedPlayerCount >= MIN_PLAYERS_TO_START_TIMER) {
            console.log("Compte à rebours terminé. Démarrage du jeu.");
            prepareAndStartGameWithReadyPlayers();
        } else {
            console.log("Compte à rebours terminé, mais pas assez de joueurs.");
            broadcast("LOBBY_TIMER_ENDED_NO_GAME:Pas assez de joueurs prêts.");
        }
    }, LOBBY_COUNTDOWN_MS);
}

function cancelLobbyCountdown() {
    if (lobbyCountdownTimer) {
        clearTimeout(lobbyCountdownTimer);
        lobbyCountdownTimer = null;
    }
    if (lobbyCountdownActive) {
        lobbyCountdownActive = false;
        broadcast("LOBBY_COUNTDOWN_CANCELLED");
        console.log("Compte à rebours annulé.");
    }
}

function prepareAndStartGameWithReadyPlayers() {
    if (gameInProgressFlag) return;
    
    const joueursPretsPourPartie = allClientConnections.filter(c => c.isNameSet && c.role === ClientRole.PLAYER_IN_LOBBY && c.isActive());
    
    if (joueursPretsPourPartie.length < MIN_PLAYERS_TO_START_TIMER) {
        broadcast("ERROR:Pas assez de joueurs prêts pour démarrer.");
        return;
    }

    gameInProgressFlag = true;
    cancelLobbyCountdown();

    playersInGame = [...joueursPretsPourPartie];
    playersInGame.forEach((player, i) => {
        player.playerIndex = i;
        player.role = ClientRole.PLAYER_IN_GAME;
    });

    const nomsJoueurs = playersInGame.map(p => p.nomJoueur);
    game = new BatailleNavaleGame(nomsJoueurs);
    console.log(`Partie démarrée avec : ${nomsJoueurs.join(', ')}`);

    const allPlayerNamesStr = nomsJoueurs.join(',');

    playersInGame.forEach(client => {
        client.sendMessage(`GAME_START:${PlayerBoard.TAILLE_GRILLE}:${client.playerIndex}:${playersInGame.length}:${allPlayerNamesStr}`);
    });
    
    allClientConnections.forEach(client => {
        if (!playersInGame.find(p => p.id === client.id)) { // Si le client n'est pas un joueur
            client.role = ClientRole.SPECTATOR;
            if (client.isNameSet) {
                client.sendMessage("SPECTATE_MODE");
                client.sendMessage(`SPECTATE_INFO:${PlayerBoard.TAILLE_GRILLE}:${playersInGame.length}:${allPlayerNamesStr}`);
            } else {
                client.sendMessage("REQ_NAME");
            }
        }
    });

    passerAuPlacementSuivant();
}

function passerAuPlacementSuivant() {
    if (!game) return;

    if (game.phaseActuelle === GamePhase.COMBAT) {
        broadcastToPlayersInGame("ALL_SHIPS_PLACED");
        informerTourCombat();
        return;
    }

    if (game.phaseActuelle === GamePhase.PLACEMENT_BATEAUX) {
        const joueurCourantIdx = game.getJoueurCourantIndex();
        if (joueurCourantIdx === -1) {
            broadcastToAllParticipants("GAME_OVER_DISCONNECT:Tous les joueurs ont quitté.");
            resetServerForNewLobby();
            return;
        }

        const clientActif = playersInGame.find(p => p.playerIndex === joueurCourantIdx);
        if (!clientActif || !clientActif.isActive()) {
            game.passerAuJoueurSuivantPourPlacement();
            passerAuPlacementSuivant();
            return;
        }
        
        const naviresAPlacer = game.getNaviresAPlacerPourJoueurCourant();
        if (naviresAPlacer.length > 0) {
            const prochainNavire = naviresAPlacer[0];
            const enumName = Object.keys(Ship.ShipType).find(key => Ship.ShipType[key].nom === prochainNavire.nom);
            clientActif.sendMessage(`YOUR_TURN_PLACE_SHIP:${enumName}:${prochainNavire.taille}:${prochainNavire.nom}`);
            broadcastSaufAUnJoueurEnPartie(clientActif, `WAIT_PLACEMENT:${clientActif.nomJoueur}:${prochainNavire.nom}`);
        } else {
            game.passerAuJoueurSuivantPourPlacement();
            passerAuPlacementSuivant();
        }
    }
}

function informerTourCombat() {
    if (!game || game.phaseActuelle !== GamePhase.COMBAT) return;

    const joueurCourantIdx = game.getJoueurCourantIndex();
    if (joueurCourantIdx === -1) {
        broadcastToAllParticipants("GAME_OVER_DRAW:Aucun joueur actif restant.");
        resetServerForNewLobby();
        return;
    }

    const clientActif = playersInGame.find(p => p.playerIndex === joueurCourantIdx);
    if (!clientActif || !clientActif.isActive()) {
        game.passerAuJoueurSuivantPourCombat();
        informerTourCombat();
        return;
    }
    
    clientActif.sendMessage("YOUR_TURN_FIRE");
    broadcastSaufAUnJoueurEnPartie(clientActif, `OPPONENT_TURN_FIRE:${clientActif.nomJoueur}`);
}

function handleClientQuit(client) {
    console.log(`Déconnexion détectée pour: ${client.nomJoueur} (${client.getRemoteAddressString()})`);
    
    // Retirer de la liste globale
    const initialCount = allClientConnections.length;
    allClientConnections = allClientConnections.filter(c => c.id !== client.id);
    if (allClientConnections.length < initialCount) {
         console.log(`${client.nomJoueur} retiré de la liste des connexions.`);
    }

    const wasInGame = playersInGame.find(p => p.id === client.id);
    if (gameInProgressFlag && wasInGame && game) {
        playersInGame = playersInGame.filter(p => p.id !== client.id);
        const gamePeutContinuer = game.handlePlayerDisconnect(client.playerIndex);

        if (gamePeutContinuer) {
            broadcastToAllParticipants(`PLAYER_LEFT:${client.nomJoueur}:${client.playerIndex}`);
             // Si le joueur déconnecté était le joueur courant, il faut passer au suivant
            if (game.getJoueurCourantIndex() === client.playerIndex) {
                if (game.phaseActuelle === GamePhase.PLACEMENT_BATEAUX) passerAuPlacementSuivant();
                else if (game.phaseActuelle === GamePhase.COMBAT) informerTourCombat();
            }
        } else {
            const gagnantIdx = game.gagnantIndex;
            let messageFin = `GAME_OVER_DISCONNECT:${client.nomJoueur}`;
            if (gagnantIdx !== -1) {
                const nomGagnant = game.playerBoards[gagnantIdx].nomJoueur;
                messageFin = `GAME_OVER:${nomGagnant}:${gagnantIdx}`;
            }
            broadcastToAllParticipants(messageFin);
            resetServerForNewLobby();
        }
    } else {
        // Le client était dans le lobby ou spectateur
        broadcastLobbyState();
        if (client.isNameSet) {
            broadcast(`NEW_CHAT_MSG:Serveur:[${client.nomJoueur} a quitté le chat]`);
        }
    }
}

function resetServerForNewLobby() {
    console.log("Réinitialisation du serveur pour un nouveau lobby.");
    game = null;
    gameInProgressFlag = false;
    cancelLobbyCountdown();
    playersInGame = [];

    allClientConnections.forEach(client => {
        if (client.isActive()) {
            client.resetForNewLobby();
            client.sendMessage("REQ_NAME");
        }
    });
    broadcastLobbyState();
}


function processClientMessage(client, message) {
    console.log(`Reçu de ${client.nomJoueur}: ${message}`);
    const [command, ...payloadParts] = message.split(':');
    const payload = payloadParts.join(':');

    if (command === 'SET_NAME') {
        const potentialName = payload.trim();
        if (potentialName && potentialName.length <= 15) {
            const nameTaken = allClientConnections.some(c => c.id !== client.id && c.isNameSet && c.nomJoueur.toLowerCase() === potentialName.toLowerCase());
            if (nameTaken) {
                client.sendMessage("ERROR:Ce nom est déjà utilisé.");
                client.sendMessage("REQ_NAME");
            } else {
                client.nomJoueur = potentialName;
                client.isNameSet = true;
                playerHasSetName(client);
            }
        } else {
            client.sendMessage("ERROR:Le nom ne peut pas être vide et doit faire 15 caractères max.");
            if (!client.isNameSet) client.sendMessage("REQ_NAME");
        }
        return;
    }

    if (!client.isNameSet) {
        client.sendMessage("ERROR:Veuillez d'abord définir votre nom.");
        client.sendMessage("REQ_NAME");
        return;
    }

    switch (command) {
        case 'PLACE_SHIP': {
            if (client.role !== ClientRole.PLAYER_IN_GAME) break;
            const [shipEnum, ligne, col, horizontal] = payload.split(':');
            const type = Ship.ShipType[shipEnum];
            if (type && game) {
                 game.placerNavireJoueurCourant(type, parseInt(ligne), parseInt(col), horizontal === 'true');
                 // Confirmation/rejet est géré par la logique qui suit
                 const clientActif = playersInGame.find(p => p.playerIndex === game.getJoueurCourantIndex());
                 if (clientActif && clientActif.id === client.id) {
                     // Le navire attendu a peut-être changé
                     const naviresRestants = game.getNaviresAPlacerPourJoueurCourant();
                     const navireJustePlace = naviresRestants.find(n => n.nom === type.nom) === undefined;
                     
                     if (navireJustePlace) {
                         client.sendMessage(`PLACEMENT_ACCEPTED:${shipEnum}:${ligne}:${col}:${horizontal}`);
                         broadcastSaufAUnJoueurEnPartie(client, `PLAYER_PLACED_SHIP:${client.nomJoueur}:${type.nom}`);
                     } else {
                         client.sendMessage(`PLACEMENT_REJECTED:${shipEnum}`);
                     }
                 }
                 passerAuPlacementSuivant();
            }
            break;
        }
        case 'FIRE_SHOT': {
            if (client.role !== ClientRole.PLAYER_IN_GAME || !game) break;
            const [targetIdx, ligne, col] = payload.split(':').map(Number);
            
            if (client.playerIndex !== game.getJoueurCourantIndex()) {
                client.sendMessage("ERROR:Pas votre tour de tirer.");
                break;
            }

            const resultat = game.tirerSurAdversaire(targetIdx, ligne, col);
            let messageBase = `SHOT_RESULT:${client.playerIndex}:${targetIdx}:${ligne}:${col}:${resultat}`;

            if (resultat === ShotResult.COULE) {
                const targetBoard = game.playerBoards[targetIdx];
                // Essayer de trouver le navire qui vient d'être coulé par ce tir
                const navireCoule = targetBoard.navires.find(n => n.isSunk() && n.positions.some(p => p.x === ligne && p.y === col));
                if (navireCoule) {
                    messageBase += `:${navireCoule.getTypeName()}`;
                } else {
                    // Fallback si on ne trouve pas le navire exact (ne devrait pas arriver)
                    messageBase += ":UNKNOWN_SHIP";
                }
            }
            broadcastToAllParticipants(messageBase);
            
            // Après avoir diffusé le résultat, on vérifie l'état du jeu pour passer le tour
            if (game.phaseActuelle === GamePhase.TERMINE) {
                const gagnantIdx = game.gagnantIndex;
                let messageFin = "GAME_OVER_DRAW";
                if (gagnantIdx !== -1) {
                    const nomGagnant = game.playerBoards[gagnantIdx].nomJoueur;
                    messageFin = `GAME_OVER:${nomGagnant}:${gagnantIdx}`;
                }
                broadcastToAllParticipants(messageFin);
                resetServerForNewLobby();
            } else if (resultat === ShotResult.DEJA_JOUE || resultat === ShotResult.ERREUR) {
                // Le joueur a fait un tir invalide, il doit rejouer
                client.sendMessage("YOUR_TURN_FIRE");
            } else {
                // CORRECTION : C'était un tir valide qui n'a pas mis fin à la partie.
                // Il faut maintenant informer le joueur suivant que c'est son tour.
                informerTourCombat();
            }
            break;
        }
        case 'ADMIN_START_GAME': {
            // Logique simplifiée pour démarrer
            prepareAndStartGameWithReadyPlayers();
            break;
        }
        case 'CHAT_MSG': {
            broadcast(`NEW_CHAT_MSG:${client.nomJoueur}:${payload}`);
            break;
        }
        case 'QUIT_GAME': {
            client.closeConnection();
            break;
        }
        default:
            client.sendMessage(`ERROR:Commande inconnue '${command}'.`);
    }
}


// =======================================================================================
// 5. INITIALISATION DES SERVEURS
// =======================================================================================

function handleNewConnection(socket, type) {
    if (allClientConnections.length >= MAX_PLAYERS_ALLOWED) {
        const conn = new ClientConnection(socket, type);
        conn.sendMessage(`ERROR:Serveur plein (max ${MAX_PLAYERS_ALLOWED} participants).`);
        conn.closeConnection();
        return;
    }

    const client = new ClientConnection(socket, type);
    allClientConnections.push(client);
    console.log(`Nouveau participant ${type} connecté: ${client.getRemoteAddressString()}. Total connectés: ${allClientConnections.length}`);
    
    if (gameInProgressFlag && game && game.phaseActuelle !== GamePhase.TERMINE) {
        client.role = ClientRole.SPECTATOR;
        console.log(`Client ${type} ${client.getRemoteAddressString()} est un spectateur.`);
    }

    client.sendMessage("REQ_NAME");
}

// Lancer le serveur TCP
const tcpServer = net.createServer((socket) => {
    handleNewConnection(socket, 'TCP');
});
tcpServer.listen(TCP_PORT, () => {
    console.log(`Serveur TCP en écoute sur le port ${TCP_PORT}`);
});

// Lancer le serveur WebSocket
const wsServer = new WebSocketServer({ port: WS_PORT });
wsServer.on('connection', (ws) => {
    handleNewConnection(ws, 'WS');
});
wsServer.on('listening', () => {
    console.log(`Serveur WebSocket en écoute sur le port ${WS_PORT}`);
});