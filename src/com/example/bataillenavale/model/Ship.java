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
    private final List<Point> positions; // Coordonnées de chaque segment du navire
    private final boolean[] hits; // Indique si un segment du navire est touché
    private boolean estHorizontal;

    public Ship(ShipType type) {
        this.type = type;
        this.positions = new ArrayList<>();
        this.hits = new boolean[type.getTaille()];
    }

    public ShipType getType() {
        return type;
    }

    public int getTaille() {
        return type.getTaille();
    }

    public List<Point> getPositions() {
        return positions;
    }

    public void addPosition(int x, int y) {
        this.positions.add(new Point(x, y));
    }
    
    public void setPositions(List<Point> positions) {
        this.positions.clear();
        this.positions.addAll(positions);
    }

    public boolean isEstHorizontal() {
        return estHorizontal;
    }

    public void setEstHorizontal(boolean estHorizontal) {
        this.estHorizontal = estHorizontal;
    }

    /**
     * Enregistre un tir sur le navire.
     * @param shotPosition La position du tir.
     * @return true si le tir a touché une nouvelle partie du navire, false sinon (déjà touché ou manqué).
     */
    public boolean registerHit(Point shotPosition) {
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).equals(shotPosition)) {
                if (!hits[i]) { // Si cette partie n'était pas encore touchée
                    hits[i] = true;
                    return true;
                }
                return false; // Déjà touché à cet endroit
            }
        }
        return false; // Le tir n'est pas sur ce navire (ne devrait pas arriver si la logique est correcte)
    }

    public boolean estCoule() {
        for (boolean hit : hits) {
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    public int getNombreTouchees() {
        int count = 0;
        for (boolean hit : hits) {
            if (hit) count++;
        }
        return count;
    }

    @Override
    public String toString() {
        return type.getNom() + " (taille " + type.getTaille() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Ship ship = (Ship) o;
        return type == ship.type && Objects.equals(positions, ship.positions); // Simplifié, pourrait nécessiter une comparaison plus profonde
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, positions);
    }
}
