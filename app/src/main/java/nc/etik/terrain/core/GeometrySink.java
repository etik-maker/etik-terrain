package nc.etik.terrain.core;

/**
 * Reçoit la géométrie aplatie du plan, en coordonnées du dessin (Lambert NC).
 * Couleur : 0xFFRRGGBB, ou {@link #COLOR_FG} pour la couleur 7 d'AutoCAD
 * (blanc sur fond sombre, noir sur fond clair).
 */
public interface GeometrySink {
    int COLOR_FG = 0;

    void polyline(int layer, int color, double[] xy, int count, boolean closed);

    /** Surface pleine (hachure, solide). rings : anneaux x,y,x,y,... ; solid : remplissage plein ou motif. */
    void fill(int layer, int color, boolean solid, double[][] rings);

    /**
     * Texte. (x, y) point d'ancrage ; h hauteur ; rot rotation en radians ;
     * halign 0 gauche 1 centre 2 droite ; valign 0 ligne de base 1 bas 2 milieu 3 haut.
     */
    void text(int layer, int color, double x, double y, double h, double rot, int halign, int valign, String s);
}
