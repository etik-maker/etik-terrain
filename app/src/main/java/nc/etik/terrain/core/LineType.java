package nc.etik.terrain.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Type de ligne AutoCAD (table LTYPE) : tirets, espaces, points, et éléments
 * complexes (texte comme « AEP », symboles de ltypeshp.shx comme les cercles).
 */
final class LineType {
    final String name;
    String description = "";
    final List<Elem> elems = new ArrayList<>();
    double total;

    static final class Elem {
        double len;          // > 0 tiret, < 0 espace, 0 point
        int kind;            // 0 simple, 2 texte, 4 symbole
        boolean absRot;
        int shape;
        String text;
        double scale = 1, rot, xoff, yoff;
    }

    LineType(String name) {
        this.name = name;
    }

    boolean isContinuous() {
        if (elems.isEmpty()) return true;
        for (Elem e : elems) if (e.len < 0 || e.kind != 0) return false;
        return true;
    }

    static LineType parse(Ent e) {
        LineType lt = new LineType(e.s(2, ""));
        lt.description = e.s(3, "");
        Elem cur = null;
        for (int k = 0; k < e.n; k++) {
            int c = e.codes[k];
            String v = e.vals[k];
            switch (c) {
                case 40: lt.total = DxfTokenizer.parseDouble(v); break;
                case 49:
                    cur = new Elem();
                    cur.len = DxfTokenizer.parseDouble(v);
                    lt.elems.add(cur);
                    break;
                case 74:
                    if (cur != null) {
                        int f = DxfTokenizer.parseInt(v);
                        cur.absRot = (f & 1) != 0;
                        cur.kind = (f & 2) != 0 ? 2 : (f & 4) != 0 ? 4 : 0;
                    }
                    break;
                case 75: if (cur != null) cur.shape = DxfTokenizer.parseInt(v); break;
                case 46: if (cur != null) cur.scale = DxfTokenizer.parseDouble(v); break;
                case 50: if (cur != null) cur.rot = Math.toRadians(DxfTokenizer.parseDouble(v)); break;
                case 44: if (cur != null) cur.xoff = DxfTokenizer.parseDouble(v); break;
                case 45: if (cur != null) cur.yoff = DxfTokenizer.parseDouble(v); break;
                case 9: if (cur != null) cur.text = v; break;
                default:
            }
        }
        if (lt.total <= 0) for (Elem el : lt.elems) lt.total += Math.abs(el.len);
        return lt;
    }

    /** Sortie des tirets et des éléments complexes. */
    interface Out {
        void dash(double[] xy, int n);

        void text(double x, double y, double h, double rot, String s);

        void shape(double[] xy, int n, boolean closed);
    }

    /**
     * Applique le motif le long d'une polyligne (coordonnées du dessin).
     * Le motif est continu sur toute la polyligne (comme PLINEGEN), et une
     * polyligne plus courte qu'un motif est tracée en continu, comme AutoCAD.
     */
    void apply(double[] xy, int n, boolean closed, double scale, Out out) {
        int pts = closed ? n + 1 : n;
        double[] cum = new double[pts];
        for (int i = 1; i < pts; i++) {
            int a = i - 1, b = i % n;
            cum[i] = cum[i - 1] + Math.hypot(xy[2 * b] - xy[2 * a], xy[2 * b + 1] - xy[2 * a + 1]);
        }
        double length = cum[pts - 1];
        double period = total * scale;
        if (period <= 0 || length < period || length / period > 200000) {
            out.dash(closedCopy(xy, n, closed), pts);
            return;
        }
        double d = 0;
        int seg = 1;
        double dot = Math.max(period * 0.01, 0.02);
        while (d < length) {
            for (Elem el : elems) {
                double l = el.len * scale;
                double start = d, end = d + Math.abs(l);
                if (el.len > 0 || el.len == 0) {
                    double e2 = el.len == 0 ? Math.min(d + dot, length) : Math.min(end, length);
                    emitDash(xy, n, cum, pts, start, e2, out);
                }
                d = end;
                if (el.kind != 0 && d <= length) placeComplex(el, xy, n, cum, pts, d, scale, out);
                if (d >= length) break;
            }
            if (++seg > 1_000_000) break;
        }
    }

    private static double[] closedCopy(double[] xy, int n, boolean closed) {
        if (!closed) return xy;
        double[] c = new double[2 * n + 2];
        System.arraycopy(xy, 0, c, 0, 2 * n);
        c[2 * n] = xy[0];
        c[2 * n + 1] = xy[1];
        return c;
    }

    /** Index du segment contenant la distance d. */
    private static int segAt(double[] cum, int pts, double d) {
        int lo = 1, hi = pts - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] < d) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static void pointAt(double[] xy, int n, double[] cum, int pts, double d, double[] p) {
        int i = segAt(cum, pts, d);
        int a = i - 1, b = i % n;
        double sl = cum[i] - cum[i - 1];
        double t = sl <= 0 ? 0 : (d - cum[i - 1]) / sl;
        double ax = xy[2 * a], ay = xy[2 * a + 1], bx = xy[2 * b], by = xy[2 * b + 1];
        p[0] = ax + (bx - ax) * t;
        p[1] = ay + (by - ay) * t;
        p[2] = Math.atan2(by - ay, bx - ax);
    }

    private static void emitDash(double[] xy, int n, double[] cum, int pts, double s, double e, Out out) {
        if (e <= s) {
            if (e == s) { // point
                double[] p = new double[3];
                pointAt(xy, n, cum, pts, s, p);
                out.dash(new double[]{p[0], p[1], p[0] + 1e-3 * Math.cos(p[2]), p[1] + 1e-3 * Math.sin(p[2])}, 2);
            }
            return;
        }
        double[] p = new double[3];
        int i0 = segAt(cum, pts, s), i1 = segAt(cum, pts, e);
        double[] r = new double[2 * (i1 - i0 + 2)];
        int k = 0;
        pointAt(xy, n, cum, pts, s, p);
        r[k++] = p[0];
        r[k++] = p[1];
        for (int i = i0; i < i1; i++) {
            int b = i % n;
            r[k++] = xy[2 * b];
            r[k++] = xy[2 * b + 1];
        }
        pointAt(xy, n, cum, pts, e, p);
        r[k++] = p[0];
        r[k++] = p[1];
        out.dash(r, k / 2);
    }

    private void placeComplex(Elem el, double[] xy, int n, double[] cum, int pts, double d, double scale, Out out) {
        double[] p = new double[3];
        pointAt(xy, n, cum, pts, Math.min(d, cum[pts - 1]), p);
        double ang = p[2];
        double ca = Math.cos(ang), sa = Math.sin(ang);
        double x = p[0] + (el.xoff * ca - el.yoff * sa) * scale;
        double y = p[1] + (el.xoff * sa + el.yoff * ca) * scale;
        double rot = el.absRot ? el.rot : ang + el.rot;
        double s = el.scale * scale;
        if (el.kind == 2) {
            if (el.text != null && !el.text.isEmpty()) out.text(x, y, s, rot, TextUtil.decodeText(el.text));
            return;
        }
        // Symboles de ltypeshp.shx (approximation géométrique)
        double cr = Math.cos(rot), sr = Math.sin(rot);
        double[][] shapes = shapeGeometry(el.shape);
        if (shapes == null) return;
        for (double[] sh : shapes) {
            boolean closed = sh[0] == 1;
            int m = (sh.length - 1) / 2;
            double[] r = new double[2 * m];
            for (int i = 0; i < m; i++) {
                double u = sh[1 + 2 * i] * s, v = sh[2 + 2 * i] * s;
                r[2 * i] = x + u * cr - v * sr;
                r[2 * i + 1] = y + u * sr + v * cr;
            }
            out.shape(r, m, closed);
        }
    }

    /** Géométrie unitaire des symboles courants (1er nombre : 1 = fermé). */
    private static double[][] shapeGeometry(int shape) {
        switch (shape) {
            case 130: // TRACK1 : traverse
                return new double[][]{{0, 0, -0.5, 0, 0.5}};
            case 131: // ZIG
                return new double[][]{{0, 0, 0, 0.5, 0.5, 1, 0}};
            case 132: // BOX
                return new double[][]{{1, 0, -0.5, 1, -0.5, 1, 0.5, 0, 0.5}};
            case 133: { // CIRC1 : cercle de diamètre 2 commençant au point d'insertion
                int m = 24;
                double[] c = new double[1 + 2 * m];
                c[0] = 1;
                for (int i = 0; i < m; i++) {
                    double t = 2 * Math.PI * i / m;
                    c[1 + 2 * i] = 1 - Math.cos(t);
                    c[2 + 2 * i] = Math.sin(t);
                }
                return new double[][]{c};
            }
            case 134: // BAT : isolant
                return new double[][]{{0, 0, 0, 0.5, 0.5, 1, 0, 1.5, 0.5, 2, 0}};
            default:
                return null;
        }
    }
}
