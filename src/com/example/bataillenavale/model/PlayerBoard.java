package com.example.bataillenavale.model; // Exemple de package

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public class PlayerBoard {
    public static final int TAILLE_GRILLE = 10; // Grille standard de 10x10

    // États des cases pour la grille de ce joueur (vue par lui-même)
    public static final char CASE_VIDE = ' '; // Eau non touchée
    public static final char CASE_NAVIRE = 'N'; // Segment de navire non touché
    public static final char CASE_NAVIRE_TOUCHE = 'X'; // Segment de navire touché
    public static final char CASE_MANQUE = 'O'; // Tir manqué dans l'eau

    private final char[][] grille; // La grille de ce joueur avec ses navires et les tirs reçus
    private final List<Ship> navires;
    private final String nomJoueur;

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
        return navires;
    }

    /**
     * Tente de placer un navire sur la grille.
     * @param navire Le navire à placer.
     * @param ligne La ligne de départ (du segment le plus en haut ou à gauche).
     * @param colonne La colonne de départ.
     * @param horizontal True si le navire est placé horizontalement, false si verticalement.
     * @return True si le placement est réussi, false sinon (hors grille, chevauchement).
     */
    public boolean placerNavire(Ship navire, int ligne, int colonne, boolean horizontal) {
        List<Point> positionsPotentielles = new ArrayList<>();
        for (int i = 0; i < navire.getTaille(); i++) {
            int currentLigne = ligne;
            int currentCol = colonne;
            if (horizontal) {
                currentCol += i;
            } else {
                currentLigne += i;
            }

            // Vérifier si hors grille
            if (currentLigne < 0 || currentLigne >= TAILLE_GRILLE || currentCol < 0 || currentCol >= TAILLE_GRILLE) {
                System.err.println("Placement navire " + navire.getType().getNom() + " hors grille.");
                return false; 
            }
            // Vérifier si la case est déjà occupée par un autre navire
            for (Ship existant : navires) {
                for (Point pExistant : existant.getPositions()) {
                    if (pExistant.x == currentLigne && pExistant.y == currentCol) {
                         System.err.println("Placement navire " + navire.getType().getNom() + " chevauche un autre navire.");
                        return false; // Chevauchement
                    }
                }
            }
            // Vérifier si la case est déjà occupée sur la grille (au cas où la grille a une info que la liste des navires n'a pas)
            // Normalement, la vérification précédente suffit si la grille est synchronisée avec la liste des navires.
            // if (grille[currentLigne][currentCol] == CASE_NAVIRE) {
            //    System.err.println("Placement navire " + navire.getType().getNom() + " sur case déjà marquée comme navire.");
            //    return false;
            // }
            positionsPotentielles.add(new Point(currentLigne, currentCol));
        }

        // Si toutes les vérifications sont passées, placer le navire
        navire.setPositions(positionsPotentielles);
        navire.setEstHorizontal(horizontal);
        navires.add(navire);
        for (Point p : positionsPotentielles) {
            grille[p.x][p.y] = CASE_NAVIRE;
        }
        System.out.println("Navire " + navire.getType().getNom() + " placé pour " + nomJoueur);
        return true;
    }

    /**
     * Enregistre un tir sur cette grille.
     * @param ligne La ligne du tir.
     * @param colonne La colonne du tir.
     * @return Le résultat du tir (MANQUE, TOUCHE, COULE, DEJA_JOUE).
     */
    public ShotResult recevoirTir(int ligne, int colonne) {
        if (ligne < 0 || ligne >= TAILLE_GRILLE || colonne < 0 || colonne >= TAILLE_GRILLE) {
            return ShotResult.ERREUR; // Hors grille
        }

        char etatCase = grille[ligne][colonne];
        if (etatCase == CASE_NAVIRE_TOUCHE || etatCase == CASE_MANQUE) {
            return ShotResult.DEJA_JOUE;
        }

        if (etatCase == CASE_NAVIRE) {
            grille[ligne][colonne] = CASE_NAVIRE_TOUCHE;
            for (Ship navire : navires) {
                for (Point segment : navire.getPositions()) {
                    if (segment.x == ligne && segment.y == colonne) {
                        navire.registerHit(new Point(ligne, colonne));
                        if (navire.estCoule()) {
                            System.out.println("Navire " + navire.getType().getNom() + " coulé pour " + nomJoueur);
                            return ShotResult.COULE;
                        }
                        return ShotResult.TOUCHE;
                    }
                }
            }
        }

        if (etatCase == CASE_VIDE) {
            grille[ligne][colonne] = CASE_MANQUE;
            return ShotResult.MANQUE;
        }
        
        // Ne devrait pas arriver si la logique est correcte
        System.err.println("État de case inattendu lors du tir: " + etatCase + " en " + ligne + "," + colonne);
        return ShotResult.ERREUR;
    }

    public boolean tousNaviresCoules() {
        if (navires.isEmpty()) return false; // Pas de navires, pas de défaite (ou victoire si l'autre n'a plus rien)
        for (Ship navire : navires) {
            if (!navire.estCoule()) {
                return false;
            }
        }
        return true;
    }

    // Pour l'affichage de la grille du joueur (ses propres navires)
    public void afficherGrilleDebug() {
        System.out.println("Grille de " + nomJoueur + ":");
        for (int i = 0; i < TAILLE_GRILLE; i++) {
            for (int j = 0; j < TAILLE_GRILLE; j++) {
                System.out.print(grille[i][j] + " ");
            }
            System.out.println();
        }
    }
}
