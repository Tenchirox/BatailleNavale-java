package com.example.bataillenavale.model; // Exemple de package

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlayerBoard {
    public static final int TAILLE_GRILLE = 10; // Grille standard de 10x10

    // États des cases pour la grille de ce joueur (vue par lui-même)
    public static final char CASE_VIDE = ' '; // Eau non touchée
    public static final char CASE_NAVIRE = 'N'; // Segment de navire non touché
    public static final char CASE_NAVIRE_TOUCHE = 'X'; // Segment de navire touché
    public static final char CASE_MANQUE = 'O'; // Tir manqué dans l'eau
    public static final char CASE_ABANDON = 'A'; // Case d'un joueur ayant abandonné

    private final char[][] grille; // La grille de ce joueur avec ses navires et les tirs reçus
    private final List<Ship> navires;
    private final String nomJoueur;
    private boolean aAbandonne = false;


    public enum ShotResult {
        MANQUE,
        TOUCHE,
        COULE,
        DEJA_JOUE, // Case déjà tirée
        ERREUR // Mouvement invalide (hors grille etc)
    }

    public PlayerBoard(String nomJoueur) {
        this.nomJoueur = nomJoueur;
        this.grille = new char[TAILLE_GRILLE][TAILLE_GRILLE];
        this.navires = new ArrayList<>();
        initialiserGrille();
    }

    private void initialiserGrille() {
        for (int i = 0; i < TAILLE_GRILLE; i++) {
            for (int j = 0; j < TAILLE_GRILLE; j++) {
                grille[i][j] = CASE_VIDE;
            }
        }
    }

    public String getNomJoueur() {
        return nomJoueur;
    }

    public char getEtatCase(int ligne, int colonne) {
        if (ligne < 0 || ligne >= TAILLE_GRILLE || colonne < 0 || colonne >= TAILLE_GRILLE) {
            return ' '; // Hors grille
        }
        return grille[ligne][colonne];
    }

    public List<Ship> getNavires() {
        return new ArrayList<>(navires); // Retourner une copie
    }

    public boolean placerNavire(Ship navire, int ligne, int colonne, boolean horizontal) {
        if (aAbandonne) return false; // Ne peut pas placer si abandonné

        List<Point> positionsPotentielles = new ArrayList<>();
        for (int i = 0; i < navire.getTaille(); i++) {
            int currentLigne = ligne;
            int currentCol = colonne;
            if (horizontal) {
                currentCol += i;
            } else {
                currentLigne += i;
            }

            if (currentLigne < 0 || currentLigne >= TAILLE_GRILLE || currentCol < 0 || currentCol >= TAILLE_GRILLE) {
                System.err.println("Placement navire " + navire.getType().getNom() + " hors grille pour " + nomJoueur);
                return false;
            }
            for (Ship existant : navires) {
                for (Point pExistant : existant.getPositions()) {
                    if (pExistant.x == currentLigne && pExistant.y == currentCol) {
                         System.err.println("Placement navire " + navire.getType().getNom() + " chevauche un autre navire pour " + nomJoueur);
                        return false; // Chevauchement
                    }
                }
            }
            if (grille[currentLigne][currentCol] != CASE_VIDE && grille[currentLigne][currentCol] != CASE_ABANDON) { // Vérifier si la case est vide sur la grille
               System.err.println("Placement navire " + navire.getType().getNom() + " sur case " + grille[currentLigne][currentCol] + " non vide pour " + nomJoueur);
               return false;
            }
            positionsPotentielles.add(new Point(currentLigne, currentCol));
        }

        navire.setPositions(positionsPotentielles);
        navire.setEstHorizontal(horizontal);
        navires.add(navire);
        for (Point p : positionsPotentielles) {
            grille[p.x][p.y] = CASE_NAVIRE;
        }
        System.out.println("Navire " + navire.getType().getNom() + " placé pour " + nomJoueur);
        return true;
    }

    public ShotResult recevoirTir(int ligne, int colonne) {
        if (aAbandonne) return ShotResult.ERREUR; // Ou un autre statut indiquant que le joueur n'est plus cible valide

        if (ligne < 0 || ligne >= TAILLE_GRILLE || colonne < 0 || colonne >= TAILLE_GRILLE) {
            return ShotResult.ERREUR;
        }

        char etatCase = grille[ligne][colonne];
        if (etatCase == CASE_NAVIRE_TOUCHE || etatCase == CASE_MANQUE || etatCase == CASE_ABANDON) {
            return ShotResult.DEJA_JOUE; // Ou ERREUR si ABANDON
        }

        if (etatCase == CASE_NAVIRE) {
            grille[ligne][colonne] = CASE_NAVIRE_TOUCHE;
            for (Ship navire : navires) {
                // Inutile de vérifier registerHit si le navire est déjà coulé
                if (!navire.estCoule() && navire.registerHit(new Point(ligne, colonne))) {
                    if (navire.estCoule()) {
                        System.out.println("Navire " + navire.getType().getNom() + " coulé pour " + nomJoueur);
                        // Marquer toutes les cases du navire coulé différemment ? (Pour l'affichage client)
                        return ShotResult.COULE;
                    }
                    return ShotResult.TOUCHE;
                }
            }
            // Si on arrive ici, c'est que le segment touché appartenait à un navire déjà marqué comme coulé
            // ou une incohérence. On considère TOUCHE car la case était 'N'.
            return ShotResult.TOUCHE;
        }

        if (etatCase == CASE_VIDE) {
            grille[ligne][colonne] = CASE_MANQUE;
            return ShotResult.MANQUE;
        }

        System.err.println("État de case inattendu lors du tir: " + etatCase + " en " + ligne + "," + colonne + " pour " + nomJoueur);
        return ShotResult.ERREUR;
    }

    public boolean tousNaviresCoules() {
        if (aAbandonne) return true; // Si abandonné, considéré comme tous navires coulés
        if (navires.isEmpty() && !typesDeNaviresAPlacerDansLeJeu().isEmpty()) {
            // Si la liste des navires à placer n'est pas vide (par ex. jeu solo où on n'a pas encore placé)
            // et que `navires` est vide, alors pas encore de navires à couler.
            // Cependant, dans le contexte du jeu serveur, les navires sont placés avant le combat.
            // Si `navires` est vide après la phase de placement, c'est une erreur de logique.
            // Pour être sûr, on vérifie si le jeu attendait des navires.
            // Normalement, si navires est vide APRES placement, cela signifie que tous sont placés.
            // Cette condition est surtout pour le cas où aucun navire n'a été placé.
            return false;
        }
        for (Ship navire : navires) {
            if (!navire.estCoule()) {
                return false;
            }
        }
        // Si la liste navires est vide ET que le jeu s'attendait à ce qu'on ait des navires,
        // on ne peut pas dire que tous sont coulés. Mais si tous les navires attendus ont été placés
        // et que la liste `navires` en contient, alors cette boucle est correcte.
        return !navires.isEmpty(); // Vrai si la liste n'est pas vide et que tous sont coulés.
                                  // Faux si la liste est vide (aucun navire placé = pas de défaite)
                                  // Cela pourrait être problématique si un joueur n'a aucun navire à placer.
                                  // On part du principe qu'il y a toujours des navires à placer.
    }

    // Juste pour la complétude, similairement à BatailleNavaleGame.typesDeNaviresAPlacer
    private List<Ship.ShipType> typesDeNaviresAPlacerDansLeJeu() {
        return Arrays.asList(
            Ship.ShipType.PORTE_AVIONS,
            Ship.ShipType.CROISEUR,
            Ship.ShipType.CONTRE_TORPILLEUR,
            Ship.ShipType.SOUS_MARIN,
            Ship.ShipType.TORPILLEUR
        );
    }

    public void marquerCommeAbandonne() {
        this.aAbandonne = true;
        // Marquer toutes les cases de la grille comme abandonnées pour l'affichage
        for (int i = 0; i < TAILLE_GRILLE; i++) {
            for (int j = 0; j < TAILLE_GRILLE; j++) {
                // Ne pas écraser les tirs déjà effectués (TOUCHÉ, MANQUÉ)
                // mais indiquer que le joueur n'est plus actif.
                // Pour l'instant, on met juste un flag. Le serveur gère l'élimination.
                // On pourrait changer l'état des cases non touchées.
                if (grille[i][j] == CASE_VIDE || grille[i][j] == CASE_NAVIRE) {
                    // grille[i][j] = CASE_ABANDON; // Optionnel: pour l'affichage
                }
            }
        }
        // Tous les navires sont considérés comme "coulés" ou hors-jeu
        for (Ship navire : navires) {
            navire.marquerCommeCouleSiAbandon(); // Nouvelle méthode dans Ship
        }
         System.out.println("Le joueur " + nomJoueur + " a abandonné. Ses navires sont considérés coulés.");
    }


    public void afficherGrilleDebug() {
        System.out.println("Grille de " + nomJoueur + (aAbandonne ? " (ABANDONNÉ)" : "") + ":");
        for (int i = 0; i < TAILLE_GRILLE; i++) {
            for (int j = 0; j < TAILLE_GRILLE; j++) {
                System.out.print(grille[i][j] + " ");
            }
            System.out.println();
        }
    }
}