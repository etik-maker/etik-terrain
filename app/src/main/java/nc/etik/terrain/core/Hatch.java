package nc.etik.terrain.core;

import java.util.ArrayList;
import java.util.List;

/** Lecture des contours de HATCH (polylignes et arêtes : lignes, arcs, ellipses, splines). */
final class Hatch {
    private Hatch() {}

    private static final class Cursor {
        final Ent e;
        int i;

        Cursor(Ent e, int i) {
            this.e = e;
            this.i = i;
        }

        boolean at(int code) {
            return i < e.n && e.codes[i] == code;
        }

        /** Avance jusqu'au prochain code donné (sans dépasser `limit` paires), retourne la valeur. */
        double d(int code) {
            int k = find(code);
            if (k < 0) throw new IllegalStateException("code " + code);
            i = k + 1;
            return DxfTokenizer.parseDouble(e.vals[k]);
        }

        int iv(int code) {
            return (int) d(code);
        }

        int find(int code) {
            for (int k = i; k < e.n && k < i + 8; k++) if (e.codes[k] == code) return k;
            return -1;
        }
    }

    static List<double[]> parseBoundaries(Ent e, DxfParser p) {
        List<double[]> rings = new ArrayList<>();
        int start = e.indexOf(91);
        if (start < 0) return rings;
        Cursor c = new Cursor(e, start);
        int nPaths = c.iv(91);
        try {
            for (int pi = 0; pi < nPaths; pi++) {
                int flags = c.iv(92);
                DxfParser.PointList pl = new DxfParser.PointList();
                if ((flags & 2) != 0) {
                    boolean hasBulge = c.iv(72) != 0;
                    c.iv(73);
                    int nv = c.iv(93);
                    List<double[]> verts = new ArrayList<>();
                    for (int k = 0; k < nv; k++) {
                        double x = c.d(10), y = c.d(20), b = 0;
                        if (hasBulge && c.at(42)) b = c.d(42);
                        verts.add(new double[]{x, y, b});
                    }
                    addPolyline(verts, pl, p);
                } else {
                    int ne = c.iv(93);
                    for (int k = 0; k < ne; k++) {
                        int type = c.iv(72);
                        switch (type) {
                            case 1: {
                                double x1 = c.d(10), y1 = c.d(20), x2 = c.d(11), y2 = c.d(21);
                                pl.add(x1, y1);
                                pl.add(x2, y2);
                                break;
                            }
                            case 2: {
                                double cx = c.d(10), cy = c.d(20), r = c.d(40);
                                double s = c.d(50), en = c.d(51);
                                boolean ccw = c.iv(73) != 0;
                                arc(pl, cx, cy, r, r, 0, s, en, ccw, p);
                                break;
                            }
                            case 3: {
                                double cx = c.d(10), cy = c.d(20), mx = c.d(11), my = c.d(21);
                                double ratio = c.d(40), s = c.d(50), en = c.d(51);
                                boolean ccw = c.iv(73) != 0;
                                double R = Math.sqrt(mx * mx + my * my);
                                arc(pl, cx, cy, R, R * ratio, Math.atan2(my, mx), s, en, ccw, p);
                                break;
                            }
                            case 4: {
                                int degree = c.iv(94);
                                boolean rational = c.iv(73) != 0;
                                c.iv(74);
                                int nk = c.iv(95), nc = c.iv(96);
                                List<Double> knots = new ArrayList<>();
                                for (int j = 0; j < nk; j++) knots.add(c.d(40));
                                List<double[]> ctrl = new ArrayList<>();
                                List<Double> w = new ArrayList<>();
                                for (int j = 0; j < nc; j++) {
                                    ctrl.add(new double[]{c.d(10), c.d(20)});
                                    if (rational && c.at(42)) w.add(c.d(42));
                                }
                                int nfit = 0;
                                if (c.at(97)) nfit = c.iv(97);
                                for (int j = 0; j < nfit; j++) { c.d(11); c.d(21); }
                                if (nfit > 0) {
                                    if (c.at(12)) { c.d(12); c.d(22); }
                                    if (c.at(13)) { c.d(13); c.d(23); }
                                }
                                if (ctrl.size() > degree && knots.size() == ctrl.size() + degree + 1)
                                    Spline.sample(ctrl, knots, w, degree, pl);
                                else for (double[] q : ctrl) pl.add(q[0], q[1]);
                                break;
                            }
                            default:
                                throw new IllegalStateException("arête " + type);
                        }
                    }
                }
                // objets sources
                if (c.at(97)) {
                    int ns = c.iv(97);
                    for (int k = 0; k < ns; k++) if (c.at(330)) c.i++;
                }
                if (pl.n >= 3) rings.add(pl.toArray());
            }
        } catch (RuntimeException ex) {
            // contour illisible : on garde ce qui a été lu
        }
        return rings;
    }

    private static void addPolyline(List<double[]> verts, DxfParser.PointList pl, DxfParser p) {
        int n = verts.size();
        for (int k = 0; k < n; k++) {
            double[] a = verts.get(k), b = verts.get((k + 1) % n);
            pl.add(a[0], a[1]);
            if (Math.abs(a[2]) > 1e-9) {
                double dx = b[0] - a[0], dy = b[1] - a[1];
                double chord = Math.sqrt(dx * dx + dy * dy);
                if (chord < 1e-12) continue;
                double theta = 4 * Math.atan(a[2]);
                double s = a[2] * chord / 2;
                double d = (chord * chord / 4 - s * s) / (2 * s);
                double cx = (a[0] + b[0]) / 2 - dy / chord * d, cy = (a[1] + b[1]) / 2 + dx / chord * d;
                double r = (chord * chord / 4 + s * s) / (2 * Math.abs(s));
                double a1 = Math.atan2(a[1] - cy, a[0] - cx);
                int ns = p.arcSegments(r, Math.abs(theta));
                for (int i = 1; i < ns; i++) {
                    double t = a1 + theta * i / ns;
                    pl.add(cx + r * Math.cos(t), cy + r * Math.sin(t));
                }
            }
        }
    }

    /** Arc d'arête de hachure. Angles en degrés ; sens horaire : angles complémentaires. */
    private static void arc(DxfParser.PointList pl, double cx, double cy, double rx, double ry, double rot,
                            double s, double e, boolean ccw, DxfParser p) {
        double a1 = Math.toRadians(s), a2 = Math.toRadians(e);
        double sweep = a2 - a1;
        while (sweep <= 0) sweep += 2 * Math.PI;
        if (Math.abs(e - s) >= 360) sweep = 2 * Math.PI;
        int n = Math.max(p.arcSegments(Math.max(rx, ry), sweep), 4);
        double cr = Math.cos(rot), sr = Math.sin(rot);
        for (int i = 0; i <= n; i++) {
            double t = a1 + sweep * i / n;
            if (!ccw) t = -t;
            double x = rx * Math.cos(t), y = ry * Math.sin(t);
            pl.add(cx + cr * x - sr * y, cy + sr * x + cr * y);
        }
    }
}
