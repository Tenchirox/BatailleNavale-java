document.addEventListener('DOMContentLoaded', () => {
    // --- DOM Elements ---
    const serverIpField = document.getElementById('server-ip');
    const playerNameField = document.getElementById('player-name');
    const connectButton = document.getElementById('connect-button');

    const connectionView = document.getElementById('connection-view');
    const mainGameArea = document.getElementById('main-game-area');
    const lobbyView = document.getElementById('lobby-view');
    const gameContentView = document.getElementById('game-content-view');

    const lobbyArea = document.getElementById('lobby-area');
    const hostStartGameButton = document.getElementById('host-start-game-button');
    const quitGameButton = document.getElementById('quit-game-button');

    const placementControls = document.getElementById('placement-controls');
    const shipToPlaceLabel = document.getElementById('ship-to-place-label');
    const horizontalRadioButton = document.getElementById('horizontal-radio');
    const verticalRadioButton = document.getElementById('vertical-radio');

    const gridsContainer = document.getElementById('grids-container');
    const eastSidePanel = document.getElementById('east-side-panel');
    const chatDisplayArea = document.getElementById('chat-display-area');
    const chatInputField = document.getElementById('chat-input-field');
    const sendChatButton = document.getElementById('send-chat-button');
    const gameLogArea = document.getElementById('game-log-area');
    const statusBar = document.getElementById('status-bar');

    // --- Model Constants (from BatailleNavaleClient.ModelConstants) ---
    const ModelConstants = {
        ShipType: {
            PORTE_AVIONS: { nom: "Porte-avions", taille: 5, spriteChar: 'P' },
            CROISEUR: { nom: "Croiseur", taille: 4, spriteChar: 'C' },
            CONTRE_TORPILLEUR: { nom: "Contre-torpilleur", taille: 3, spriteChar: 'T' },
            SOUS_MARIN: { nom: "Sous-marin", taille: 3, spriteChar: 'S' },
            TORPILLEUR: { nom: "Torpilleur", taille: 2, spriteChar: 'R' }
        },
        ShotResult: { MANQUE: "MANQUE", TOUCHE: "TOUCHE", COULE: "COULE", DEJA_JOUE: "DEJA_JOUE", ERREUR: "ERREUR" },
        TAILLE_GRILLE: 10,
        OPP_UNKNOWN: ' ', OPP_MISS: 'O', OPP_HIT: 'X', OPP_SUNK: '!',
        MY_EMPTY: ' ', MY_HIT_ON_ME: 'X', MY_MISS_ON_ME: 'M' // M for miss on my board by opponent
    };

    // --- Game State Variables ---
    let socket;
    let serverAddress = "ws://192.168.3.86:12349"; // Default WebSocket URL
    let playerName = "Joueur";
    let playerIndex = -1;
    let myTurn = false;
    let placementPhase = false;
    let currentShipToPlace = null; // Will be an object from ModelConstants.ShipType
    let inGame = false;
    let amISpectator = false;
    let totalPlayersInGame = 0;
    let allPlayerNames = {}; // { index: "name", ... }
    let minPlayersToStartLobby = 2;
    let nameSuccessfullySetThisSession = false;

    // Stores references to grid cell elements for quick updates
    // playerGrids[playerBoardIndex][row][col] = cellElement
    let playerGridCells = {}; // { boardOwnerPlayerIndex: [[cell, cell], [cell, cell]], ... }
    // Store client-side representation of player's own ships for preview validation
    let myPlacedShips = []; // [{type, r, c, horizontal, cells: [{r,c}, ...]}, ...]

    // --- Initialization ---
    function init() {
        serverIpField.value = serverAddress;
        playerNameField.value = playerName;

        connectButton.addEventListener('click', connectAndSetName);
        hostStartGameButton.addEventListener('click', () => sendMessage("ADMIN_START_GAME"));
        quitGameButton.addEventListener('click', () => sendMessage("QUIT_GAME"));
        sendChatButton.addEventListener('click', sendChatMessageAction);
        chatInputField.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') sendChatMessageAction();
        });

        [horizontalRadioButton, verticalRadioButton].forEach(radio => {
            radio.addEventListener('change', () => {
                if (placementPhase && currentShipToPlace) {
                    // Clear previous preview on my grid if any active
                    clearPlacementPreview(playerIndex);
                    // Re-trigger preview logic if mouse is over a cell (or rely on mouseEnter to re-trigger)
                }
            });
        });
        switchToView("CONNECTION");
    }

    // --- UI Manipulation ---
    function updateStatus(message) {
        statusBar.textContent = message;
    }

    function addGameLog(message) {
        const entry = document.createElement('div');
        entry.textContent = message;
        gameLogArea.appendChild(entry);
        gameLogArea.scrollTop = gameLogArea.scrollHeight; // Auto-scroll
    }

    function clearGameLog() {
        gameLogArea.innerHTML = '';
    }

    function appendChatMessage(message) {
        const entry = document.createElement('div');
        entry.textContent = message; // Consider sanitizing if names can contain HTML
        chatDisplayArea.appendChild(entry);
        chatDisplayArea.scrollTop = chatDisplayArea.scrollHeight; // Auto-scroll
    }

    function clearChat() {
        chatDisplayArea.innerHTML = '';
    }

    function switchToView(viewName) {
        connectionView.style.display = 'none';
        mainGameArea.style.display = 'none';
        lobbyView.style.display = 'none';
        gameContentView.style.display = 'none';
        eastSidePanel.style.display = 'none';
        placementControls.style.display = 'none';
        hostStartGameButton.style.display = 'none';
        quitGameButton.style.display = 'none';


        switch (viewName) {
            case "CONNECTION":
                connectionView.style.display = 'block';
                playerNameField.value = playerName;
                playerNameField.disabled = false;
                serverIpField.disabled = false;
                connectButton.disabled = false;
                playerIndex = -1; inGame = false; placementPhase = false; amISpectator = false;
                clearGameLog();
                clearChat();
                break;
            case "LOBBY":
                mainGameArea.style.display = 'flex'; /* Use flex for main game area layout */
                lobbyView.style.display = 'block';
                eastSidePanel.style.display = 'flex'; /* Show chat/log */
                quitGameButton.textContent = "Quitter le Lobby";
                quitGameButton.style.display = 'block';
                // hostStartGameButton might be set visible by LOBBY_STATE
                break;
            case "PLACEMENT":
            case "COMBAT":
            case "SPECTATOR":
                mainGameArea.style.display = 'flex';
                gameContentView.style.display = 'block';
                eastSidePanel.style.display = 'flex';
                quitGameButton.textContent = amISpectator ? "Quitter (Spectateur)" : "Quitter la Partie";
                quitGameButton.style.display = 'block';
                if (viewName === "PLACEMENT" && !amISpectator) {
                    placementControls.style.display = 'block';
                }
                break;
        }
    }
    
    function switchToLobbyView(lobbyMessageContent, namedPlayerCount, minPlayers, firstPlayerNameInLobby) {
        inGame = false; placementPhase = false; amISpectator = false;
        lobbyArea.value = lobbyMessageContent; // Use .value for textarea
        
        const amIHost = nameSuccessfullySetThisSession && playerName === firstPlayerNameInLobby && firstPlayerNameInLobby !== "";
        hostStartGameButton.style.display = amIHost ? 'block' : 'none';
        hostStartGameButton.disabled = !(amIHost && namedPlayerCount >= minPlayers);
        
        switchToView("LOBBY");
        updateStatus("Dans le lobby. En attente du démarrage de la partie...");
    }

    function setupPlayerGridsForGameView() {
        gridsContainer.innerHTML = ''; // Clear previous grids
        playerGridCells = {}; // Reset cell references

        if (amISpectator) {
            updateStatus(`Mode Spectateur - ${totalPlayersInGame} joueurs.`);
            if (totalPlayersInGame === 0) {
                gridsContainer.innerHTML = '<p>Aucun joueur à observer.</p>';
                return;
            }
            for (let i = 0; i < totalPlayersInGame; i++) {
                const playerNameForGrid = allPlayerNames[i] || `Joueur ${i + 1}`;
                createGridPanelDOM(gridsContainer, ModelConstants.TAILLE_GRILLE, false, i, playerNameForGrid);
                setGridEnabled(i, false); // Spectator grids are never enabled for interaction
            }
        } else { // Regular player
            // My grid
            createGridPanelDOM(gridsContainer, ModelConstants.TAILLE_GRILLE, true, playerIndex, `${playerName} (Votre Grille)`);
            
            // Opponent grids
            for (let i = 0; i < totalPlayersInGame; i++) {
                if (i === playerIndex) continue;
                const opponentName = allPlayerNames[i] || `Adversaire ${i + 1}`;
                createGridPanelDOM(gridsContainer, ModelConstants.TAILLE_GRILLE, false, i, `Tirer sur: ${opponentName}`);
            }
        }
    }

    function switchToPlacementView() {
        placementPhase = true; inGame = true; amISpectator = false;
        setupPlayerGridsForGameView();

        if (playerGridCells[playerIndex]) { // My grid exists
            setGridEnabled(playerIndex, true); // Enable my grid for placement clicks
        }
        for (const boardIdx in playerGridCells) {
            if (parseInt(boardIdx) !== playerIndex) {
                setGridEnabled(parseInt(boardIdx), false); // Disable opponent grids
            }
        }
        switchToView("PLACEMENT");
        updateStatus("Phase de placement des navires.");
    }

    function switchToGameOrSpectatorView() {
        placementPhase = false; // Ensure placement controls are hidden if moving from placement
        
        if (Object.keys(playerGridCells).length === 0 || 
            (!amISpectator && !playerGridCells[playerIndex]) ||
            (amISpectator && totalPlayersInGame > 0 && Object.keys(playerGridCells).length !== totalPlayersInGame) ) {
            setupPlayerGridsForGameView(); // Setup grids if not already done or if player count changed
        }


        if (amISpectator) {
            updateStatus("Mode Spectateur - Phase de combat.");
            for (const boardIdx in playerGridCells) {
                setGridEnabled(parseInt(boardIdx), false);
            }
        } else { // Regular player in combat phase
             updateStatus(myTurn ? "Phase de combat - À vous de tirer !" : "Phase de combat - Attente de l'adversaire.");
            // Enable/disable grids based on whose turn it is
            setGridEnabled(playerIndex, false); // My grid is never clickable in combat
            for (const boardIdx in playerGridCells) {
                if (parseInt(boardIdx) !== playerIndex) {
                    setGridEnabled(parseInt(boardIdx), myTurn); // Opponent grids clickable if myTurn
                }
            }
        }
        switchToView(amISpectator ? "SPECTATOR" : "COMBAT");
    }


    // --- WebSocket Communication ---
    function connectAndSetName() {
        serverAddress = serverIpField.value.trim();
        const tempPlayerName = playerNameField.value.trim();

        if (serverAddress === "") { updateStatus("L'URL du serveur WebSocket ne peut pas être vide."); return; }
        if (tempPlayerName === "") { updateStatus("Votre nom ne peut pas être vide."); return; }
        if (tempPlayerName.length > 15) { updateStatus("Erreur: Le nom ne doit pas dépasser 15 caractères."); return; }
        
        playerName = tempPlayerName;

        updateStatus(`Connexion à ${serverAddress}...`);
        connectButton.disabled = true;
        serverIpField.disabled = true;
        playerNameField.disabled = true;

        try {
            if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
                socket.close(); // Close existing socket before creating a new one
            }
            socket = new WebSocket(serverAddress);
        } catch (e) {
            handleConnectionError("Erreur de création WebSocket: URL invalide?", e);
            return;
        }


        socket.onopen = () => {
            updateStatus("Connecté. Envoi du nom au serveur...");
            sendMessage(`SET_NAME:${playerName}`);
        };

        socket.onmessage = (event) => {
            handleServerMessageRaw(event.data);
        };

        socket.onerror = (error) => {
            console.error('WebSocket Error:', error);
            // The 'onclose' event will likely fire after an error, which will handle UI reset.
            // If onclose doesn't fire for some errors, manual handling might be needed here.
            // For simplicity, we let onclose manage the UI reset.
            // If we want immediate feedback on error before close:
            handleConnectionError("Erreur WebSocket. Vérifiez la console.", error);
        };

        socket.onclose = (event) => {
            let reason = "";
            if (event.code) reason += `Code: ${event.code}`;
            if (event.reason) reason += ` Raison: ${event.reason}`;
            if (!reason && !event.wasClean) reason = "Connexion interrompue inopinément.";
            if (!reason && event.wasClean) reason = "Déconnexion normale.";


            updateStatus(`Déconnecté du serveur. ${reason} Veuillez vous reconnecter.`);
            addGameLog(`Déconnexion du serveur. ${reason}`);
            resetClientStateForNewConnection();
            switchToView("CONNECTION");
            nameSuccessfullySetThisSession = false; // Ensure this is reset
            connectButton.disabled = false;
            serverIpField.disabled = false;
            playerNameField.disabled = false;
        };
    }

    function handleConnectionError(message, ex) {
        updateStatus(`Erreur de connexion: ${message}`);
        addGameLog(`Erreur de connexion: ${message} ${ex ? `(${ex.message || ex})` : ''}`);
        connectButton.disabled = false;
        serverIpField.disabled = false;
        playerNameField.disabled = false;
        nameSuccessfullySetThisSession = false;
    }

    function sendMessage(message) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(message);
        } else {
            updateStatus("Non connecté. Impossible d'envoyer le message.");
            addGameLog("Erreur: Tentative d'envoi de message sans connexion active.");
        }
    }
    
    function resetClientStateForNewConnection() {
        // nameSuccessfullySetThisSession is reset in onclose or connectAndSetName failure
        inGame = false;
        amISpectator = false;
        placementPhase = false;
        myTurn = false;
        playerIndex = -1;
        playerGridCells = {}; // Clear grid cell references
        gridsContainer.innerHTML = ''; // Clear actual grid DOM
        allPlayerNames = {};
        currentShipToPlace = null;
        myPlacedShips = [];
    }

   // NEW, CORRECTED CODE
function handleServerMessageRaw(rawMessage) {
    console.log("Serveur (brut): " + rawMessage);
    
    const firstColonIndex = rawMessage.indexOf(':');
    let command, payload;
    
    if (firstColonIndex === -1) {
        // No colon found, the whole message is the command
        command = rawMessage;
        payload = "";
    } else {
        // Split at the first colon
        command = rawMessage.substring(0, firstColonIndex);
        payload = rawMessage.substring(firstColonIndex + 1);
    }
    
    processServerMessage(command, payload);
}

    function processServerMessage(command, payload) {
        addGameLog(`Serveur: ${command} ${payload ? ':'+payload : ''}`); // Log processed message
        switch (command) {
            case "REQ_NAME":
                updateStatus("Le serveur demande votre nom.");
                addGameLog("Le serveur demande la saisie du nom.");
                nameSuccessfullySetThisSession = false; playerIndex = -1; inGame = false; placementPhase = false; amISpectator = false;
                switchToView("CONNECTION");
                playerNameField.disabled = false;
                connectButton.disabled = false;
                break;

            case "LOBBY_STATE":
                const lobbyData = payload.split(":", 4); // Max 4 parts: count:min:max:names
                if (lobbyData.length >= 3) { // count, min, (max or names could be missing if only 2 players for example)
                    const namedPlayerCount = parseInt(lobbyData[0]);
                    minPlayersToStartLobby = parseInt(lobbyData[1]);
                    // const maxPlayersInLobby = parseInt(lobbyData[2]); // If needed
                    const playerNamesList = (lobbyData.length > 3) ? lobbyData[3] : "";
                    
                    let lobbyText = `Joueurs prêts: ${namedPlayerCount} (Min: ${minPlayersToStartLobby})\nNoms: ${playerNamesList || "(aucun)"}`;
                    
                    let firstPlayerName = "";
                    let myNameConfirmed = false;
                    if (playerNamesList) {
                        const names = playerNamesList.split(',');
                        if (names.length > 0) firstPlayerName = names[0].trim();
                        myNameConfirmed = names.some(n => n.trim() === playerName);
                    }

                    if (myNameConfirmed) {
                        nameSuccessfullySetThisSession = true; // My name is confirmed by the server
                    }

                    if (!amISpectator) {
                        if (myNameConfirmed) {
                            if (!inGame) { // If I'm confirmed and not in a game, show lobby
                               switchToLobbyView(lobbyText, namedPlayerCount, minPlayersToStartLobby, firstPlayerName);
                            }
                        } else if (connectionView.style.display === 'block') { // On connection screen, not yet confirmed
                            updateStatus(`Lobby: ${namedPlayerCount} joueurs. Votre nom n'est pas encore confirmé.`);
                        } else if (nameSuccessfullySetThisSession && !inGame) {
                            // Was confirmed, now not in list (and not in game) -> might be an issue, server should send REQ_NAME
                            addGameLog("Avertissement: Mon nom n'est plus dans la liste du lobby. En attente du serveur.");
                        }
                    } else { // Spectator
                         addGameLog(`Info Lobby (spectateur): ${lobbyText}`);
                    }
                } else {
                    updateStatus("Erreur: LOBBY_STATE malformé."); addGameLog(`Erreur: LOBBY_STATE malformé: ${payload}`);
                }
                break;
            
            case "SPECTATE_MODE":
                amISpectator = true;
                nameSuccessfullySetThisSession = true; // Spectator also has a name set by this point
                updateStatus("Mode Spectateur activé...");
                addGameLog("Activation du mode spectateur.");
                // SPECTATE_INFO will trigger the view switch
                break;

            case "SPECTATE_INFO":
                if (!amISpectator) break;
                const si = payload.split(":", 3); // TAILLE_GRILLE:totalPlayers:names
                if (si.length >= 3) {
                    // TAILLE_GRILLE from si[0] can be used if dynamic
                    totalPlayersInGame = parseInt(si[1]);
                    const specPlayerNames = si[2].split(',');
                    allPlayerNames = {};
                    specPlayerNames.forEach((name, idx) => allPlayerNames[idx] = name.trim());
                    
                    const sm = `Spectateur: ${totalPlayersInGame} joueurs: ${si[2]}`;
                    updateStatus(sm); addGameLog(sm);
                    switchToGameOrSpectatorView();
                } else {
                    updateStatus("Erreur: SPECTATE_INFO malformé."); addGameLog(`Erreur: SPECTATE_INFO malformé: ${payload}`);
                }
                break;

            case "GAME_START": // TAILLE_GRILLE:playerIndex:totalPlayers:names
                const gi = payload.split(":", 4);
                if (gi.length >= 4) {
                    playerIndex = parseInt(gi[1]);
                    totalPlayersInGame = parseInt(gi[2]);
                    const gamePlayerNames = gi[3].split(',');
                    allPlayerNames = {};
                    gamePlayerNames.forEach((name, idx) => allPlayerNames[idx] = name.trim());
                    
                    if (allPlayerNames[playerIndex]) { // Server confirms my name for this game
                        playerName = allPlayerNames[playerIndex];
                    }
                    nameSuccessfullySetThisSession = true; amISpectator = false; inGame = true;
                    myPlacedShips = []; // Reset for new game

                    const gsm = `Partie commence ! Vous: ${playerName} (J${playerIndex}). Placement.`;
                    updateStatus(gsm); addGameLog(gsm);
                    switchToPlacementView();
                } else {
                    updateStatus("Erreur: GAME_START malformé."); addGameLog(`Erreur: GAME_START malformé: ${payload}`);
                }
                break;
            
            case "YOUR_TURN_PLACE_SHIP": // shipEnumName:shipSize:shipDisplayName
                if (amISpectator) break;
                const shi = payload.split(":");
                if (shi.length >= 3) {
                    const shipEnumName = shi[0];
                    currentShipToPlace = ModelConstants.ShipType[shipEnumName]; // Get ship object
                    if (!currentShipToPlace) {
                        addGameLog(`Erreur: Type de navire inconnu ${shipEnumName} pour placement.`);
                        updateStatus(`Erreur: Type de navire inconnu ${shipEnumName}`);
                        break;
                    }
                    const currentShipDisplayName = shi[2];
                    shipToPlaceLabel.textContent = `Placez: ${currentShipDisplayName} (taille ${currentShipToPlace.taille})`;
                    
                    const ptm = `${allPlayerNames[playerIndex] || 'Vous'}, placez: ${currentShipDisplayName}`;
                    updateStatus(ptm); addGameLog(`Placement: ${ptm}`);
                    setGridEnabled(playerIndex, true); // Enable my grid for placement
                    // Preview will be handled by mouseover events on the grid
                } else {
                     updateStatus("Erreur: YOUR_TURN_PLACE_SHIP malformé."); addGameLog(`Erreur: YOUR_TURN_PLACE_SHIP malformé: ${payload}`);
                }
                break;

            case "WAIT_PLACEMENT": // placingPlayerName:shipDisplayName (for non-spectators) OR spectatorMessage (for spectators)
                let wm;
                if (amISpectator) {
                    const wsi = payload.split(":");
                    wm = (wsi.length >=2) ? `Spectateur: ${wsi[0]} place ${wsi[1]}` : `Spectateur: Attente placement...`;
                } else {
                    const wi = payload.split(":");
                    wm = (wi.length >= 2) ? `Attente: ${wi[0]} place ${wi[1]}` : `Attente placement adversaire...`;
                    if (!amISpectator && playerGridCells[playerIndex]) {
                       setGridEnabled(playerIndex, false); // Disable my grid
                    }
                    currentShipToPlace = null; // Not my turn to place
                }
                updateStatus(wm); addGameLog(wm);
                break;

            case "PLACEMENT_ACCEPTED": // shipEnumName:r:c:horizontal
                 if (amISpectator) break;
                 const ai = payload.split(":", 4);
                 if (ai.length >= 4) {
                    const shipEnumName = ai[0];
                    const type = ModelConstants.ShipType[shipEnumName];
                    const r = parseInt(ai[1]);
                    const c = parseInt(ai[2]);
                    const isHorizontal = ai[3] === 'true';

                    if (type) {
                        confirmShipPlacementOnGrid(playerIndex, type, r, c, isHorizontal);
                        // Store placed ship for client-side validation of future placements
                        const shipCells = [];
                        for (let i = 0; i < type.taille; i++) {
                            shipCells.push(isHorizontal ? {r: r, c: c + i} : {r: r + i, c: c});
                        }
                        myPlacedShips.push({type, r, c, horizontal: isHorizontal, cells: shipCells});

                        const pam = `${type.nom} placé par ${allPlayerNames[playerIndex] || 'vous'}.`;
                        updateStatus(pam); addGameLog(`Placement: ${pam}`);
                    } else {
                         addGameLog(`Erreur: Type de navire ${shipEnumName} inconnu pour PLACEMENT_ACCEPTED.`);
                    }
                    currentShipToPlace = null;
                    setGridEnabled(playerIndex, false);
                 } else {
                    updateStatus("Erreur: PLACEMENT_ACCEPTED malformé."); addGameLog(`Erreur: PLACEMENT_ACCEPTED malformé: ${payload}`);
                 }
                 break;

            case "PLACEMENT_REJECTED": // shipName (currently just payload)
                if (amISpectator) break;
                // Payload might be shipEnumName or shipDisplayName. Let's assume it's for context.
                const prm = `Placement de ${payload} refusé. Réessayez.`;
                updateStatus(prm); addGameLog(`Placement: ${prm}`);
                if (currentShipToPlace) { // Server should send YOUR_TURN_PLACE_SHIP again if it's still this player's turn for this ship
                    setGridEnabled(playerIndex, true); // Re-enable grid
                }
                break;
            
            case "ALL_SHIPS_PLACED":
                updateStatus("Tous les navires placés. Début du combat !"); 
                addGameLog("Tous les navires placés. Phase de combat !");
                placementPhase = false;
                currentShipToPlace = null;
                myPlacedShips = []; // Clear after placement phase
                switchToGameOrSpectatorView();
                break;

            case "YOUR_TURN_FIRE":
                if (amISpectator) break;
                myTurn = true;
                const ftm = `${allPlayerNames[playerIndex] || 'Vous'}, à vous de tirer !`;
                updateStatus(ftm); addGameLog(`Combat: ${ftm}`);
                setGridEnabled(playerIndex, false); // Own grid not clickable for firing
                for (const boardIdxStr in playerGridCells) {
                    const boardIdx = parseInt(boardIdxStr);
                    if (boardIdx !== playerIndex) {
                        setGridEnabled(boardIdx, true); // Enable opponent grids
                    }
                }
                // Consider visual cue for active turn
                break;

            case "OPPONENT_TURN_FIRE": // opponentName
                const oppNameTurn = payload;
                const otm = `Au tour de ${oppNameTurn} de tirer.`;
                updateStatus(otm); addGameLog(`Combat: ${otm} ${amISpectator ? "(Spectateur)" : ""}`);
                if (!amISpectator) {
                    myTurn = false;
                     for (const boardIdxStr in playerGridCells) {
                        const boardIdx = parseInt(boardIdxStr);
                        if (boardIdx !== playerIndex) {
                             setGridEnabled(boardIdx, false); // Disable opponent grids
                        }
                    }
                }
                break;

            case "SHOT_RESULT": // shooterIdx:targetIdx:r:c:result:sunkShipName (sunkShipName is optional)
                const sd = payload.split(":", 6);
                if (sd.length >= 5) {
                    const shooterPlayerIndex = parseInt(sd[0]);
                    const targetPlayerIndex = parseInt(sd[1]);
                    const r = parseInt(sd[2]);
                    const c = parseInt(sd[3]);
                    const result = sd[4]; // String like "TOUCHE", "MANQUE", "COULE"
                    const sunkShipName = (sd.length > 5 && sd[5] !== "UNKNOWN_SHIP") ? sd[5] : null;

                    const shooterName = allPlayerNames[shooterPlayerIndex] || `J${shooterPlayerIndex + 1}`;
                    const targetName = allPlayerNames[targetPlayerIndex] || `J${targetPlayerIndex + 1}`;
                    
                    markShotOnGrid(targetPlayerIndex, r, c, result, targetPlayerIndex === playerIndex && !amISpectator);

                    let srm = `Tir de ${shooterName} sur ${targetName} en ${String.fromCharCode(65 + c)}${r + 1} -> ${result}`;
                    if (sunkShipName && result === ModelConstants.ShotResult.COULE) {
                        srm += ` (${sunkShipName} COULÉ!)`;
                    }
                    updateStatus(srm); addGameLog(srm);
                } else {
                    updateStatus("Erreur: SHOT_RESULT malformé."); addGameLog(`Erreur: SHOT_RESULT malformé: ${payload}`);
                }
                break;

            case "GAME_OVER": // winnerName:winnerIndex OR DRAW
                let winMsg;
                if (payload.toUpperCase() === "DRAW" || payload.toUpperCase() === "GAME_OVER_DRAW") {
                    winMsg = "PARTIE TERMINÉE! Match Nul.";
                } else {
                    const goi = payload.split(":", 2);
                    winMsg = `PARTIE TERMINÉE! Gagnant: ${goi[0]}`;
                }
                alert(winMsg); // Simple alert for now
                updateStatus(`${winMsg}. Retour au lobby demandé...`); addGameLog(`${winMsg}. Retour au lobby demandé par le serveur.`);
                myTurn = false; inGame = false; placementPhase = false;
                // Server should follow up with REQ_NAME or LOBBY_STATE to transition client
                break;
            
            case "GAME_OVER_DISCONNECT": // disconnectedPlayerName
                const dPlayer = payload;
                const dMsg = `PARTIE TERMINÉE: ${dPlayer} s'est déconnecté.`;
                alert(dMsg);
                updateStatus(`${dMsg}. Retour au lobby demandé...`); addGameLog(`${dMsg}. Retour au lobby demandé par le serveur.`);
                myTurn = false; inGame = false; placementPhase = false;
                break;

            case "PLAYER_LEFT": // playerName:playerIndex (playerIndex only if it was a game player)
            case "PLAYER_LEFT_GAME_SPECTATOR": // playerName (spectator who left)
                const lInfo = payload.split(":", 2);
                const lPlayerName = lInfo[0];
                let lPlayerIdx = -1;
                if (lInfo.length > 1 && command === "PLAYER_LEFT") {
                    try { lPlayerIdx = parseInt(lInfo[1]); } catch (e) { /* ignore */ }
                }
                const plm = `Joueur ${lPlayerName} ${command === "PLAYER_LEFT_GAME_SPECTATOR" ? "(spectateur)" : ""} a quitté.`;
                updateStatus(plm); addGameLog(plm);

                if (lPlayerIdx !== -1 && command === "PLAYER_LEFT" && playerGridCells[lPlayerIdx]) {
                    // Gray out or mark the grid of the player who left
                    const gridPanel = document.getElementById(`grid-panel-${lPlayerIdx}`);
                    if (gridPanel) gridPanel.classList.add('disabled-player-grid'); // Add a class for styling
                    
                    for (let r_idx = 0; r_idx < ModelConstants.TAILLE_GRILLE; r_idx++) {
                        for (let c_idx = 0; c_idx < ModelConstants.TAILLE_GRILLE; c_idx++) {
                            const cell = playerGridCells[lPlayerIdx]?.[r_idx]?.[c_idx];
                            if (cell) {
                                cell.textContent = "OUT";
                                cell.classList.add('opponent-left');
                                cell.classList.add('disabled'); // Make unclickable via CSS if needed
                                cell.onclick = null; // Remove click listener
                            }
                        }
                    }
                    setGridEnabled(lPlayerIdx, false);
                    delete allPlayerNames[lPlayerIdx];
                }
                if (lPlayerIdx === playerIndex && command === "PLAYER_LEFT") { // I was the one who left/got disconnected
                    updateStatus("Vous avez été déconnecté de la partie. Retour au lobby...");
                    inGame = false; myTurn = false; placementPhase = false;
                    // Server should send REQ_NAME or LOBBY_STATE
                }
                break;


            case "NEW_CHAT_MSG": // senderName:messageContent
                const cp = payload.split(":", 2);
                const senderName = (cp.length === 2) ? cp[0] : "Serveur";
                const chatMessageContent = (cp.length === 2) ? cp[1] : payload;
                appendChatMessage(`${senderName}: ${chatMessageContent}`);
                // Mention highlighting can be added here
                break;
            
            case "LOBBY_COUNTDOWN_STARTED": // seconds
                 lobbyArea.value += `\nCompte à rebours de ${payload}s démarré !`;
                 addGameLog(`Lobby: Compte à rebours de ${payload}s démarré !`);
                 break;
            case "LOBBY_COUNTDOWN_CANCELLED":
                 lobbyArea.value += `\nCompte à rebours annulé.`;
                 addGameLog(`Lobby: Compte à rebours annulé.`);
                 break;
            case "LOBBY_TIMER_ENDED_NO_GAME": // reason
                 lobbyArea.value += `\nMinuteur terminé. ${payload}. En attente...`;
                 addGameLog(`Lobby: Minuteur terminé. ${payload}. En attente...`);
                 break;

            case "ERROR":
                updateStatus(`Erreur serveur: ${payload}`);
                addGameLog(`Erreur serveur: ${payload}`);
                if (payload.includes("nom est déjà utilisé") || payload.includes("nom ne peut pas être vide") || payload.includes("15 caractères max")) {
                    playerNameField.disabled = false;
                    playerNameField.focus();
                    connectButton.disabled = false;
                    serverIpField.disabled = false; // Allow changing server IP too
                    nameSuccessfullySetThisSession = false;
                } else if (payload.includes("Serveur plein")) {
                    connectButton.disabled = false;
                    serverIpField.disabled = false;
                    playerNameField.disabled = false;
                } else if (connectionView.style.display === 'block') { // Fallback on connection screen
                    connectButton.disabled = false;
                    playerNameField.disabled = false;
                    serverIpField.disabled = false;
                    updateStatus(`Erreur: ${payload}. Veuillez réessayer.`);
                }
                break;
            default:
                console.warn("Message serveur inconnu: " + command + " " + payload);
                addGameLog(`Message serveur non traité: ${command}${payload ? ':'+payload : ''}`);
        }
    }

    // --- Grid Logic ---
    function createGridPanelDOM(containerElement, size, isMyBoard, boardOwnerPlayerIndex, title) {
        const wrapper = document.createElement('div');
        wrapper.className = 'grid-panel-wrapper';
        if (title) {
            const titleElem = document.createElement('h4');
            titleElem.textContent = title;
            wrapper.appendChild(titleElem);
        }

        const gridPanel = document.createElement('div');
        gridPanel.className = 'grid-panel';
        gridPanel.id = `grid-panel-${boardOwnerPlayerIndex}`;
        gridPanel.style.gridTemplateColumns = `repeat(${size}, 1fr)`;
        gridPanel.style.gridTemplateRows = `repeat(${size}, 1fr)`;

        playerGridCells[boardOwnerPlayerIndex] = [];

        for (let r = 0; r < size; r++) {
            playerGridCells[boardOwnerPlayerIndex][r] = [];
            for (let c = 0; c < size; c++) {
                const cell = document.createElement('div');
                cell.className = 'grid-cell';
                cell.id = `cell-${boardOwnerPlayerIndex}-${r}-${c}`;
                cell.dataset.r = r;
                cell.dataset.c = c;
                cell.dataset.owner = boardOwnerPlayerIndex;
                // Initial text based on whose board it is (client's perspective)
                cell.textContent = (boardOwnerPlayerIndex === playerIndex && !amISpectator) ? ModelConstants.MY_EMPTY : ModelConstants.OPP_UNKNOWN;


                cell.addEventListener('click', () => onCellClick(boardOwnerPlayerIndex, r, c));
                if (isMyBoard && !amISpectator) { // Only add mouse listeners for preview to my own board
                    cell.addEventListener('mouseenter', () => onCellMouseEnter(r, c));
                    cell.addEventListener('mouseleave', () => onCellMouseLeave(r, c));
                }

                gridPanel.appendChild(cell);
                playerGridCells[boardOwnerPlayerIndex][r][c] = cell;
            }
        }
        wrapper.appendChild(gridPanel);
        containerElement.appendChild(wrapper);
    }

    function onCellClick(clickedBoardOwnerIndex, r, c) {
        if (amISpectator) return;

        const isMyOwnBoardForAction = (clickedBoardOwnerIndex === playerIndex);

        if (isMyOwnBoardForAction && placementPhase && currentShipToPlace && inGame) {
            const orientation = horizontalRadioButton.checked;
            sendMessage(`PLACE_SHIP:${currentShipToPlace.enumName}:${r}:${c}:${orientation}`);
             // Preview should be cleared by server response (PLACEMENT_ACCEPTED/REJECTED) or by next ship
        } else if (!isMyOwnBoardForAction && myTurn && inGame && !placementPhase) {
            // Check if cell was already shot
            const cellElement = playerGridCells[clickedBoardOwnerIndex]?.[r]?.[c];
            if (cellElement && (cellElement.classList.contains('hit') || cellElement.classList.contains('miss') || cellElement.classList.contains('sunk'))) {
                addGameLog("Vous avez déjà tiré sur cette case.");
                return;
            }
            sendMessage(`FIRE_SHOT:${clickedBoardOwnerIndex}:${r}:${c}`);
        }
    }

    function onCellMouseEnter(r, c) {
        if (amISpectator || !placementPhase || !currentShipToPlace || playerIndex === -1) return;
        previewShipPlacement(playerIndex, r, c, currentShipToPlace, horizontalRadioButton.checked);
    }

    function onCellMouseLeave(r, c) {
        if (amISpectator || !placementPhase || !currentShipToPlace || playerIndex === -1) return;
        clearPlacementPreview(playerIndex);
    }

    function previewShipPlacement(boardOwnerIdx, startR, startC, shipType, isHorizontal) {
        clearPlacementPreview(boardOwnerIdx); // Clear previous preview first
        if (!shipType || !playerGridCells[boardOwnerIdx]) return;

        let isValidPlacement = true;
        const cellsToPreview = [];

        for (let i = 0; i < shipType.taille; i++) {
            const r = isHorizontal ? startR : startR + i;
            const c = isHorizontal ? startC + i : startC;

            if (r < 0 || r >= ModelConstants.TAILLE_GRILLE || c < 0 || c >= ModelConstants.TAILLE_GRILLE) {
                isValidPlacement = false;
                break; // Ship out of bounds
            }
            
            const cellElement = playerGridCells[boardOwnerIdx][r][c];
            if (!cellElement) { // Should not happen if grid is initialized correctly
                isValidPlacement = false; break;
            }

            // Check against already placed ships (client-side check for immediate feedback)
            for (const placedShip of myPlacedShips) {
                if (placedShip.cells.some(pc => pc.r === r && pc.c === c)) {
                    isValidPlacement = false; // Overlaps with an already placed ship
                    break;
                }
            }
            if (!isValidPlacement) break;

            cellsToPreview.push(cellElement);
        }

        cellsToPreview.forEach(cell => {
            cell.classList.add(isValidPlacement ? 'preview-valid' : 'preview-invalid');
        });
    }
    
    function clearPlacementPreview(boardOwnerIdx) {
        if (!playerGridCells[boardOwnerIdx]) return;
        for (let r = 0; r < ModelConstants.TAILLE_GRILLE; r++) {
            for (let c = 0; c < ModelConstants.TAILLE_GRILLE; c++) {
                const cell = playerGridCells[boardOwnerIdx][r]?.[c];
                if (cell) {
                    cell.classList.remove('preview-valid', 'preview-invalid');
                }
            }
        }
    }


    function confirmShipPlacementOnGrid(boardOwnerIndex, shipType, r, c, isHorizontal) {
        if (!playerGridCells[boardOwnerIndex]) return;
        for (let i = 0; i < shipType.taille; i++) {
            const currentR = isHorizontal ? r : r + i;
            const currentC = isHorizontal ? c + i : c;

            if (currentR >= 0 && currentR < ModelConstants.TAILLE_GRILLE &&
                currentC >= 0 && currentC < ModelConstants.TAILLE_GRILLE) {
                const cell = playerGridCells[boardOwnerIndex][currentR][currentC];
                if (cell) {
                    cell.classList.add('ship');
                    cell.textContent = shipType.spriteChar;
                    // cell.classList.add('disabled'); // Visually placed, disable further clicks for placement on these cells
                }
            }
        }
        clearPlacementPreview(boardOwnerIndex); // Clear any lingering preview
    }

    function markShotOnGrid(boardOwnerIndex, r, c, shotResult, onMyBoard) {
        if (r < 0 || r >= ModelConstants.TAILLE_GRILLE || c < 0 || c >= ModelConstants.TAILLE_GRILLE || !playerGridCells[boardOwnerIndex]) return;
        const cell = playerGridCells[boardOwnerIndex][r]?.[c];
        if (!cell) return;

        // Clear any previous state classes like 'ship' if it's now hit/miss
        cell.classList.remove('ship', 'preview-valid', 'preview-invalid', 'hit', 'miss', 'sunk', 'my-hit-on-me', 'my-miss-on-me');
        cell.textContent = ''; // Clear sprite char before setting new text

        let displayChar;
        let cssClass;

        switch (shotResult) {
            case ModelConstants.ShotResult.TOUCHE:
                displayChar = onMyBoard ? ModelConstants.MY_HIT_ON_ME : ModelConstants.OPP_HIT;
                cssClass = onMyBoard ? 'my-hit-on-me' : 'hit';
                break;
            case ModelConstants.ShotResult.COULE:
                displayChar = onMyBoard ? ModelConstants.MY_HIT_ON_ME : ModelConstants.OPP_SUNK; // Visually same as hit on my board, but server knows it's sunk. Opponent gets '!'
                cssClass = onMyBoard ? 'my-hit-on-me' : 'sunk'; // Sunk is more specific than hit
                break;
            case ModelConstants.ShotResult.MANQUE:
                displayChar = onMyBoard ? ModelConstants.MY_MISS_ON_ME : ModelConstants.OPP_MISS;
                cssClass = onMyBoard ? 'my-miss-on-me' : 'miss';
                break;
            case ModelConstants.ShotResult.DEJA_JOUE:
            case ModelConstants.ShotResult.ERREUR:
            default:
                // Optionally add a visual indication for "already played" if not handled by disabling cell
                return;
        }
        cell.textContent = displayChar;
        cell.classList.add(cssClass);
        cell.classList.add('disabled'); // Shot cells are usually disabled for further clicks
    }

    function setGridEnabled(boardOwnerIndex, enabled) {
        if (!playerGridCells[boardOwnerIndex]) return;

        const isMyOwnBoard = (boardOwnerIndex === playerIndex && !amISpectator);

        for (let r = 0; r < ModelConstants.TAILLE_GRILLE; r++) {
            for (let c = 0; c < ModelConstants.TAILLE_GRILLE; c++) {
                const cell = playerGridCells[boardOwnerIndex][r]?.[c];
                if (!cell) continue;

                if (amISpectator) {
                    cell.classList.add('disabled'); // Always disabled for spectators
                    continue;
                }

                // If cell already indicates a shot (hit, miss, sunk) or a confirmed ship on my board (unless in placement phase for empty cells)
                // it should remain visually/logically disabled for new actions.
                const isShotCell = cell.classList.contains('hit') || cell.classList.contains('miss') || cell.classList.contains('sunk') || cell.classList.contains('my-hit-on-me') || cell.classList.contains('my-miss-on-me');
                const isMyConfirmedShipCell = cell.classList.contains('ship');


                if (isMyOwnBoard) { // My own grid
                    if (placementPhase && currentShipToPlace) {
                        // Enable only if cell is empty (no confirmed ship on it) AND general 'enabled' flag is true
                        cell.classList.toggle('disabled', !(enabled && !isMyConfirmedShipCell));
                    } else {
                        cell.classList.add('disabled'); // My board is not clickable outside placement or if no ship to place
                    }
                } else { // Opponent's grid
                    if (inGame && !placementPhase && myTurn) {
                        // Enable only if cell has not been shot yet AND general 'enabled' flag is true
                        cell.classList.toggle('disabled', !(enabled && !isShotCell));
                    } else {
                        cell.classList.add('disabled'); // Opponent board not clickable if not my turn or not in combat
                    }
                }
            }
        }
    }


    // --- Chat & Log ---
    function sendChatMessageAction() {
        const message = chatInputField.value.trim();
        if (message !== "" && socket && socket.readyState === WebSocket.OPEN && nameSuccessfullySetThisSession) {
            sendMessage(`CHAT_MSG:${message}`);
            chatInputField.value = "";
        } else if (!nameSuccessfullySetThisSession) {
            updateStatus("Erreur: Définissez votre nom pour utiliser le chat.");
            if (!socket || socket.readyState !== WebSocket.OPEN || connectionView.style.display === 'block') {
                 alert("Vous devez d'abord vous connecter et définir un nom pour utiliser le chat.");
            }
        } else if (!socket || socket.readyState !== WebSocket.OPEN) {
             updateStatus("Erreur: Non connecté au serveur.");
        }
    }
    
    // --- Helper to add enumName to ShipType objects for easier message sending ---
    function enhanceShipTypes() {
        for (const key in ModelConstants.ShipType) {
            ModelConstants.ShipType[key].enumName = key;
        }
    }

    // --- Start the client ---
    enhanceShipTypes();
    init();
});