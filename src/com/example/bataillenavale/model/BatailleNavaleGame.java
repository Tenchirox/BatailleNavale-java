package com.example.bataillenavale.model; // Exemple de package

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;


public class BatailleNavaleGame {

    public enum GamePhase {
        PLACEMENT_BATEAUX,
        COMBAT,
        TERMINE
    }

    private final PlayerBoard[] playerBoards; 
    private final int nombreJoueursInitial; // Ce champ existe déjà
    private List<Integer> joueursActifsIndices; 
    private int joueurCourantIndexDansListeActifs; 
    private GamePhase phaseActuelle;
    private int gagnantIndex = -1; 

    private final List<Ship.ShipType> typesDeNaviresAPlacer = Arrays.asList(
            Ship.ShipType.PORTE_AVIONS,
            Ship.ShipType.CROISEUR,
            Ship.ShipType.CONTRE_TORPILLEUR,
            Ship.ShipType.SOUS_MARIN,
            Ship.ShipType.TORPILLEUR
    );
    
    private final List<List<Ship.ShipType>> naviresRestantsAPlacerParJoueur;


    public BatailleNavaleGame(String[] nomsJoueurs) {
        this.nombreJoueursInitial = nomsJoueurs.length;
        this.playerBoards = new PlayerBoard[nombreJoueursInitial];
        this.naviresRestantsAPlacerParJoueur = new ArrayList<>();
        this.joueursActifsIndices = new ArrayList<>();

        for (int i = 0; i < nombreJoueursInitial; i++) {
            playerBoards[i] = new PlayerBoard(nomsJoueurs[i]);
            naviresRestantsAPlacerParJoueur.add(new ArrayList<>(typesDeNaviresAPlacer));
            joueursActifsIndices.add(i); 
        }

        this.joueurCourantIndexDansListeActifs = 0; 
        this.phaseActuelle = GamePhase.PLACEMENT_BATEAUX;
    }

    // MÉTHODE AJOUTÉE
    public int getNombreJoueursInitial() {
        return this.nombreJoueursInitial;
    }

    public GamePhase getPhaseActuelle() {
        return phaseActuelle;
    }

    public int getJoueurCourantIndex() {
        if (phaseActuelle == GamePhase.TERMINE || joueursActifsIndices.isEmpty()) {
            return -1; 
        }
        if (joueurCourantIndexDansListeActifs < 0 || joueurCourantIndexDansListeActifs >= joueursActifsIndices.size()) {
             // Cela peut arriver si un joueur est éliminé et que l'index n'est pas correctement ajusté
             // ou si la liste des joueurs actifs est vide mais la partie n'est pas marquée comme terminée.
             System.err.println("Alerte: joueurCourantIndexDansListeActifs (" + joueurCourantIndexDansListeActifs + 
                                ") est hors limites pour joueursActifsIndices de taille " + joueursActifsIndices.size());
             if (joueursActifsIndices.isEmpty()) return -1;
             joueurCourantIndexDansListeActifs = 0; // Réinitialiser par sécurité
        }
        return joueursActifsIndices.get(joueurCourantIndexDansListeActifs);
    }
    
    public String getNomJoueurCourant() {
        int currentIndex = getJoueurCourantIndex();
        if (currentIndex != -1) {
            return playerBoards[currentIndex].getNomJoueur();
        }
        return "N/A";
    }

    public PlayerBoard getPlayerBoard(int playerIndex) {
        if (playerIndex >= 0 && playerIndex < nombreJoueursInitial) {
            return playerBoards[playerIndex];
        }
        return null;
    }
    
    public List<Ship.ShipType> getNaviresAPlacerPourJoueurCourant() {
        if (phaseActuelle == GamePhase.PLACEMENT_BATEAUX) {
            int currentIndex = getJoueurCourantIndex();
            if (currentIndex != -1 && currentIndex < naviresRestantsAPlacerParJoueur.size()) { // Ajout de la vérification de limite
                return naviresRestantsAPlacerParJoueur.get(currentIndex);
            } else {
                 System.err.println("getNaviresAPlacerPourJoueurCourant: Index joueur courant invalide (" + currentIndex + ")");
            }
        }
        return new ArrayList<>(); 
    }

    public boolean placerNavireJoueurCourant(Ship.ShipType type, int ligne, int colonne, boolean horizontal) {
        if (phaseActuelle != GamePhase.PLACEMENT_BATEAUX) {
            System.err.println("Erreur: Tentative de placement de navire hors de la phase de placement.");
            return false;
        }
        
        int currentIndex = getJoueurCourantIndex();
        if (currentIndex == -1) return false;

        List<Ship.ShipType> naviresAPlacer = naviresRestantsAPlacerParJoueur.get(currentIndex);
        if (!naviresAPlacer.contains(type)) {
            System.err.println("Erreur: Le joueur " + getNomJoueurCourant() + " ne doit plus placer de " + type.getNom() + " ou l'a déjà placé.");
            return false;
        }

        Ship nouveauNavire = new Ship(type);
        boolean placementOk = playerBoards[currentIndex].placerNavire(nouveauNavire, ligne, colonne, horizontal);

        if (placementOk) {
            naviresAPlacer.remove(type); 
            System.out.println(getNomJoueurCourant() + " a placé " + type.getNom());
            if (naviresAPlacer.isEmpty()) {
                System.out.println(getNomJoueurCourant() + " a placé tous ses navires.");
                if (tousLesJoueursOntPlaceLeursNavires()) {
                    System.out.println("Tous les joueurs ont placé leurs navires. Passage à la phase de COMBAT.");
                    phaseActuelle = GamePhase.COMBAT;
                    joueurCourantIndexDansListeActifs = 0; 
                } else {
                    passerAuJoueurSuivantPourPlacement();
                }
            }
            return true;
        }
        return false;
    }
    
    public void passerAuJoueurSuivantPourPlacement() { 
        joueurCourantIndexDansListeActifs = (joueurCourantIndexDansListeActifs + 1) % joueursActifsIndices.size();
        System.out.println("Phase de placement: au tour de " + getNomJoueurCourant());
    }

    public boolean tousLesJoueursOntPlaceLeursNavires() {
        for (int i = 0; i < nombreJoueursInitial; i++) { // Itérer sur tous les joueurs initiaux
            if (!naviresRestantsAPlacerParJoueur.get(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
    
    public List<Integer> getJoueursActifsIndices() {
        return joueursActifsIndices;
    }


    public PlayerBoard.ShotResult tirerSurAdversaire(int targetPlayerGlobalIndex, int ligne, int colonne) {
        if (phaseActuelle != GamePhase.COMBAT) {
            System.err.println("Erreur: Tentative de tir hors de la phase de combat.");
            return PlayerBoard.ShotResult.ERREUR;
        }
        int tireurGlobalIndex = getJoueurCourantIndex();
        if (tireurGlobalIndex == -1) return PlayerBoard.ShotResult.ERREUR;

        if (targetPlayerGlobalIndex < 0 || targetPlayerGlobalIndex >= nombreJoueursInitial || targetPlayerGlobalIndex == tireurGlobalIndex) {
            System.err.println("Erreur: Index de joueur cible invalide.");
            return PlayerBoard.ShotResult.ERREUR;
        }
        if (!joueursActifsIndices.contains(targetPlayerGlobalIndex)) {
            System.err.println("Erreur: Tentative de tir sur un joueur déjà éliminé ou inactif.");
            return PlayerBoard.ShotResult.DEJA_JOUE; 
        }


        PlayerBoard cibleBoard = playerBoards[targetPlayerGlobalIndex];
        PlayerBoard.ShotResult resultat = cibleBoard.recevoirTir(ligne, colonne);
        System.out.println(getNomJoueurCourant() + " tire sur " + cibleBoard.getNomJoueur() + " en " + ligne + "," + colonne + " -> " + resultat);

        if (resultat != PlayerBoard.ShotResult.DEJA_JOUE && resultat != PlayerBoard.ShotResult.ERREUR) {
            if (cibleBoard.tousNaviresCoules()) {
                System.out.println("Tous les navires de " + cibleBoard.getNomJoueur() + " sont coulés !");
                
                // Garder l'index du joueur courant AVANT de potentiellement modifier la liste des joueurs actifs
                int indexDuJoueurCourantDansAncienneListe = joueurCourantIndexDansListeActifs;

                boolean joueurElimine = joueursActifsIndices.remove(Integer.valueOf(targetPlayerGlobalIndex));
                if(joueurElimine) System.out.println(cibleBoard.getNomJoueur() + " a été éliminé.");

                if (joueursActifsIndices.size() <= 1) { 
                    phaseActuelle = GamePhase.TERMINE;
                    if (joueursActifsIndices.size() == 1) {
                        gagnantIndex = joueursActifsIndices.get(0);
                        System.out.println("JEU TERMINE! Gagnant: " + playerBoards[gagnantIndex].getNomJoueur());
                    } else { 
                        gagnantIndex = -1; 
                        System.out.println("JEU TERMINE! Aucun survivant.");
                    }
                } else {
                    // Ajuster joueurCourantIndexDansListeActifs
                    // Si le joueur éliminé était avant l'index actuel, l'index actuel doit être décrémenté
                    // pour pointer vers le même joueur dans la liste réduite.
                    // Si l'index actuel était celui du joueur éliminé, il sera hors limites.
                    // Si le joueur courant était celui qui a tiré (et n'est pas éliminé),
                    // on doit trouver sa nouvelle position dans la liste joueursActifsIndices.
                    
                    // Si le joueur éliminé était avant ou à l'index du joueur courant dans la *nouvelle* liste potentielle
                    if (targetPlayerGlobalIndex < joueursActifsIndices.get(indexDuJoueurCourantDansAncienneListe % joueursActifsIndices.size())) {
                         // Cela signifie que l'index du joueur courant dans la liste réduite a diminué
                         // Cette logique est complexe. Il est plus simple de trouver le joueur courant dans la nouvelle liste.
                    }

                    // La logique la plus simple est de passer au joueur suivant DANS la nouvelle liste des joueurs actifs.
                    // Si le joueur courant (tireur) est toujours actif, son index dans la liste `joueursActifsIndices`
                    // a pu changer si un joueur avant lui a été éliminé.
                    // On doit s'assurer que `joueurCourantIndexDansListeActifs` pointe vers le bon joueur
                    // pour le prochain tour.

                    // Si le joueur qui a tiré est toujours actif, son tour est terminé (sauf règle spéciale)
                    // On passe au suivant dans la liste des joueurs actifs.
                    // S'il n'y a plus qu'un joueur, la partie est terminée (géré au-dessus).
                    // L'index du joueur courant doit être mis à jour par rapport à la NOUVELLE liste des joueurs actifs.
                    // Si le joueur courant était à l'index X et un joueur avant lui est retiré, il est maintenant à X-1.
                    // Si le joueur courant est toujours dans la liste, on cherche son nouvel index.
                    int tireurGlobalIndexActuel = getJoueurCourantIndex(); // Le joueur qui vient de tirer
                    int nouvelIndexDuTireurDansActifs = joueursActifsIndices.indexOf(tireurGlobalIndexActuel);

                    if (nouvelIndexDuTireurDansActifs != -1) { // S'il est toujours actif
                        joueurCourantIndexDansListeActifs = (nouvelIndexDuTireurDansActifs + 1) % joueursActifsIndices.size();
                    } else {
                        // Le tireur a été éliminé (ne devrait pas arriver car on ne peut pas se cibler)
                        // ou une erreur s'est produite. Réinitialiser au premier joueur actif.
                        joueurCourantIndexDansListeActifs = 0;
                    }
                }
            } else { 
                joueurCourantIndexDansListeActifs = (joueurCourantIndexDansListeActifs + 1) % joueursActifsIndices.size();
            }
        }
        
        return resultat;
    }

    public int getGagnantIndex() {
        return gagnantIndex;
    }
    
    public int getNombreJoueursActifs() {
        if (joueursActifsIndices == null) return 0;
        return joueursActifsIndices.size();
    }

    public void placerNaviresAleatoirementPourJoueur(int playerIdx) {
        PlayerBoard board = playerBoards[playerIdx];
        List<Ship.ShipType> aPlacer = new ArrayList<>(typesDeNaviresAPlacer); 
        Random random = new Random();

        for (Ship.ShipType type : aPlacer) {
            boolean place = false;
            int tentatives = 0;
            while (!place && tentatives < 100) { 
                int ligne = random.nextInt(PlayerBoard.TAILLE_GRILLE);
                int colonne = random.nextInt(PlayerBoard.TAILLE_GRILLE);
                boolean horizontal = random.nextBoolean();
                
                Ship tempShip = new Ship(type);
                if (board.placerNavire(tempShip, ligne, colonne, horizontal)) {
                    List<Ship.ShipType> naviresRestants = naviresRestantsAPlacerParJoueur.get(playerIdx);
                    if(naviresRestants.contains(type)) naviresRestants.remove(type);
                    place = true;
                }
                tentatives++;
            }
            if (!place) {
                System.err.println("Impossible de placer aléatoirement " + type.getNom() + " pour " + board.getNomJoueur());
            }
        }
         System.out.println("Navires placés aléatoirement pour " + board.getNomJoueur());
         if (naviresRestantsAPlacerParJoueur.get(playerIdx).isEmpty() && tousLesJoueursOntPlaceLeursNavires()) {
             if (phaseActuelle == GamePhase.PLACEMENT_BATEAUX) { 
                phaseActuelle = GamePhase.COMBAT;
                joueurCourantIndexDansListeActifs = 0; 
                System.out.println("Placement aléatoire terminé pour tous. Passage au COMBAT.");
             }
         }
    }
}
