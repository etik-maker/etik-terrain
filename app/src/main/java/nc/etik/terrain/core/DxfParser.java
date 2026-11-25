package nc.etik.terrain.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecteur DXF : calques, blocs (développés avec leurs transformations), entités
 * courantes et graphisme de substitution des objets métier (Covadis, etc.).
 * Toute la géométrie est envoyée à un {@link GeometrySink} en coordonnées du dessin.
 */
public final class DxfParser {

    public interface Progress {
        void onProgress(long bytes);
    }

    public static final class LayerDef {
        public final String name;
        public int aci = 7;
        public int trueColor = -1;
        public boolean off, frozen;
        public final String xref; // préfixe XREF "nom|" ou null

        LayerDef(String name) {
            this.name = name;
            int bar = name.indexOf('|');
            xref = bar > 0 ? name.substring(0, bar) : null;
        }

        public int color() {
            if (trueColor >= 0) return 0xFF000000 | trueColor;
            int a = Math.abs(aci);
            if (a == 7 || a == 0 || a > 255) return GeometrySink.COLOR_FG;
            return 0xFF000000 | AciColors.rgb(a);
        }
    }

    static final class Block {
        final String name;
        double bx, by;
        int flags;
        String xrefPath;
        final List<Ent> ents = new ArrayList<>();

        Block(String name) {
            this.name = name;
        }
    }

    /** Transformation affine 2D : x' = a x + b y + c ; y' = d x + e y + f. */
    static final class Xf {
        final double a, b, c, d, e, f;

        Xf(double a, double b, double c, double d, double e, double f) {
            this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
        }

        static final Xf ID = new Xf(1, 0, 0, 0, 1, 0);

        /** this ∘ o : applique o puis this. */
        Xf mul(Xf o) {
            return new Xf(
                    a * o.a + b * o.d, a * o.b + b * o.e, a * o.c + b * o.f + c,
                    d * o.a + e * o.d, d * o.b + e * o.e, d * o.c + e * o.f + f);
        }

        double x(double x, double y) { return a * x + b * y + c; }
        double y(double x, double y) { return d * x + e * y + f; }
        double scale() { return Math.sqrt(Math.abs(a * e - b * d)); }
    }

    static final class Ctx {
        final Xf xf;
        final int insertLayer; // -1 au niveau du dessin
        final int blockColor;  // couleur héritée (DUBLOC)
        final int depth;

        Ctx(Xf xf, int insertLayer, int blockColor, int depth) {
            this.xf = xf; this.insertLayer = insertLayer; this.blockColor = blockColor; this.depth = depth;
        }
    }

    public final List<LayerDef> layers = new ArrayList<>();
    private final Map<String, Integer> layerIndex = new HashMap<>();
    private final Map<String, Block> blocks = new HashMap<>();
    public final List<String[]> xrefs = new ArrayList<>(); // {nom, chemin}
    public String version = "";
    public double[] extMin, extMax;
    public String geoSystem = "";
    public int entityCount, proxyCount;
    public final Map<String, Integer> skipped = new HashMap<>();

    private GeometrySink sink;
    private Progress progress;
    private DxfTokenizer tk;
    private ProxyGraphics proxy;
    private double tol = 0.01; // tolérance de facettisation des arcs (m)

    public int layerIdx(String name) {
        Integer i = layerIndex.get(name);
        if (i != null) return i;
        // AutoCAD ne distingue pas la casse des noms de calques
        for (int k = 0; k < layers.size(); k++) if (layers.get(k).name.equalsIgnoreCase(name)) {
            layerIndex.put(name, k);
            return k;
        }
        LayerDef l = new LayerDef(name);
        layers.add(l);
        layerIndex.put(name, layers.size() - 1);
        return layers.size() - 1;
    }

    /** Lecture rapide : en-tête et table des calques uniquement. */
    public void readTablesOnly(InputStream in) throws IOException {
        parse(in, null, null, true);
    }

    public void parse(InputStream in, GeometrySink sink, Progress progress) throws IOException {
        parse(in, sink, progress, false);
    }

    private void parse(InputStream in, GeometrySink sink, Progress progress, boolean tablesOnly) throws IOException {
        this.sink = sink;
        this.progress = progress;
        this.tk = new DxfTokenizer(in);
        this.proxy = new ProxyGraphics(this);
        String section = null;
        while (tk.next()) {
            if (tk.code != 0) continue;
            String v = tk.value.trim();
            if (v.equals("SECTION")) {
                if (!tk.next()) break;
                section = tk.value.trim();
                if (section.equals("HEADER")) readHeader();
                else if (section.equals("TABLES")) {
                    readTables();
                    if (tablesOnly) return;
                } else if (section.equals("BLOCKS")) readBlocks();
                else if (section.equals("ENTITIES")) readEntities();
                else if (section.equals("OBJECTS")) break; // inutile pour l'affichage
            } else if (v.equals("EOF")) break;
        }
    }

    // ------------------------------------------------------------------ HEADER

    private void readHeader() throws IOException {
        String var = null;
        while (tk.next()) {
            if (tk.code == 0 && tk.value.trim().equals("ENDSEC")) return;
            if (tk.code == 9) {
                var = tk.value.trim();
                continue;
            }
            if (var == null) continue;
            switch (var) {
                case "$ACADVER":
                    version = tk.value.trim();
                    if (version.compareTo("AC1021") < 0) tk.setCharset(Charset.forName("windows-1252"));
                    break;
                case "$EXTMIN":
                    if (extMin == null) extMin = new double[2];
                    if (tk.code == 10) extMin[0] = tk.d(); else if (tk.code == 20) extMin[1] = tk.d();
                    break;
                case "$EXTMAX":
                    if (extMax == null) extMax = new double[2];
                    if (tk.code == 10) extMax[0] = tk.d(); else if (tk.code == 20) extMax[1] = tk.d();
                    break;
                case "$CUSTOMPROPERTY":
                    String s = tk.value.trim();
                    if (s.startsWith("RGN") || s.contains("Lambert") || s.contains("Caledonia")) geoSystem = s;
                    break;
                default:
            }
        }
    }

    // ------------------------------------------------------------------ TABLES

    private void readTables() throws IOException {
        String table = null;
        Ent cur = null;
        while (tk.next()) {
            if (tk.code == 0) {
                if (cur != null && "LAYER".equals(table)) addLayer(cur);
                cur = null;
                String v = tk.value.trim();
                if (v.equals("ENDSEC")) return;
                if (v.equals("TABLE")) {
                    tk.next();
                    table = tk.value.trim();
                } else if (v.equals("ENDTAB")) {
                    table = null;
                } else if (v.equals("LAYER") && "LAYER".equals(table)) {
                    cur = new Ent("LAYER");
                }
            } else if (cur != null) {
                cur.add(tk.code, tk.value);
            }
        }
    }

    private void addLayer(Ent e) {
        String name = e.s(2, "0");
        int idx = layerIdx(name);
        LayerDef l = layers.get(idx);
        int c = e.i(62, 7);
        l.aci = c;
        l.off = c < 0;
        l.trueColor = e.i(420, -1);
        l.frozen = (e.i(70, 0) & 1) != 0;
    }

    // ------------------------------------------------------------------ Lecture d'entités

    /** Lit une entité complète (le code 0 courant est son type). Gère VERTEX/ATTRIB/SEQEND. */
    private Ent readEnt(String type) throws IOException {
        Ent e = new Ent(type);
        while (tk.next()) {
            if (tk.code == 0) {
                tk.pushBack();
                break;
            }
            e.add(tk.code, tk.value);
        }
        boolean hasChildren = type.equals("POLYLINE") || (type.equals("INSERT") && e.i(66, 0) == 1);
        if (hasChildren) {
            while (tk.next()) {
                if (tk.code != 0) continue;
                String t = tk.value.trim();
                if (t.equals("SEQEND")) {
                    readEnt("SEQEND");
                    break;
                }
                if (t.equals("VERTEX") || t.equals("ATTRIB")) {
                    e.addChild(readEnt(t));
                } else {
                    tk.pushBack();
                    break;
                }
            }
        }
        return e;
    }

    private void readBlocks() throws IOException {
        Block cur = null;
        while (tk.next()) {
            if (tk.code != 0) continue;
            String t = tk.value.trim();
            if (t.equals("ENDSEC")) return;
            if (t.equals("BLOCK")) {
                Ent b = readEnt("BLOCK");
                cur = new Block(b.s(2, ""));
                cur.bx = b.d(10, 0);
                cur.by = b.d(20, 0);
                cur.flags = b.i(70, 0);
                if ((cur.flags & 4) != 0) {
                    cur.xrefPath = b.s(1, "");
                    xrefs.add(new String[]{cur.name, cur.xrefPath});
                }
                blocks.put(cur.name, cur);
                blocks.put(cur.name.toUpperCase(), cur);
            } else if (t.equals("ENDBLK")) {
                readEnt("ENDBLK");
                cur = null;
            } else {
                Ent e = readEnt(t);
                if (cur != null) cur.ents.add(e);
            }
        }
    }

    private void readEntities() throws IOException {
        Ctx root = new Ctx(Xf.ID, -1, GeometrySink.COLOR_FG, 0);
        long lastReport = 0;
        while (tk.next()) {
            if (tk.code != 0) continue;
            String t = tk.value.trim();
            if (t.equals("ENDSEC")) return;
            Ent e = readEnt(t);
            if (e.i(67, 0) == 1) continue; // espace papier
            entityCount++;
            if (sink != null) {
                try {
                    emit(e, root);
                } catch (RuntimeException ex) {
                    count("erreur " + t);
                }
            }
            if (progress != null && tk.bytesRead() - lastReport > 1_000_000) {
                lastReport = tk.bytesRead();
                progress.onProgress(lastReport);
            }
        }
    }

    private void count(String what) {
        Integer c = skipped.get(what);
        skipped.put(what, c == null ? 1 : c + 1);
    }

    // ------------------------------------------------------------------ Couleurs et calques

    int resolveLayer(String name, Ctx c) {
        if (c.insertLayer >= 0 && name.equals("0")) return c.insertLayer;
        return layerIdx(name);
    }

    int resolveColor(Ent e, int layer, Ctx c) {
        int tc = e.i(420, -1);
        if (tc >= 0) return 0xFF000000 | tc;
        return resolveAci(e.i(62, 256), layer, c);
    }

    int resolveAci(int aci, int layer, Ctx c) {
        if (aci == 256 || aci < 0) return layers.get(layer).color();
        if (aci == 0) return c.blockColor == GeometrySink.COLOR_FG && c.depth == 0 ? layers.get(layer).color() : c.blockColor;
        if (aci == 7 || aci > 255) return GeometrySink.COLOR_FG;
        return 0xFF000000 | AciColors.rgb(aci);
    }

    // ------------------------------------------------------------------ Repère objet (OCS)

    /** Matrice OCS→WCS (seulement x et y utiles) à partir de l'extrusion. */
    static double[] ocs(Ent e) {
        double nx = e.d(210, 0), ny = e.d(220, 0), nz = e.d(230, 1);
        if (Math.abs(nx) < 1e-12 && Math.abs(ny) < 1e-12) {
            if (nz >= 0) return null; // identité
            return new double[]{-1, 0, 0, 1, 0, 0}; // miroir en X
        }
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= l; ny /= l; nz /= l;
        double ax, ay, az;
        if (Math.abs(nx) < 1.0 / 64 && Math.abs(ny) < 1.0 / 64) { // Wy × N
            ax = nz; ay = 0; az = -nx;
        } else { // Wz × N
            ax = -ny; ay = nx; az = 0;
        }
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= la; ay /= la; az /= la;
        double bx = ny * az - nz * ay, by = nz * ax - nx * az;
        // wx = x*ax + y*bx + z*nx ; wy = x*ay + y*by + z*ny
        return new double[]{ax, bx, nx, ay, by, ny};
    }

    /** Transforme en place des points OCS (avec élévation z) puis par la transformation de bloc. */
    private void toWcs(double[] xy, int count, double[] m, double z, Xf xf) {
        for (int k = 0; k < count; k++) {
            double x = xy[2 * k], y = xy[2 * k + 1];
            if (m != null) {
                double wx = x * m[0] + y * m[1] + z * m[2];
                double wy = x * m[3] + y * m[4] + z * m[5];
                x = wx;
                y = wy;
            }
            xy[2 * k] = xf.x(x, y);
            xy[2 * k + 1] = xf.y(x, y);
        }
    }

    // ------------------------------------------------------------------ Émission

    void emit(Ent e, Ctx c) {
        String t = e.type;
        switch (t) {
            case "LINE": emitLine(e, c); break;
            case "LWPOLYLINE": emitLwPolyline(e, c); break;
            case "POLYLINE": emitPolyline(e, c); break;
            case "ARC": emitArc(e, c, false); break;
            case "CIRCLE": emitArc(e, c, true); break;
            case "ELLIPSE": emitEllipse(e, c); break;
            case "SPLINE": emitSpline(e, c); break;
            case "TEXT": emitText(e, c, false); break;
            case "ATTRIB": if ((e.i(70, 0) & 1) == 0) emitText(e, c, false); break;
            case "MTEXT": emitMText(e, c); break;
            case "INSERT": emitInsert(e, c); break;
            case "DIMENSION": emitDimension(e, c); break;
            case "HATCH": emitHatch(e, c); break;
            case "SOLID": case "TRACE": emitSolid(e, c); break;
            case "3DFACE": emit3dFace(e, c); break;
            case "LEADER": emitLeader(e, c); break;
            case "POINT": case "ATTDEF": case "VIEWPORT": case "IMAGE": case "WIPEOUT":
            case "MULTILEADER": case "OLE2FRAME": case "XLINE": case "RAY":
                count(t);
                break;
            default:
                if (e.has(310) && e.has(92) || e.has(310) && e.has(160)) {
                    proxyCount++;
                    if (c.depth < 16) proxy.emit(e, c);
                } else count(t);
        }
    }

    private void out(Ent e, Ctx c, double[] xy, int n, boolean closed) {
        if (n < 2) return;
        int layer = resolveLayer(e.layer(), c);
        sink.polyline(layer, resolveColor(e, layer, c), xy, n, closed);
    }

    void outRaw(int layer, int color, double[] xy, int n, boolean closed) {
        if (n >= 2) sink.polyline(layer, color, xy, n, closed);
    }

    void fillRaw(int layer, int color, boolean solid, double[][] rings) {
        sink.fill(layer, color, solid, rings);
    }

    void textRaw(int layer, int color, double x, double y, double h, double rot, int ha, int va, String s) {
        if (s != null && !s.isEmpty() && h > 0) sink.text(layer, color, x, y, h, rot, ha, va, s);
    }

    private void emitLine(Ent e, Ctx c) {
        double[] xy = {e.d(10, 0), e.d(20, 0), e.d(11, 0), e.d(21, 0)};
        // LINE est en WCS ; l'extrusion ne concerne que l'épaisseur
        toWcs(xy, 2, null, 0, c.xf);
        out(e, c, xy, 2, false);
    }

    private void emitLwPolyline(Ent e, Ctx c) {
        int flags = e.i(70, 0);
        double elev = e.d(38, 0);
        PointList pl = new PointList();
        List<double[]> verts = new ArrayList<>();
        double[] cur = null;
        for (int k = 0; k < e.n; k++) {
            int code = e.codes[k];
            if (code == 10) {
                cur = new double[]{DxfTokenizer.parseDouble(e.vals[k]), 0, 0};
                verts.add(cur);
            } else if (code == 20 && cur != null) cur[1] = DxfTokenizer.parseDouble(e.vals[k]);
            else if (code == 42 && cur != null) cur[2] = DxfTokenizer.parseDouble(e.vals[k]);
        }
        boolean closed = (flags & 1) != 0;
        buildBulged(verts, closed, pl);
        double[] m = ocs(e);
        toWcs(pl.xy, pl.n, m, elev, c.xf);
        out(e, c, pl.xy, pl.n, closed && !pl.closedExplicit);
    }

    /** Construit la polyligne en développant les arcs (bulges). */
    private void buildBulged(List<double[]> verts, boolean closed, PointList pl) {
        int n = verts.size();
        if (n == 0) return;
        int segs = closed ? n : n - 1;
        pl.add(verts.get(0)[0], verts.get(0)[1]);
        for (int k = 0; k < segs; k++) {
            double[] a = verts.get(k), b = verts.get((k + 1) % n);
            if (Math.abs(a[2]) > 1e-9) addBulge(pl, a[0], a[1], b[0], b[1], a[2]);
            pl.add(b[0], b[1]);
        }
        if (closed) pl.closedExplicit = true;
    }

    private void addBulge(PointList pl, double x1, double y1, double x2, double y2, double bulge) {
        double dx = x2 - x1, dy = y2 - y1;
        double chord = Math.sqrt(dx * dx + dy * dy);
        if (chord < 1e-12) return;
        double theta = 4 * Math.atan(bulge); // angle balayé, signé (positif = sens trigo)
        double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
        double ux = -dy / chord, uy = dx / chord; // normale à gauche de la corde
        double s = bulge * chord / 2; // flèche signée
        double d = (chord * chord / 4 - s * s) / (2 * s); // distance signée milieu -> centre (vers la gauche)
        double cx = mx + ux * d, cy = my + uy * d;
        double r = (chord * chord / 4 + s * s) / (2 * Math.abs(s));
        double a1 = Math.atan2(y1 - cy, x1 - cx);
        int nseg = arcSegments(r, Math.abs(theta));
        for (int i = 1; i < nseg; i++) {
            double a = a1 + theta * i / nseg;
            pl.add(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
    }

    int arcSegments(double r, double sweep) {
        if (r <= 0) return 1;
        double t = tol / r;
        double step = t >= 1 ? Math.PI / 2 : 2 * Math.acos(1 - t);
        step = Math.max(step, Math.PI / 90); // au plus 2°
        int n = (int) Math.ceil(sweep / step);
        return Math.max(2, Math.min(n, 360));
    }

    private void emitPolyline(Ent e, Ctx c) {
        int flags = e.i(70, 0);
        if (e.children == null) return;
        if ((flags & 64) != 0) { // maillage polyface : on trace les arêtes des faces
            List<double[]> pts = new ArrayList<>();
            for (Ent v : e.children) {
                int vf = v.i(70, 0);
                if ((vf & 128) != 0 && (vf & 64) != 0) pts.add(new double[]{v.d(10, 0), v.d(20, 0)});
            }
            for (Ent v : e.children) {
                int vf = v.i(70, 0);
                if ((vf & 128) != 0 && (vf & 64) == 0) {
                    int[] idx = {v.i(71, 0), v.i(72, 0), v.i(73, 0), v.i(74, 0)};
                    PointList pl = new PointList();
                    for (int id : idx) {
                        int a = Math.abs(id) - 1;
                        if (id != 0 && a < pts.size()) pl.add(pts.get(a)[0], pts.get(a)[1]);
                    }
                    toWcs(pl.xy, pl.n, null, 0, c.xf);
                    out(e, c, pl.xy, pl.n, true);
                }
            }
            return;
        }
        if ((flags & 16) != 0) { // maillage polygonal M x N
            int mc = e.i(71, 0), nc = e.i(72, 0);
            List<Ent> vs = e.children;
            if (mc * nc > vs.size() || mc == 0) return;
            for (int i = 0; i < mc; i++) {
                PointList pl = new PointList();
                for (int j = 0; j < nc; j++) { Ent v = vs.get(i * nc + j); pl.add(v.d(10, 0), v.d(20, 0)); }
                toWcs(pl.xy, pl.n, null, 0, c.xf);
                out(e, c, pl.xy, pl.n, false);
            }
            for (int j = 0; j < nc; j++) {
                PointList pl = new PointList();
                for (int i = 0; i < mc; i++) { Ent v = vs.get(i * nc + j); pl.add(v.d(10, 0), v.d(20, 0)); }
                toWcs(pl.xy, pl.n, null, 0, c.xf);
                out(e, c, pl.xy, pl.n, false);
            }
            return;
        }
        boolean is3d = (flags & 8) != 0;
        boolean closed = (flags & 1) != 0;
        List<double[]> verts = new ArrayList<>();
        for (Ent v : e.children) {
            if ((v.i(70, 0) & 16) != 0) continue; // point de contrôle de lissage
            verts.add(new double[]{v.d(10, 0), v.d(20, 0), is3d ? 0 : v.d(42, 0)});
        }
        PointList pl = new PointList();
        buildBulged(verts, closed, pl);
        double[] m = is3d ? null : ocs(e);
        toWcs(pl.xy, pl.n, m, e.d(30, 0), c.xf);
        out(e, c, pl.xy, pl.n, false);
    }

    private void emitArc(Ent e, Ctx c, boolean full) {
        double cx = e.d(10, 0), cy = e.d(20, 0), r = e.d(40, 0);
        if (r <= 0) return;
        double a1 = full ? 0 : Math.toRadians(e.d(50, 0));
        double a2 = full ? 2 * Math.PI : Math.toRadians(e.d(51, 0));
        double sweep = a2 - a1;
        while (sweep <= 0) sweep += 2 * Math.PI;
        if (full) sweep = 2 * Math.PI;
        int n = arcSegments(r * c.xf.scale(), sweep);
        if (full) n = Math.max(n, 16);
        PointList pl = new PointList();
        for (int i = 0; i <= n; i++) {
            double a = a1 + sweep * i / n;
            pl.add(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
        toWcs(pl.xy, pl.n, ocs(e), e.d(30, 0), c.xf);
        out(e, c, pl.xy, pl.n, false);
    }

    private void emitEllipse(Ent e, Ctx c) {
        double cx = e.d(10, 0), cy = e.d(20, 0);
        double mx = e.d(11, 0), my = e.d(21, 0), mz = e.d(31, 0);
        double ratio = e.d(40, 1);
        double p1 = e.d(41, 0), p2 = e.d(42, 2 * Math.PI);
        double nx = e.d(210, 0), ny = e.d(220, 0), nz = e.d(230, 1);
        // petit axe = N × grand axe × ratio
        double ix = (ny * mz - nz * my) * ratio, iy = (nz * mx - nx * mz) * ratio;
        double sweep = p2 - p1;
        while (sweep <= 0) sweep += 2 * Math.PI;
        double R = Math.sqrt(mx * mx + my * my);
        int n = Math.max(arcSegments(R * c.xf.scale(), sweep), 16);
        PointList pl = new PointList();
        for (int i = 0; i <= n; i++) {
            double t = p1 + sweep * i / n;
            double ct = Math.cos(t), st = Math.sin(t);
            pl.add(cx + mx * ct + ix * st, cy + my * ct + iy * st);
        }
        toWcs(pl.xy, pl.n, null, 0, c.xf);
        out(e, c, pl.xy, pl.n, false);
    }

    private void emitSpline(Ent e, Ctx c) {
        int degree = e.i(71, 3);
        List<double[]> ctrl = new ArrayList<>(), fit = new ArrayList<>();
        List<Double> knots = new ArrayList<>(), weights = new ArrayList<>();
        double[] cur = null, curFit = null;
        for (int k = 0; k < e.n; k++) {
            int code = e.codes[k];
            double v = DxfTokenizer.parseDouble(e.vals[k]);
            switch (code) {
                case 10: cur = new double[]{v, 0}; ctrl.add(cur); break;
                case 20: if (cur != null) cur[1] = v; break;
                case 11: curFit = new double[]{v, 0}; fit.add(curFit); break;
                case 21: if (curFit != null) curFit[1] = v; break;
                case 40: knots.add(v); break;
                case 41: weights.add(v); break;
                default:
            }
        }
        PointList pl = new PointList();
        if (ctrl.size() > degree && knots.size() == ctrl.size() + degree + 1) {
            Spline.sample(ctrl, knots, weights, degree, pl);
        } else if (fit.size() >= 2) {
            for (double[] p : fit) pl.add(p[0], p[1]);
        } else {
            for (double[] p : ctrl) pl.add(p[0], p[1]);
        }
        toWcs(pl.xy, pl.n, null, 0, c.xf);
        out(e, c, pl.xy, pl.n, (e.i(70, 0) & 1) != 0);
    }

    private void emitText(Ent e, Ctx c, boolean unused) {
        String s = TextUtil.decodeText(e.s(1, ""));
        if (s.trim().isEmpty()) return;
        int ha = e.i(72, 0), va = e.i(74, e.i(73, 0));
        if (e.type.equals("TEXT")) va = e.i(73, 0);
        double x = e.d(10, 0), y = e.d(20, 0);
        if ((ha != 0 || va != 0) && e.has(11)) { x = e.d(11, x); y = e.d(21, y); }
        int hal = ha == 1 || ha == 4 ? 1 : ha == 2 ? 2 : 0;
        int val = ha == 4 ? 2 : va;
        if (ha == 3 || ha == 5) { hal = 0; val = 0; x = e.d(10, 0); y = e.d(20, 0); }
        double h = e.d(40, 1);
        double rot = Math.toRadians(e.d(50, 0));
        emitTextGeom(e, c, x, y, h, rot, hal, val, s);
    }

    private void emitTextGeom(Ent e, Ctx c, double x, double y, double h, double rot, int hal, int val, String s) {
        double[] p = {x, y, x + Math.cos(rot) * h, y + Math.sin(rot) * h, x - Math.sin(rot) * h, y + Math.cos(rot) * h};
        toWcs(p, 3, ocs(e), e.d(30, 0), c.xf);
        double dx = p[2] - p[0], dy = p[3] - p[1];
        double ux = p[4] - p[0], uy = p[5] - p[1];
        double hh = Math.sqrt(ux * ux + uy * uy);
        double ang = Math.atan2(dy, dx);
        // texte en miroir : on le garde lisible
        int layer = resolveLayer(e.layer(), c);
        textRaw(layer, resolveColor(e, layer, c), p[0], p[1], hh, ang, hal, val, s);
    }

    private void emitMText(Ent e, Ctx c) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < e.n; k++) if (e.codes[k] == 3) sb.append(e.vals[k]);
        sb.append(e.s(1, ""));
        String s = TextUtil.decodeMText(sb.toString());
        if (s.trim().isEmpty()) return;
        double x = e.d(10, 0), y = e.d(20, 0), h = e.d(40, 1);
        double rot;
        if (e.has(11)) rot = Math.atan2(e.d(21, 0), e.d(11, 1));
        else rot = Math.toRadians(e.d(50, 0));
        int att = e.i(71, 1);
        int hal = (att - 1) % 3;
        int row = (att - 1) / 3; // 0 haut, 1 milieu, 2 bas
        String[] lines = s.split("\n");
        double lh = h * 1.667 * e.d(44, 1);
        int nl = lines.length;
        // position de la première ligne (ancrage haut de la première ligne)
        double offset; // décalage vers le bas depuis l'ancrage, pour la ligne 0, ancrage en haut de ligne
        if (row == 0) offset = 0;
        else if (row == 1) offset = -((nl - 1) * lh + h) / 2;
        else offset = -((nl - 1) * lh + h);
        for (int i = 0; i < nl; i++) {
            if (lines[i].trim().isEmpty()) continue;
            double d = offset + i * lh;
            double lx = x + Math.sin(rot) * d, ly = y - Math.cos(rot) * d;
            emitTextGeom(e, c, lx, ly, h, rot, hal, 3, lines[i]);
        }
    }

    private void emitInsert(Ent e, Ctx c) {
        Block b = blocks.get(e.s(2, ""));
        if (b == null) b = blocks.get(e.s(2, "").toUpperCase());
        int layer = resolveLayer(e.layer(), c);
        int color = resolveColor(e, layer, c);
        if (b != null && c.depth < 16 && (b.flags & 4) == 0) {
            double sx = e.d(41, 1), sy = e.d(42, 1), rot = Math.toRadians(e.d(50, 0));
            int cols = Math.max(1, e.i(70, 1)), rows = Math.max(1, e.i(71, 1));
            double cs = e.d(44, 0), rs = e.d(45, 0);
            double[] m = ocs(e);
            double cr = Math.cos(rot), sr = Math.sin(rot);
            for (int ri = 0; ri < rows; ri++) {
                for (int ci = 0; ci < cols; ci++) {
                    double ox = ci * cs, oy = ri * rs;
                    double ix = e.d(10, 0) + cr * ox - sr * oy, iy = e.d(20, 0) + sr * ox + cr * oy;
                    // bloc -> OCS : T(insert) R S T(-base)
                    Xf local = new Xf(cr * sx, -sr * sy, 0, sr * sx, cr * sy, 0);
                    double tx = ix - (local.a * b.bx + local.b * b.by), ty = iy - (local.d * b.bx + local.e * b.by);
                    local = new Xf(local.a, local.b, tx, local.d, local.e, ty);
                    if (m != null) local = new Xf(m[0], m[1], 0, m[3], m[4], 0).mul(local);
                    Ctx sub = new Ctx(c.xf.mul(local), layer, color, c.depth + 1);
                    for (Ent be : b.ents) {
                        try {
                            emit(be, sub);
                        } catch (RuntimeException ex) {
                            count("erreur " + be.type);
                        }
                    }
                }
            }
        }
        if (e.children != null) for (Ent a : e.children) if ((a.i(70, 0) & 1) == 0) emitText(a, c, false);
    }

    private void emitDimension(Ent e, Ctx c) {
        Block b = blocks.get(e.s(2, ""));
        if (b == null) return;
        int layer = resolveLayer(e.layer(), c);
        Ctx sub = new Ctx(c.xf, layer, resolveColor(e, layer, c), c.depth + 1);
        for (Ent be : b.ents) emit(be, sub);
    }

    private void emitSolid(Ent e, Ctx c) {
        double[] xy = {e.d(10, 0), e.d(20, 0), e.d(11, 0), e.d(21, 0), e.d(13, e.d(12, 0)), e.d(23, e.d(22, 0)), e.d(12, 0), e.d(22, 0)};
        toWcs(xy, 4, ocs(e), e.d(30, 0), c.xf);
        int layer = resolveLayer(e.layer(), c);
        fillRaw(layer, resolveColor(e, layer, c), true, new double[][]{xy});
    }

    private void emit3dFace(Ent e, Ctx c) {
        double[] xy = {e.d(10, 0), e.d(20, 0), e.d(11, 0), e.d(21, 0), e.d(12, 0), e.d(22, 0), e.d(13, 0), e.d(23, 0)};
        toWcs(xy, 4, null, 0, c.xf);
        out(e, c, xy, 4, true);
    }

    private void emitLeader(Ent e, Ctx c) {
        PointList pl = new PointList();
        double[] cur = null;
        for (int k = 0; k < e.n; k++) {
            if (e.codes[k] == 10) { pl.add(DxfTokenizer.parseDouble(e.vals[k]), 0); }
            else if (e.codes[k] == 20 && pl.n > 0) pl.xy[2 * pl.n - 1] = DxfTokenizer.parseDouble(e.vals[k]);
        }
        toWcs(pl.xy, pl.n, null, 0, c.xf);
        out(e, c, pl.xy, pl.n, false);
    }

    private void emitHatch(Ent e, Ctx c) {
        List<double[]> rings = Hatch.parseBoundaries(e, this);
        if (rings.isEmpty()) return;
        double[] m = ocs(e);
        double elev = e.d(30, 0);
        double[][] arr = new double[rings.size()][];
        for (int i = 0; i < rings.size(); i++) {
            double[] r = rings.get(i);
            toWcs(r, r.length / 2, m, elev, c.xf);
            arr[i] = r;
        }
        int layer = resolveLayer(e.layer(), c);
        fillRaw(layer, resolveColor(e, layer, c), e.i(70, 0) == 1, arr);
    }

    /** Liste de points extensible. */
    static final class PointList {
        double[] xy = new double[32];
        int n;
        boolean closedExplicit;

        void add(double x, double y) {
            if (2 * n + 2 > xy.length) {
                double[] a = new double[xy.length * 2];
                System.arraycopy(xy, 0, a, 0, 2 * n);
                xy = a;
            }
            xy[2 * n] = x;
            xy[2 * n + 1] = y;
            n++;
        }

        double[] toArray() {
            double[] a = new double[2 * n];
            System.arraycopy(xy, 0, a, 0, 2 * n);
            return a;
        }
    }
}
