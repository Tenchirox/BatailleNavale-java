package com.example.bataillenavale.model; // Exemple de package

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Ship {
    public enum ShipType {
        PORTE_AVIONS("Porte-avions", 5),
        CROISEUR("Croiseur", 4),
        CONTRE_TORPILLEUR("Contre-torpilleur", 3),
        SOUS_MARIN("Sous-marin", 3),
        TORPILLEUR("Torpilleur", 2);

        private final String nom;
        private final int taille;

        ShipType(String nom, int taille) {
            this.nom = nom;
            this.taille = taille;
        }

        public String getNom() {
            return nom;
        }

        public int getTaille() {
            return taille;
        }
    }

    private final ShipType type;
    private final List<Point> positions;
    private final boolean[] hits;
    private boolean estHorizontal;
    private boolean explicitementCoule = false; // Pour le cas d'abandon

    public Ship(ShipType type) {
        this.type = type;
        this.positions = new ArrayList<>();
        this.hits = new boolean[type.getTaille()]; // Initialisé à false par défaut
    }

    public ShipType getType() {
        return type;
    }

    public int getTaille() {
        return type.getTaille();
    }

    public List<Point> getPositions() {
        return new ArrayList<>(positions); // Retourner une copie
    }

    public void addPosition(int x, int y) { // Utilisé pendant la construction initiale, pas par le jeu principal
        this.positions.add(new Point(x, y));
    }

    public void setPositions(List<Point> positions) {
        this.positions.clear();
        this.positions.addAll(positions);
        // S'assurer que `hits` est réinitialisé si les positions changent après l'initialisation
        // (normalement, les positions sont fixées une fois pour toutes).
        // Arrays.fill(this.hits, false); // Déjà fait par la taille fixe dans le constructeur.
    }

    public boolean isEstHorizontal() {
        return estHorizontal;
    }

    public void setEstHorizontal(boolean estHorizontal) {
        this.estHorizontal = estHorizontal;
    }

    public boolean registerHit(Point shotPosition) {
        if (explicitementCoule) return false; // Si déjà marqué comme coulé par abandon, ne pas enregistrer de nouveaux tirs

        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).equals(shotPosition)) {
                if (!hits[i]) {
                    hits[i] = true;
                    return true; // Touché pour la première fois à cet endroit
                }
                return false; // Déjà touché à cet endroit
            }
        }
        return false; // Le tir n'est pas sur ce navire (ne devrait pas arriver si la logique de PlayerBoard est correcte)
    }

    public boolean estCoule() {
        if (explicitementCoule) return true;
        if (positions.isEmpty() && type.getTaille() > 0) return false; // Pas de positions, pas encore placé, donc pas coulé.
        if (type.getTaille() == 0) return true; // Navire de taille 0 est toujours "coulé".

        for (boolean hit : hits) {
            if (!hit) {
                return false; // Au moins un segment n'est pas touché
            }
        }
        // Si tous les segments sont dans `hits` et sont `true`, et qu'il y a bien des positions
        return !positions.isEmpty(); // Vrai seulement si tous les segments sont touchés ET le navire a été positionné.
    }

    public void marquerCommeCouleSiAbandon() {
        this.explicitementCoule = true;
        // Optionnellement, marquer tous les segments comme touchés si la logique externe en dépend.
        // for (int i = 0; i < hits.length; i++) {
        //     hits[i] = true;
        // }
    }


    public int getNombreTouchees() {
        if (explicitementCoule) return type.getTaille();
        int count = 0;
        for (boolean hit : hits) {
            if (hit) count++;
        }
        return count;
    }

    @Override
    public String toString() {
        return type.getNom() + " (taille " + type.getTaille() + ", positions: " + positions.size() + ", touches: " + getNombreTouchees() + (estCoule() ? " COULÉ" : "") + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Ship ship = (Ship) o;
        // Comparaison basée sur le type et les positions initiales peut suffire si les navires sont uniques par type sur un plateau.
        // Si plusieurs navires du même type sont possibles, un ID unique serait nécessaire.
        return type == ship.type && Objects.equals(positions, ship.positions) && estHorizontal == ship.estHorizontal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, positions, estHorizontal);
    }
}