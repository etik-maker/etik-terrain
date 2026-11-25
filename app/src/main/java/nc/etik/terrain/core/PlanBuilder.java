package nc.etik.terrain.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Construit un {@link PlanData} à partir de la géométrie émise par le lecteur DXF. */
public final class PlanBuilder implements GeometrySink {

    private static final class FloatBuf {
        float[] a = new float[256];
        int n;

        void add(float v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
    }

    private boolean originSet;
    private double ox, oy, omx, omy;
    private final double[] tmp = new double[2];
    private final Map<StyleKey, FloatBuf> segs = new HashMap<>();

    /** Style d'un lot de segments : calque, couleur, largeur, épaisseur. */
    static final class StyleKey {
        final int layer, color;
        final float width, lw;

        StyleKey(int layer, int color, float width, float lw) {
            this.layer = layer; this.color = color; this.width = width; this.lw = lw;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof StyleKey)) return false;
            StyleKey k = (StyleKey) o;
            return k.layer == layer && k.color == color && k.width == width && k.lw == lw;
        }

        @Override
        public int hashCode() {
            return ((layer * 31 + color) * 31 + Float.floatToIntBits(width)) * 31 + Float.floatToIntBits(lw);
        }
    }
    private final List<PlanData.Text> texts = new ArrayList<>();
    private final List<PlanData.Poly> polys = new ArrayList<>();
    private final Map<Integer, Integer> layerCounts = new HashMap<>();

    /** Origine en Lambert NC (centre de l'emprise du dessin de préférence). */
    public void setOrigin(double x, double y) {
        ox = x;
        oy = y;
        LambertNC.toMercator(x, y, tmp);
        omx = tmp[0];
        omy = tmp[1];
        originSet = true;
    }

    private void local(double x, double y, float[] out, int idx) {
        if (!originSet) setOrigin(x, y);
        LambertNC.toMercator(x, y, tmp);
        out[idx] = (float) (tmp[0] - omx);
        out[idx + 1] = (float) (tmp[1] - omy);
    }

    private double mercK = 0;

    private double mercScale() {
        if (mercK == 0) {
            double[] o = new double[2];
            LambertNC.toLonLat(ox, oy, o);
            mercK = 1 / Math.cos(Math.toRadians(o[1]));
        }
        return mercK;
    }

    private static boolean valid(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) < 1e8;
    }

    private void countLayer(int layer) {
        Integer c = layerCounts.get(layer);
        layerCounts.put(layer, c == null ? 1 : c + 1);
    }

    @Override
    public void polyline(int layer, int color, double[] xy, int count, boolean closed, float width, float lw) {
        // largeur convertie en mètres Web Mercator (facteur d'échelle local ~ 1/cos(lat))
        float wm = width > 0 ? (float) (width * mercScale()) : 0f;
        StyleKey key = new StyleKey(layer, color, wm, lw);
        FloatBuf b = segs.get(key);
        if (b == null) segs.put(key, b = new FloatBuf());
        float[] p = new float[2], q = new float[2];
        int total = closed ? count : count - 1;
        boolean any = false;
        for (int k = 0; k < total; k++) {
            int j = (k + 1) % count;
            double x1 = xy[2 * k], y1 = xy[2 * k + 1], x2 = xy[2 * j], y2 = xy[2 * j + 1];
            if (!valid(x1) || !valid(y1) || !valid(x2) || !valid(y2)) continue;
            if (x1 == x2 && y1 == y2) continue;
            local(x1, y1, p, 0);
            local(x2, y2, q, 0);
            b.add(p[0]); b.add(p[1]); b.add(q[0]); b.add(q[1]);
            any = true;
        }
        if (any) countLayer(layer);
    }

    @Override
    public void fill(int layer, int color, boolean solid, double[][] rings) {
        PlanData.Poly poly = new PlanData.Poly();
        poly.layer = layer;
        poly.color = color;
        poly.solid = solid;
        poly.minx = poly.miny = Float.MAX_VALUE;
        poly.maxx = poly.maxy = -Float.MAX_VALUE;
        List<float[]> rs = new ArrayList<>();
        for (double[] r : rings) {
            int n = r.length / 2;
            if (n < 3) continue;
            float[] f = new float[2 * n];
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                if (!valid(r[2 * k]) || !valid(r[2 * k + 1])) { ok = false; break; }
                local(r[2 * k], r[2 * k + 1], f, 2 * k);
                poly.minx = Math.min(poly.minx, f[2 * k]);
                poly.maxx = Math.max(poly.maxx, f[2 * k]);
                poly.miny = Math.min(poly.miny, f[2 * k + 1]);
                poly.maxy = Math.max(poly.maxy, f[2 * k + 1]);
            }
            if (ok) rs.add(f);
        }
        if (rs.isEmpty()) return;
        poly.rings = rs.toArray(new float[0][]);
        poly.size = (float) Math.hypot(poly.maxx - poly.minx, poly.maxy - poly.miny);
        polys.add(poly);
        countLayer(layer);
    }

    @Override
    public void text(int layer, int color, double x, double y, double h, double rot, int halign, int valign, String s) {
        if (!valid(x) || !valid(y) || !valid(h)) return;
        double c = Math.cos(rot), sn = Math.sin(rot);
        float[] a = new float[2], b = new float[2], u = new float[2];
        local(x, y, a, 0);
        local(x + c * h, y + sn * h, b, 0);
        local(x - sn * h, y + c * h, u, 0);
        PlanData.Text t = new PlanData.Text();
        t.layer = layer;
        t.color = color;
        t.x = a[0];
        t.y = a[1];
        t.h = (float) Math.hypot(u[0] - a[0], u[1] - a[1]);
        t.ang = (float) Math.atan2(b[1] - a[1], b[0] - a[0]);
        t.ha = (byte) halign;
        t.va = (byte) valign;
        t.s = s.length() > 400 ? s.substring(0, 400) : s;
        texts.add(t);
        countLayer(layer);
    }

    /** Termine la construction : emprise, grille, tri par taille. */
    public PlanData build(DxfParser parser, String name) {
        PlanData p = new PlanData();
        p.name = name;
        p.geoSystem = parser.geoSystem;
        p.originMx = omx;
        p.originMy = omy;
        p.originX = ox;
        p.originY = oy;
        p.xrefs.clear();
        for (String[] x : parser.xrefs) p.xrefs.add(x[0] + "|" + x[1]);

        for (int i = 0; i < parser.layers.size(); i++) {
            DxfParser.LayerDef d = parser.layers.get(i);
            PlanData.Layer l = new PlanData.Layer();
            l.name = d.name;
            l.xref = d.xref;
            l.color = d.color();
            l.offInDwg = d.off || d.frozen;
            l.visible = !l.offInDwg;
            Integer c = layerCounts.get(i);
            l.count = c == null ? 0 : c;
            p.layers.add(l);
        }

        // Emprise robuste (on écarte les objets égarés loin du projet)
        int total = 0;
        for (FloatBuf b : segs.values()) total += b.n / 4;
        int sample = Math.max(1, total / 200000);
        FloatBuf xs = new FloatBuf(), ys = new FloatBuf();
        int k = 0;
        for (FloatBuf b : segs.values()) {
            for (int i = 0; i < b.n; i += 4, k++) {
                if (k % sample != 0) continue;
                xs.add((b.a[i] + b.a[i + 2]) / 2);
                ys.add((b.a[i + 1] + b.a[i + 3]) / 2);
            }
        }
        for (PlanData.Text t : texts) { xs.add(t.x); ys.add(t.y); }
        if (xs.n == 0) { xs.add(0); ys.add(0); xs.add(100); ys.add(100); }
        float[] sx = Arrays.copyOf(xs.a, xs.n), sy = Arrays.copyOf(ys.a, ys.n);
        Arrays.sort(sx);
        Arrays.sort(sy);
        int lo = (int) (sx.length * 0.001), hi = Math.max(lo, (int) (sx.length * 0.999) - 1);
        float minX = sx[lo], maxX = sx[hi], minY = sy[lo], maxY = sy[hi];
        float pad = Math.max(50, 0.02f * Math.max(maxX - minX, maxY - minY));
        minX -= pad; minY -= pad; maxX += pad; maxY += pad;
        p.minX = minX; p.minY = minY; p.maxX = maxX; p.maxY = maxY;

        float extent = Math.max(maxX - minX, maxY - minY);
        float cell = Math.max(25f, extent / 64f);
        p.cellSize = cell;
        p.gridX = minX;
        p.gridY = minY;
        p.nx = Math.max(1, (int) Math.ceil((maxX - minX) / cell));
        p.ny = Math.max(1, (int) Math.ceil((maxY - minY) / cell));
        p.cells = new PlanData.Cell[p.nx * p.ny];

        // Répartition des segments (découpés à la taille d'une cellule)
        Map<StyleKey, FloatBuf>[] perCell = newMaps(p.cells.length);
        for (Map.Entry<StyleKey, FloatBuf> en : segs.entrySet()) {
            FloatBuf b = en.getValue();
            StyleKey key = en.getKey();
            for (int i = 0; i < b.n; i += 4) {
                float x1 = b.a[i], y1 = b.a[i + 1], x2 = b.a[i + 2], y2 = b.a[i + 3];
                float len = (float) Math.hypot(x2 - x1, y2 - y1);
                int parts = Math.max(1, (int) Math.ceil(len / cell));
                if (parts > 10000) parts = 10000;
                for (int q = 0; q < parts; q++) {
                    float ax = x1 + (x2 - x1) * q / parts, ay = y1 + (y2 - y1) * q / parts;
                    float bx = x1 + (x2 - x1) * (q + 1) / parts, by = y1 + (y2 - y1) * (q + 1) / parts;
                    int ci = cellIndex(p, (ax + bx) / 2, (ay + by) / 2);
                    Map<StyleKey, FloatBuf> m = perCell[ci];
                    FloatBuf fb = m.get(key);
                    if (fb == null) m.put(key, fb = new FloatBuf());
                    fb.add(ax); fb.add(ay); fb.add(bx); fb.add(by);
                }
            }
        }
        segs.clear();

        List<PlanData.Text>[] cellTexts = newLists(p.cells.length);
        for (PlanData.Text t : texts) cellTexts[cellIndex(p, t.x, t.y)].add(t);
        texts.clear();

        for (int ci = 0; ci < p.cells.length; ci++) {
            Map<StyleKey, FloatBuf> m = perCell[ci];
            List<PlanData.Text> tl = cellTexts[ci];
            if (m.isEmpty() && tl.isEmpty()) continue;
            PlanData.Cell c = new PlanData.Cell();
            c.chunks = new PlanData.Chunk[m.size()];
            int j = 0;
            for (Map.Entry<StyleKey, FloatBuf> en : m.entrySet()) {
                c.chunks[j++] = makeChunk(en.getKey(), en.getValue());
            }
            tl.sort((a, b) -> Float.compare(b.h, a.h));
            c.texts = tl.toArray(new PlanData.Text[0]);
            p.cells[ci] = c;
            perCell[ci] = null;
        }

        polys.sort((a, b) -> Float.compare(b.size, a.size));
        p.polys.addAll(polys);
        polys.clear();
        return p;
    }

    private static PlanData.Chunk makeChunk(StyleKey key, FloatBuf b) {
        int n = b.n / 4;
        float[] len = new float[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            len[i] = (float) Math.hypot(b.a[4 * i + 2] - b.a[4 * i], b.a[4 * i + 3] - b.a[4 * i + 1]);
        }
        Arrays.sort(idx, (x, y) -> Float.compare(len[y], len[x]));
        PlanData.Chunk ch = new PlanData.Chunk();
        ch.layer = key.layer;
        ch.color = key.color;
        ch.width = key.width;
        ch.lw = key.lw;
        ch.seg = new float[4 * n];
        ch.len = new float[n];
        for (int i = 0; i < n; i++) {
            int s = idx[i];
            System.arraycopy(b.a, 4 * s, ch.seg, 4 * i, 4);
            ch.len[i] = len[s];
        }
        return ch;
    }

    static int cellIndex(PlanData p, float x, float y) {
        int cx = (int) Math.floor((x - p.gridX) / p.cellSize);
        int cy = (int) Math.floor((y - p.gridY) / p.cellSize);
        cx = Math.max(0, Math.min(p.nx - 1, cx));
        cy = Math.max(0, Math.min(p.ny - 1, cy));
        return cy * p.nx + cx;
    }

    @SuppressWarnings("unchecked")
    private static Map<StyleKey, FloatBuf>[] newMaps(int n) {
        Map<StyleKey, FloatBuf>[] a = new Map[n];
        for (int i = 0; i < n; i++) a[i] = new HashMap<>();
        return a;
    }

    @SuppressWarnings("unchecked")
    private static List<PlanData.Text>[] newLists(int n) {
        List<PlanData.Text>[] a = new List[n];
        for (int i = 0; i < n; i++) a[i] = new ArrayList<>();
        return a;
    }
}
