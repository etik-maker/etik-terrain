package nc.etik.terrain.core;

import java.util.HashSet;
import java.util.Set;

/**
 * Liste des tuiles de fond de carte (schéma XYZ Web Mercator) couvrant une
 * bande de largeur donnée autour des objets visibles d'un plan.
 */
public final class TileCoverage {
    private TileCoverage() {}

    private static final double HALF = Math.PI * LambertNC.MERC_R;

    public static long key(int z, int x, int y) {
        return ((long) z << 40) | ((long) x << 20) | y;
    }

    public static int z(long k) { return (int) (k >>> 40); }
    public static int x(long k) { return (int) ((k >>> 20) & 0xFFFFF); }
    public static int y(long k) { return (int) (k & 0xFFFFF); }

    /**
     * @param bufferM largeur de la bande autour des objets, en mètres sur le terrain
     * @param zLow    niveaux zLow..zBand-1 : emprise rectangulaire du plan (peu de tuiles)
     * @param zBand   premier niveau où l'on ne garde que la bande
     * @param zMax    niveau maximal
     */
    public static Set<Long> compute(PlanData p, double bufferM, int zLow, int zBand, int zMax) {
        double res = 20; // résolution de la grille d'occupation (m Web Mercator)
        double lat = LambertNC.mercYToLat(p.originMy + (p.minY + p.maxY) / 2);
        double k = 1 / Math.cos(Math.toRadians(lat));
        double buf = bufferM * k;
        double x0 = p.minX - buf - res, y0 = p.minY - buf - res;
        int nx = (int) Math.ceil((p.maxX - p.minX + 2 * buf + 2 * res) / res) + 1;
        int ny = (int) Math.ceil((p.maxY - p.minY + 2 * buf + 2 * res) / res) + 1;
        boolean[] occ = new boolean[nx * ny];
        double vx0 = Double.MAX_VALUE, vy0 = Double.MAX_VALUE, vx1 = -Double.MAX_VALUE, vy1 = -Double.MAX_VALUE;

        // 1. occupation par les objets visibles
        for (PlanData.Cell c : p.cells) {
            if (c == null) continue;
            if (c.chunks != null) for (PlanData.Chunk ch : c.chunks) {
                if (!p.layers.get(ch.layer).visible) continue;
                float[] s = ch.seg;
                for (int i = 0; i < s.length; i += 4) {
                    double ax = s[i], ay = s[i + 1], bx = s[i + 2], by = s[i + 3];
                    double len = Math.hypot(bx - ax, by - ay);
                    int n = Math.max(1, (int) Math.ceil(len / (res / 2)));
                    for (int q = 0; q <= n; q++) {
                        double x = ax + (bx - ax) * q / n, y = ay + (by - ay) * q / n;
                        if (mark(occ, nx, ny, x0, y0, res, x, y)) {
                            vx0 = Math.min(vx0, x); vx1 = Math.max(vx1, x);
                            vy0 = Math.min(vy0, y); vy1 = Math.max(vy1, y);
                        }
                    }
                }
            }
            if (c.texts != null) for (PlanData.Text t : c.texts) {
                if (!p.layers.get(t.layer).visible) continue;
                mark(occ, nx, ny, x0, y0, res, t.x, t.y);
            }
        }
        for (PlanData.Poly q : p.polys) {
            if (!p.layers.get(q.layer).visible) continue;
            for (float[] r : q.rings) {
                for (int i = 0; i < r.length; i += 2) {
                    int j = (i + 2) % r.length;
                    double ax = r[i], ay = r[i + 1], bx = r[j], by = r[j + 1];
                    int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / (res / 2)));
                    for (int s = 0; s <= n; s++) mark(occ, nx, ny, x0, y0, res, ax + (bx - ax) * s / n, ay + (by - ay) * s / n);
                }
            }
        }
        Set<Long> tiles = new HashSet<>();
        if (vx0 > vx1) return tiles;

        // 2. dilatation de la largeur de la bande (disque)
        int rad = (int) Math.ceil(buf / res);
        boolean[] dil = new boolean[nx * ny];
        int[] span = new int[2 * rad + 1];
        for (int d = -rad; d <= rad; d++) span[d + rad] = (int) Math.floor(Math.sqrt((double) rad * rad - (double) d * d));
        // passe horizontale avec largeur variable par ligne : on dilate ligne par ligne
        for (int cy = 0; cy < ny; cy++) {
            for (int cx = 0; cx < nx; cx++) {
                if (!occ[cy * nx + cx]) continue;
                for (int d = -rad; d <= rad; d++) {
                    int yy = cy + d;
                    if (yy < 0 || yy >= ny) continue;
                    int w = span[d + rad];
                    int xa = Math.max(0, cx - w), xb = Math.min(nx - 1, cx + w);
                    int row = yy * nx;
                    for (int xx = xa; xx <= xb; xx++) dil[row + xx] = true;
                }
            }
        }

        // 3. niveaux bas : emprise rectangulaire
        double bx0 = p.originMx + vx0 - buf, bx1 = p.originMx + vx1 + buf;
        double by0 = p.originMy + vy0 - buf, by1 = p.originMy + vy1 + buf;
        for (int z = zLow; z < Math.min(zBand, zMax + 1); z++) addRange(tiles, z, bx0, by0, bx1, by1);

        // 4. niveaux hauts : seulement les tuiles touchant la bande
        for (int z = zBand; z <= zMax; z++) {
            for (int cy = 0; cy < ny; cy++) {
                for (int cx = 0; cx < nx; cx++) {
                    if (!dil[cy * nx + cx]) continue;
                    double mx = p.originMx + x0 + cx * res, my = p.originMy + y0 + cy * res;
                    addRange(tiles, z, mx, my, mx + res, my + res);
                }
            }
        }
        return tiles;
    }

    private static boolean mark(boolean[] occ, int nx, int ny, double x0, double y0, double res, double x, double y) {
        int cx = (int) Math.floor((x - x0) / res), cy = (int) Math.floor((y - y0) / res);
        if (cx < 0 || cy < 0 || cx >= nx || cy >= ny) return false;
        occ[cy * nx + cx] = true;
        return true;
    }

    private static void addRange(Set<Long> tiles, int z, double mx0, double my0, double mx1, double my1) {
        double t = 2 * HALF / (1 << z);
        int n = 1 << z;
        int tx0 = clamp((int) Math.floor((mx0 + HALF) / t), n), tx1 = clamp((int) Math.floor((mx1 + HALF) / t), n);
        int ty0 = clamp((int) Math.floor((HALF - my1) / t), n), ty1 = clamp((int) Math.floor((HALF - my0) / t), n);
        for (int x = tx0; x <= tx1; x++) for (int y = ty0; y <= ty1; y++) tiles.add(key(z, x, y));
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /** Nombre de tuiles par niveau (pour l'affichage). */
    public static int[] perZoom(Set<Long> tiles, int zMax) {
        int[] c = new int[zMax + 1];
        for (long k : tiles) if (z(k) <= zMax) c[z(k)]++;
        return c;
    }
}
