package com.example.bataillenavale.model; // Exemple de package

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


public class BatailleNavaleGame {

    public enum GamePhase {
        PLACEMENT_BATEAUX,
        COMBAT,
        TERMINE
    }

    private final PlayerBoard[] playerBoards;
    private final int nombreJoueursInitial;
    private List<Integer> joueursActifsIndices; // Liste des INDEX GLOBAUX des joueurs encore actifs
    private int joueurCourantIndexDansListeActifs; // Index dans la LISTE joueursActifsIndices
    private GamePhase phaseActuelle;
    private int gagnantIndex = -1; // Index global du gagnant

    private final List<Ship.ShipType> typesDeNaviresAPlacer = Arrays.asList(
            Ship.ShipType.PORTE_AVIONS,
            Ship.ShipType.CROISEUR,
            Ship.ShipType.CONTRE_TORPILLEUR,
            Ship.ShipType.SOUS_MARIN,
            Ship.ShipType.TORPILLEUR
    );

    private final List<List<Ship.ShipType>> naviresRestantsAPlacerParJoueur; // Indexé par l'index global du joueur


    public BatailleNavaleGame(String[] nomsJoueurs) {
        this.nombreJoueursInitial = nomsJoueurs.length;
        this.playerBoards = new PlayerBoard[nombreJoueursInitial];
        this.naviresRestantsAPlacerParJoueur = new ArrayList<>();
        this.joueursActifsIndices = new ArrayList<>();

        for (int i = 0; i < nombreJoueursInitial; i++) {
            playerBoards[i] = new PlayerBoard(nomsJoueurs[i]);
            naviresRestantsAPlacerParJoueur.add(new ArrayList<>(typesDeNaviresAPlacer));
            joueursActifsIndices.add(i); // Au début, tous les joueurs (par leur index global) sont actifs
        }

        this.joueurCourantIndexDansListeActifs = 0; // Le premier joueur dans la liste des actifs commence
        this.phaseActuelle = GamePhase.PLACEMENT_BATEAUX;
    }

    public int getNombreJoueursInitial() {
        return this.nombreJoueursInitial;
    }

    public GamePhase getPhaseActuelle() {
        return phaseActuelle;
    }

    public int getJoueurCourantIndex() { // Renvoie l'INDEX GLOBAL du joueur courant
        if (phaseActuelle == GamePhase.TERMINE || joueursActifsIndices.isEmpty()) {
            return -1;
        }
        if (joueurCourantIndexDansListeActifs < 0 || joueurCourantIndexDansListeActifs >= joueursActifsIndices.size()) {
             System.err.println("Alerte: joueurCourantIndexDansListeActifs (" + joueurCourantIndexDansListeActifs +
                                ") est hors limites pour joueursActifsIndices de taille " + joueursActifsIndices.size());
             if (joueursActifsIndices.isEmpty()) return -1;
             joueurCourantIndexDansListeActifs = 0; // Réinitialiser par sécurité au premier joueur actif
        }
        return joueursActifsIndices.get(joueurCourantIndexDansListeActifs);
    }

    public String getNomJoueurCourant() {
        int currentIndexGlobal = getJoueurCourantIndex();
        if (currentIndexGlobal != -1) {
            return playerBoards[currentIndexGlobal].getNomJoueur();
        }
        return "N/A";
    }

    public PlayerBoard getPlayerBoard(int playerGlobalIndex) {
        if (playerGlobalIndex >= 0 && playerGlobalIndex < nombreJoueursInitial) {
            return playerBoards[playerGlobalIndex];
        }
        return null;
    }

    public List<Ship.ShipType> getNaviresAPlacerPourJoueurCourant() {
        if (phaseActuelle == GamePhase.PLACEMENT_BATEAUX) {
            int joueurCourantGlobalIdx = getJoueurCourantIndex();
            if (joueurCourantGlobalIdx != -1 && joueurCourantGlobalIdx < naviresRestantsAPlacerParJoueur.size()) {
                return naviresRestantsAPlacerParJoueur.get(joueurCourantGlobalIdx);
            } else {
                 System.err.println("getNaviresAPlacerPourJoueurCourant: Index joueur courant global invalide (" + joueurCourantGlobalIdx + ")");
            }
        }
        return new ArrayList<>(); // Retourner une liste vide si pas en phase de placement ou erreur
    }

    public boolean placerNavireJoueurCourant(Ship.ShipType type, int ligne, int colonne, boolean horizontal) {
        if (phaseActuelle != GamePhase.PLACEMENT_BATEAUX) {
            System.err.println("Erreur: Tentative de placement de navire hors de la phase de placement.");
            return false;
        }

        int joueurCourantGlobalIdx = getJoueurCourantIndex();
        if (joueurCourantGlobalIdx == -1) return false; // Aucun joueur courant valide

        List<Ship.ShipType> naviresPourCeJoueur = naviresRestantsAPlacerParJoueur.get(joueurCourantGlobalIdx);
        if (!naviresPourCeJoueur.contains(type)) { // Vérifier si le type est bien dans la liste des navires à placer pour CE joueur
            System.err.println("Erreur: Le joueur " + getNomJoueurCourant() + " ne doit pas placer " + type.getNom() + " (peut-être déjà placé ou non attendu).");
            return false;
        }

        Ship nouveauNavire = new Ship(type);
        boolean placementOk = playerBoards[joueurCourantGlobalIdx].placerNavire(nouveauNavire, ligne, colonne, horizontal);

        if (placementOk) {
            naviresPourCeJoueur.remove(type); // Retirer le navire de la liste de CE joueur
            System.out.println(getNomJoueurCourant() + " a placé " + type.getNom());
            if (naviresPourCeJoueur.isEmpty()) {
                System.out.println(getNomJoueurCourant() + " a placé tous ses navires.");
                // Ne pas passer au joueur suivant ici, laisser passerAuPlacementSuivant() le gérer
            }
            // Si tous les joueurs actifs ont placé tous leurs navires, la phase change.
            // Cette vérification est mieux placée dans une méthode appelée par le serveur après chaque placement.
            if (tousLesJoueursActifsOntPlaceLeursNavires()) {
                 System.out.println("Tous les joueurs actifs ont placé leurs navires. Passage à la phase de COMBAT.");
                 phaseActuelle = GamePhase.COMBAT;
                 joueurCourantIndexDansListeActifs = 0; // Le combat commence avec le premier joueur de la liste des actifs
            }
            return true;
        }
        return false;
    }

    // Appelée par le serveur pour passer au joueur suivant en phase de placement
    // ou pour démarrer le combat si tous ont placé.
    public void passerAuJoueurSuivantPourPlacement() {
        if (phaseActuelle != GamePhase.PLACEMENT_BATEAUX) return;

        if (tousLesJoueursActifsOntPlaceLeursNavires()) {
            phaseActuelle = GamePhase.COMBAT;
            joueurCourantIndexDansListeActifs = 0; // Le combat commence avec le premier joueur actif
            System.out.println("Transition vers la phase de COMBAT.");
            return;
        }

        // Passer au joueur suivant dans la liste des joueurs actifs
        // qui n'a pas encore placé tous ses navires.
        int initialIndexDansActifs = joueurCourantIndexDansListeActifs;
        do {
            joueurCourantIndexDansListeActifs = (joueurCourantIndexDansListeActifs + 1) % joueursActifsIndices.size();
            int joueurGlobalSuivant = joueursActifsIndices.get(joueurCourantIndexDansListeActifs);
            if (!naviresRestantsAPlacerParJoueur.get(joueurGlobalSuivant).isEmpty()) {
                System.out.println("Phase de placement: au tour de " + playerBoards[joueurGlobalSuivant].getNomJoueur());
                return; // Joueur trouvé
            }
        } while (joueurCourantIndexDansListeActifs != initialIndexDansActifs);

        // Si on revient au joueur initial et que la condition tousLesJoueursActifsOntPlaceLeursNavires n'était pas vraie,
        // il y a une incohérence ou tous ont fini en même temps. La vérification au début devrait couvrir cela.
        System.err.println("Impossible de trouver un joueur suivant pour le placement, ou tous ont fini (devrait être géré).");
    }


    public boolean tousLesJoueursActifsOntPlaceLeursNavires() {
        for (int joueurGlobalIndex : joueursActifsIndices) { // Itérer seulement sur les joueurs actifs
            if (!naviresRestantsAPlacerParJoueur.get(joueurGlobalIndex).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public List<Integer> getJoueursActifsIndices() {
        return new ArrayList<>(joueursActifsIndices); // Retourner une copie pour éviter modification externe
    }


    public PlayerBoard.ShotResult tirerSurAdversaire(int targetPlayerGlobalIndex, int ligne, int colonne) {
        if (phaseActuelle != GamePhase.COMBAT) {
            System.err.println("Erreur: Tentative de tir hors de la phase de combat.");
            return PlayerBoard.ShotResult.ERREUR;
        }
        int tireurGlobalIndex = getJoueurCourantIndex();
        if (tireurGlobalIndex == -1) {
            System.err.println("Erreur de tir: Aucun joueur courant valide.");
            return PlayerBoard.ShotResult.ERREUR;
        }

        if (targetPlayerGlobalIndex < 0 || targetPlayerGlobalIndex >= nombreJoueursInitial || targetPlayerGlobalIndex == tireurGlobalIndex) {
            System.err.println("Erreur: Index de joueur cible invalide pour le tir.");
            return PlayerBoard.ShotResult.ERREUR;
        }
        if (!joueursActifsIndices.contains(targetPlayerGlobalIndex)) {
            System.err.println("Erreur: Tentative de tir sur un joueur déjà éliminé ou inactif (cible non dans joueursActifsIndices).");
            return PlayerBoard.ShotResult.DEJA_JOUE; // Ou ERREUR, car le joueur n'est plus une cible valide
        }


        PlayerBoard cibleBoard = playerBoards[targetPlayerGlobalIndex];
        PlayerBoard.ShotResult resultat = cibleBoard.recevoirTir(ligne, colonne);
        System.out.println(playerBoards[tireurGlobalIndex].getNomJoueur() + " tire sur " + cibleBoard.getNomJoueur() + " en " + ligne + "," + colonne + " -> " + resultat);

        if (resultat != PlayerBoard.ShotResult.DEJA_JOUE && resultat != PlayerBoard.ShotResult.ERREUR) {
            // Gérer l'élimination du joueur cible si tous ses navires sont coulés
            if (cibleBoard.tousNaviresCoules()) {
                System.out.println("Tous les navires de " + cibleBoard.getNomJoueur() + " sont coulés ! Il est éliminé.");
                eliminerJoueur(targetPlayerGlobalIndex); // Gère la logique d'élimination et de fin de partie
            }

            // Si la partie n'est pas terminée après le tir et l'élimination potentielle, passer au joueur suivant.
            if (phaseActuelle == GamePhase.COMBAT) {
                passerAuJoueurSuivantPourCombat();
            }
        }
        // Si DEJA_JOUE ou ERREUR, le tour ne passe pas, le joueur doit rejouer (géré par le serveur).
        return resultat;
    }

    private void eliminerJoueur(int joueurGlobalIndexAEliminer) {
        if (!joueursActifsIndices.contains(joueurGlobalIndexAEliminer)) {
            //System.out.println("Tentative d'éliminer un joueur ("+joueurGlobalIndexAEliminer+") déjà inactif.");
            return;
        }

        System.out.println("Élimination du joueur: " + playerBoards[joueurGlobalIndexAEliminer].getNomJoueur() + " (Index global: " + joueurGlobalIndexAEliminer + ")");
        joueursActifsIndices.remove(Integer.valueOf(joueurGlobalIndexAEliminer));

        // Vérifier les conditions de fin de partie
        if (joueursActifsIndices.size() <= 1) {
            phaseActuelle = GamePhase.TERMINE;
            if (joueursActifsIndices.size() == 1) {
                gagnantIndex = joueursActifsIndices.get(0); // Le seul joueur restant est le gagnant
                System.out.println("JEU TERMINE! Gagnant: " + playerBoards[gagnantIndex].getNomJoueur());
            } else { // 0 joueurs actifs (ex: élimination simultanée ou déconnexion du dernier)
                gagnantIndex = -1; // Match nul ou pas de gagnant clair
                System.out.println("JEU TERMINE! Aucun survivant ou match nul.");
            }
        } else {
            // Si la partie continue, ajuster l'index du joueur courant pour pointer vers le même joueur
            // dans la liste réduite, ou au début si le joueur courant était celui éliminé.
            // Cette logique est délicate et est mieux gérée par passerAuJoueurSuivantPourCombat.
            // L'important ici est que joueurCourantIndexDansListeActifs soit valide pour la nouvelle taille de joueursActifsIndices.
            // Si le joueur éliminé était avant ou égal au joueur courant dans l'ancienne liste, l'index doit peut-être décrémenter.
            // Cependant, passerAuJoueurSuivantPourCombat recalculera le prochain tour de manière plus propre.
            // On s'assure juste que l'index reste dans les bornes.
             if (joueurCourantIndexDansListeActifs >= joueursActifsIndices.size()) {
                joueurCourantIndexDansListeActifs = 0; // Revenir au début de la liste si l'index est hors limites
             }
        }
    }

    public void passerAuJoueurSuivantPourCombat() {
        if (phaseActuelle != GamePhase.COMBAT || joueursActifsIndices.isEmpty()) {
            return;
        }
        joueurCourantIndexDansListeActifs = (joueurCourantIndexDansListeActifs + 1) % joueursActifsIndices.size();
        System.out.println("Phase de combat: au tour de " + getNomJoueurCourant());
    }


    public int getGagnantIndex() {
        return gagnantIndex;
    }

    public int getNombreJoueursActifs() {
        return joueursActifsIndices.size();
    }

    /**
     * Gère la déconnexion d'un joueur.
     * @param playerGlobalIndex L'index global du joueur qui se déconnecte.
     * @return true si la partie peut continuer, false sinon.
     */
    public boolean handlePlayerDisconnect(int playerGlobalIndex) {
        System.out.println("Jeu: Gestion de la déconnexion du joueur avec index global " + playerGlobalIndex);
        if (!joueursActifsIndices.contains(playerGlobalIndex) && phaseActuelle != GamePhase.TERMINE) {
             // Si le joueur n'était déjà plus actif (peut-être éliminé juste avant déconnexion)
             // ou si la partie est déjà terminée, il n'y a rien de plus à faire pour la logique de jeu.
             System.out.println("Joueur " + playerGlobalIndex + " déjà inactif ou partie terminée. Aucune action de jeu supplémentaire.");
             return phaseActuelle != GamePhase.TERMINE && !joueursActifsIndices.isEmpty();
        }


        // Marquer le joueur comme "éliminé" à cause de la déconnexion
        // Si le joueur avait encore des navires, ses navires sont considérés comme perdus.
        // Si le joueur était en train de placer des navires, son placement est interrompu.
        playerBoards[playerGlobalIndex].marquerCommeAbandonne(); // Méthode à ajouter à PlayerBoard si on veut un état visuel

        boolean etaitLeJoueurCourant = (getJoueurCourantIndex() == playerGlobalIndex);

        // Retirer le joueur de la liste des joueurs actifs
        eliminerJoueur(playerGlobalIndex); // Utilise la même logique que pour une élimination en combat

        if (phaseActuelle == GamePhase.TERMINE) {
            System.out.println("Jeu: Partie terminée suite à la déconnexion.");
            return false; // La partie ne peut pas continuer
        }

        // Si la partie n'est pas terminée, et que le joueur déconnecté était le joueur courant,
        // il faut s'assurer que le tour passe correctement.
        // `eliminerJoueur` ajuste déjà joueurCourantIndexDansListeActifs si besoin,
        // ou la prochaine appel à `passerAuJoueurSuivantPourPlacement/Combat` le fera.

        if (phaseActuelle == GamePhase.PLACEMENT_BATEAUX) {
            // Si c'était le tour du joueur déconnecté de placer, ou si son départ affecte la fin du placement
            if (tousLesJoueursActifsOntPlaceLeursNavires()) {
                phaseActuelle = GamePhase.COMBAT;
                joueurCourantIndexDansListeActifs = 0;
            } else if (etaitLeJoueurCourant && !joueursActifsIndices.isEmpty()) {
                // On ne passe pas explicitement au joueur suivant ici,
                // Le serveur appellera passerAuPlacementSuivant qui déterminera le prochain joueur.
                // On s'assure que l'index est valide.
                if (joueurCourantIndexDansListeActifs >= joueursActifsIndices.size()) {
                    joueurCourantIndexDansListeActifs = 0;
                }
            }
        } else if (phaseActuelle == GamePhase.COMBAT) {
            // Si c'était le tour du joueur déconnecté de tirer,
            // `eliminerJoueur` a déjà potentiellement ajusté l'index.
            // Le serveur appellera `informerTourCombat` qui utilisera `getJoueurCourantIndex`.
            if (etaitLeJoueurCourant && !joueursActifsIndices.isEmpty()) {
                 if (joueurCourantIndexDansListeActifs >= joueursActifsIndices.size()) {
                    joueurCourantIndexDansListeActifs = 0;
                }
            }
        }

        System.out.println("Jeu: Après déconnexion du joueur " + playerGlobalIndex + ", joueurs actifs: " + joueursActifsIndices.size());
        return !joueursActifsIndices.isEmpty(); // La partie peut continuer s'il reste des joueurs actifs
    }


    // Méthodes conceptuelles que le serveur pourrait appeler (simplifiées pour l'instant)
    // Ces logiques sont maintenant plus intégrées dans handlePlayerDisconnect et la gestion de tour normale.
    public boolean passerAuJoueurSuivantApresDeconnexionPlacement() {
        if (phaseActuelle == GamePhase.TERMINE) return false;
        if (tousLesJoueursActifsOntPlaceLeursNavires()) {
             phaseActuelle = GamePhase.COMBAT;
             joueurCourantIndexDansListeActifs = 0;
             return !joueursActifsIndices.isEmpty();
        }
        // S'assurer que l'index est valide après une potentielle modification de joueursActifsIndices
        if (joueurCourantIndexDansListeActifs >= joueursActifsIndices.size() && !joueursActifsIndices.isEmpty()) {
            joueurCourantIndexDansListeActifs = 0;
        }
        return !joueursActifsIndices.isEmpty();
    }

    public boolean passerAuJoueurSuivantApresDeconnexionCombat() {
        if (phaseActuelle == GamePhase.TERMINE) return false;
        // S'assurer que l'index est valide
        if (joueurCourantIndexDansListeActifs >= joueursActifsIndices.size() && !joueursActifsIndices.isEmpty()) {
            joueurCourantIndexDansListeActifs = 0;
        }
        return !joueursActifsIndices.isEmpty();
    }


    public void placerNaviresAleatoirementPourJoueur(int playerIdx) {
        if (playerIdx < 0 || playerIdx >= nombreJoueursInitial) return;

        PlayerBoard board = playerBoards[playerIdx];
        List<Ship.ShipType> naviresPourCeJoueur = naviresRestantsAPlacerParJoueur.get(playerIdx);
        if (naviresPourCeJoueur.isEmpty()) {
             System.out.println("Pas de navires à placer aléatoirement pour " + board.getNomJoueur() + ", déjà fait.");
             return;
        }

        List<Ship.ShipType> aPlacerCeTourCi = new ArrayList<>(naviresPourCeJoueur); // Copie pour itération
        Random random = new Random();

        for (Ship.ShipType type : aPlacerCeTourCi) {
            boolean place = false;
            int tentatives = 0;
            while (!place && tentatives < 100) { // Limiter les tentatives pour éviter boucle infinie
                int ligne = random.nextInt(PlayerBoard.TAILLE_GRILLE);
                int colonne = random.nextInt(PlayerBoard.TAILLE_GRILLE);
                boolean horizontal = random.nextBoolean();

                // Utiliser la méthode de placement existante qui gère la logique et met à jour naviresRestantsAPlacerParJoueur
                // Temporairement, on simule le fait que c'est le tour de ce joueur
                // Ceci est une simplification, car la logique de tour est normalement gérée par getJoueurCourantIndex.
                // Pour un vrai placement aléatoire au nom d'un joueur, il faudrait s'assurer que les conditions sont bonnes.

                // Sauvegarder l'état du tour actuel
                GamePhase phasePrecedente = this.phaseActuelle;
                int joueurCourantIndexDansListeActifsPrecedent = this.joueurCourantIndexDansListeActifs;
                List<Integer> joueursActifsIndicesPrecedent = new ArrayList<>(this.joueursActifsIndices);

                // Simuler le tour pour le joueur spécifié s'il est actif
                if (joueursActifsIndices.contains(playerIdx)) {
                    this.phaseActuelle = GamePhase.PLACEMENT_BATEAUX; // Forcer la phase
                    // Trouver l'index de playerIdx dans la liste des joueurs actifs
                    int tempIndexDansActifs = joueursActifsIndices.indexOf(playerIdx);
                    if (tempIndexDansActifs != -1) {
                         this.joueurCourantIndexDansListeActifs = tempIndexDansActifs;

                         if (placerNavireJoueurCourant(type, ligne, colonne, horizontal)) {
                             place = true; // Le navire a été placé et retiré de la liste via placerNavireJoueurCourant
                         }
                    }
                } else if (naviresRestantsAPlacerParJoueur.get(playerIdx).contains(type)) {
                    // Si le joueur n'est pas actif mais a des navires à placer (scénario de débug/test)
                    // on peut essayer de le placer directement sur son board, mais sans affecter la liste des joueurs actifs
                    Ship tempShip = new Ship(type);
                     if (board.placerNavire(tempShip, ligne, colonne, horizontal)) {
                         naviresRestantsAPlacerParJoueur.get(playerIdx).remove(type);
                         place = true;
                     }
                }


                // Restaurer l'état du tour
                this.phaseActuelle = phasePrecedente;
                this.joueursActifsIndices = joueursActifsIndicesPrecedent;
                this.joueurCourantIndexDansListeActifs = joueurCourantIndexDansListeActifsPrecedent;

                tentatives++;
            }
            if (!place) {
                System.err.println("Impossible de placer aléatoirement " + type.getNom() + " pour " + board.getNomJoueur() + " après " + tentatives + " tentatives.");
            }
        }
         System.out.println("Navires (restants) placés aléatoirement pour " + board.getNomJoueur());

         // Après placement aléatoire, vérifier si cela déclenche la phase de combat
         if (phaseActuelle == GamePhase.PLACEMENT_BATEAUX && tousLesJoueursActifsOntPlaceLeursNavires()) {
             phaseActuelle = GamePhase.COMBAT;
             joueurCourantIndexDansListeActifs = 0;
             System.out.println("Placement aléatoire terminé pour tous les joueurs actifs. Passage au COMBAT.");
         }
    }
}