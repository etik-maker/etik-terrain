package nc.etik.terrain.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Décodage du graphisme de substitution (proxy graphics) des objets métier
 * (Covadis, Civil, etc.) : c'est le dessin qu'AutoCAD enregistre pour les
 * applications qui ne connaissent pas l'objet.
 */
final class ProxyGraphics {
    private final DxfParser p;

    ProxyGraphics(DxfParser p) {
        this.p = p;
    }

    /** Extrait les octets du graphisme : code longueur (92 ou 160) suivi des blocs hexadécimaux 310. */
    static byte[] extract(Ent e) {
        for (int k = 0; k < e.n - 1; k++) {
            int code = e.codes[k];
            if ((code == 92 || code == 160) && e.codes[k + 1] == 310) {
                int len = DxfTokenizer.parseInt(e.vals[k]);
                if (len <= 0) continue;
                byte[] out = new byte[len];
                int pos = 0;
                for (int j = k + 1; j < e.n && e.codes[j] == 310 && pos < len; j++) {
                    String h = e.vals[j].trim();
                    for (int q = 0; q + 1 < h.length() && pos < len; q += 2) {
                        out[pos++] = (byte) ((hex(h.charAt(q)) << 4) | hex(h.charAt(q + 1)));
                    }
                }
                return out;
            }
        }
        return null;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return 0;
    }

    // état courant
    private int layer, entLayer, entColor;
    private int aci;
    private int trueColor;
    private boolean fill;
    private final Deque<double[]> matrices = new ArrayDeque<>();
    private DxfParser.Ctx ctx;
    private Charset ansi;

    void emit(Ent e, DxfParser.Ctx c) {
        byte[] data = extract(e);
        if (data == null || data.length < 8) return;
        ctx = c;
        entLayer = p.resolveLayer(e.layer(), c);
        entColor = p.resolveColor(e, entLayer, c);
        layer = entLayer;
        aci = 256;
        trueColor = -1;
        fill = false;
        matrices.clear();
        ansi = p.version.compareTo("AC1021") < 0 ? Charset.forName("windows-1252") : StandardCharsets.UTF_8;
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int idx = 8;
        while (idx + 8 <= data.length) {
            int size = bb.getInt(idx);
            int type = bb.getInt(idx + 4);
            if (size < 8 || idx + size > data.length) break;
            ByteBuffer d = ByteBuffer.wrap(data, idx + 8, size - 8).slice().order(ByteOrder.LITTLE_ENDIAN);
            try {
                if (command(type, d)) fill = false;
            } catch (RuntimeException ex) {
                // commande illisible : on passe à la suivante
            }
            idx += size;
        }
    }

    private int color() {
        if (trueColor >= 0) return 0xFF000000 | trueColor;
        if (aci == 0) return entColor;
        if (aci == 256) {
            DxfParser.LayerDef l = p.layers.get(layer);
            return l.color();
        }
        if (aci == 7) return GeometrySink.COLOR_FG;
        return 0xFF000000 | AciColors.rgb(aci);
    }

    /** Retourne true si une géométrie a été produite (le remplissage est alors réinitialisé). */
    private boolean command(int type, ByteBuffer d) {
        switch (type) {
            case 2: circle(d); return true;
            case 3: circle3p(d); return true;
            case 4: arc(d); return true;
            case 5: arc3p(d); return true;
            case 6: case 32: polyline(d, false); return true;
            case 7: polyline(d, true); return true;
            case 9: shell(d); return true;
            case 10: text(d, false, false); return true;
            case 11: text(d, false, true); return true;
            case 36: text(d, true, false); return true;
            case 38: text(d, true, true); return true;
            case 33: lwpolyline(d); return true;
            case 44: ellipse(d); return true;
            case 14: {
                trueColor = -1;
                int v = d.getInt(0);
                aci = (v < 0 || v > 256) ? 256 : v;
                return false;
            }
            case 22: {
                int v = d.getInt(0);
                int flags = (v >>> 24) & 0xFF;
                trueColor = -1;
                if (flags == 0xC2) trueColor = v & 0xFFFFFF;
                else if (flags == 0xC3) aci = v & 0xFF;
                else if (flags == 0xC1) aci = 0;
                else aci = 256;
                return false;
            }
            case 16: {
                int i = d.getInt(0);
                if (i >= 0 && i < p.layers.size()) {
                    layer = p.resolveLayer(p.layers.get(i).name, ctx);
                }
                return false;
            }
            case 20: fill = d.getInt(0) != 0; return false;
            case 29: {
                double[] m = new double[16];
                for (int k = 0; k < 16; k++) m[k] = d.getDouble(k * 8);
                matrices.push(m);
                return false;
            }
            case 31: if (!matrices.isEmpty()) matrices.pop(); return false;
            default: return false;
        }
    }

    // ---- géométrie

    private double[] v3(ByteBuffer d, int off) {
        return new double[]{d.getDouble(off), d.getDouble(off + 8), d.getDouble(off + 16)};
    }

    /** Applique la matrice proxy courante puis la transformation de bloc, en place (x,y,z → x,y). */
    private void place(double[] xyz3, int n, double[] out) {
        double[] m = matrices.peek();
        for (int k = 0; k < n; k++) {
            double x = xyz3[3 * k], y = xyz3[3 * k + 1], z = xyz3[3 * k + 2];
            if (m != null) {
                double nx = m[0] * x + m[1] * y + m[2] * z + m[3];
                double ny = m[4] * x + m[5] * y + m[6] * z + m[7];
                x = nx;
                y = ny;
            }
            out[2 * k] = ctx.xf.x(x, y);
            out[2 * k + 1] = ctx.xf.y(x, y);
        }
    }

    private void emitPts(double[] xyz, int n, boolean closed) {
        if (n < 2) return;
        double[] xy = new double[2 * n];
        place(xyz, n, xy);
        int col = color();
        // Les remplissages blancs des étiquettes Covadis sont des masques de fond :
        // sur une carte ils cacheraient tout : on les ignore.
        boolean mask = col == 0xFFFFFFFF || col == GeometrySink.COLOR_FG;
        if (closed && fill) {
            if (!mask) p.fillRaw(layer, col, true, new double[][]{xy});
        } else p.outRaw(layer, col, xy, n, closed);
    }

    private void polyline(ByteBuffer d, boolean polygon) {
        int n = d.getInt(0);
        if (n < 2 || n > 1_000_000) return;
        double[] xyz = new double[3 * n];
        for (int k = 0; k < 3 * n; k++) xyz[k] = d.getDouble(4 + 8 * k);
        emitPts(xyz, n, polygon);
    }

    /** Cercle ou arc dans le plan (u, v) autour du centre, en 3D puis projeté. */
    private void circlePts(double[] c, double r, double[] u, double[] v, double a0, double sweep, boolean closed) {
        int n = Math.max(p.arcSegments(r * ctx.xf.scale(), Math.abs(sweep)), closed ? 16 : 4);
        double[] xyz = new double[3 * (n + 1)];
        for (int i = 0; i <= n; i++) {
            double t = a0 + sweep * i / n;
            double ct = Math.cos(t) * r, st = Math.sin(t) * r;
            for (int k = 0; k < 3; k++) xyz[3 * i + k] = c[k] + ct * u[k] + st * v[k];
        }
        emitPts(xyz, n + 1, closed);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        if (l < 1e-15) return new double[]{1, 0, 0};
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    /** Axe X du repère objet (algorithme d'axe arbitraire d'AutoCAD). */
    private static double[] ocsX(double[] nrm) {
        double[] n = norm(nrm);
        double[] ax = (Math.abs(n[0]) < 1.0 / 64 && Math.abs(n[1]) < 1.0 / 64)
                ? cross(new double[]{0, 1, 0}, n) : cross(new double[]{0, 0, 1}, n);
        return norm(ax);
    }

    private void circle(ByteBuffer d) {
        double[] c = v3(d, 0);
        double r = d.getDouble(24);
        double[] n = norm(v3(d, 32));
        double[] u = ocsX(n), v = cross(n, u);
        circlePts(c, r, u, v, 0, 2 * Math.PI, true);
    }

    private void arc(ByteBuffer d) {
        double[] c = v3(d, 0);
        double r = d.getDouble(24);
        double[] n = norm(v3(d, 32));
        double[] u = norm(v3(d, 56));
        double sweep = d.getDouble(80);
        double[] v = cross(n, u);
        circlePts(c, r, u, v, 0, sweep, false);
    }

    private static double[] circumcenter(double[] a, double[] b, double[] c) {
        double d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
        if (Math.abs(d) < 1e-18) return null;
        double a2 = a[0] * a[0] + a[1] * a[1], b2 = b[0] * b[0] + b[1] * b[1], c2 = c[0] * c[0] + c[1] * c[1];
        double x = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d;
        double y = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d;
        return new double[]{x, y, a[2]};
    }

    private void circle3p(ByteBuffer d) {
        double[] a = v3(d, 0), b = v3(d, 24), c = v3(d, 48);
        double[] o = circumcenter(a, b, c);
        if (o == null) return;
        double r = Math.hypot(a[0] - o[0], a[1] - o[1]);
        circlePts(o, r, new double[]{1, 0, 0}, new double[]{0, 1, 0}, 0, 2 * Math.PI, true);
    }

    private void arc3p(ByteBuffer d) {
        double[] a = v3(d, 0), b = v3(d, 24), c = v3(d, 48);
        double[] o = circumcenter(a, b, c);
        if (o == null) {
            emitPts(new double[]{a[0], a[1], a[2], c[0], c[1], c[2]}, 2, false);
            return;
        }
        double r = Math.hypot(a[0] - o[0], a[1] - o[1]);
        double t1 = Math.atan2(a[1] - o[1], a[0] - o[0]);
        double t2 = Math.atan2(b[1] - o[1], b[0] - o[0]);
        double t3 = Math.atan2(c[1] - o[1], c[0] - o[0]);
        double ccw13 = mod2pi(t3 - t1), ccw12 = mod2pi(t2 - t1);
        double sweep = ccw12 <= ccw13 ? ccw13 : -(2 * Math.PI - ccw13);
        circlePts(o, r, new double[]{1, 0, 0}, new double[]{0, 1, 0}, t1, sweep, false);
    }

    private static double mod2pi(double a) {
        a %= 2 * Math.PI;
        return a < 0 ? a + 2 * Math.PI : a;
    }

    private void ellipse(ByteBuffer d) {
        double[] c = v3(d, 0);
        double[] n = norm(v3(d, 24));
        double major = d.getDouble(48), minor = d.getDouble(56);
        double p0 = d.getDouble(64), p1 = d.getDouble(72), ang = d.getDouble(80);
        double[] ox = ocsX(n), oy = cross(n, ox);
        double[] u = new double[3], v;
        for (int k = 0; k < 3; k++) u[k] = Math.cos(ang) * ox[k] + Math.sin(ang) * oy[k];
        v = cross(n, u);
        double sweep = p1 - p0;
        while (sweep <= 0) sweep += 2 * Math.PI;
        int cnt = Math.max(p.arcSegments(major * ctx.xf.scale(), sweep), 16);
        double[] xyz = new double[3 * (cnt + 1)];
        for (int i = 0; i <= cnt; i++) {
            double t = p0 + sweep * i / cnt;
            double ct = Math.cos(t) * major, st = Math.sin(t) * minor;
            for (int k = 0; k < 3; k++) xyz[3 * i + k] = c[k] + ct * u[k] + st * v[k];
        }
        emitPts(xyz, cnt + 1, false);
    }

    private void shell(ByteBuffer d) {
        int nv = d.getInt(0);
        if (nv <= 0 || nv > 1_000_000) return;
        double[] v = new double[3 * nv];
        for (int k = 0; k < 3 * nv; k++) v[k] = d.getDouble(4 + 8 * k);
        int off = 4 + 24 * nv;
        int entries = d.getInt(off);
        off += 4;
        int read = 0;
        boolean wasFill = fill;
        while (read < entries && off + 4 <= d.limit()) {
            int ec = Math.abs(d.getInt(off));
            off += 4;
            read += 1 + ec;
            double[] xyz = new double[3 * ec];
            for (int k = 0; k < ec; k++) {
                int id = d.getInt(off + 4 * k);
                if (id >= 0 && id < nv) System.arraycopy(v, 3 * id, xyz, 3 * k, 3);
            }
            off += 4 * ec;
            fill = wasFill;
            emitPts(xyz, ec, true);
        }
    }

    private void text(ByteBuffer d, boolean unicode, boolean v2) {
        double[] s = v3(d, 0);
        double[] dir = v3(d, 48);
        int off = 72;
        double h;
        String str;
        if (!v2) {
            h = d.getDouble(off);
            off += 24;
            str = unicode ? readUnicode(d, off) : readAnsi(d, off);
        } else {
            int[] o = {off};
            str = unicode ? readUnicode(d, o) : readAnsi(d, o);
            off = o[0] + 8; // 2 entiers ignorés
            h = d.getDouble(off);
        }
        if (str == null || str.isEmpty()) return;
        double[] pts = {s[0], s[1], s[2], s[0] + dir[0], s[1] + dir[1], s[2] + dir[2]};
        double[] xy = new double[4];
        place(pts, 2, xy);
        double ang = Math.atan2(xy[3] - xy[1], xy[2] - xy[0]);
        double sc = ctx.xf.scale();
        double[] m = matrices.peek();
        if (m != null) sc *= Math.sqrt(Math.abs(m[0] * m[5] - m[1] * m[4]));
        p.textRaw(layer, color(), xy[0], xy[1], h * sc, ang, 0, 0, TextUtil.decodeText(str));
    }

    private static String readAnsi(ByteBuffer d, int off) {
        return readAnsi(d, new int[]{off});
    }

    private static String readAnsi(ByteBuffer d, int[] o) {
        int start = o[0], end = start;
        while (end < d.limit() && d.get(end) != 0) end++;
        byte[] b = new byte[end - start];
        for (int k = 0; k < b.length; k++) b[k] = d.get(start + k);
        o[0] = align4(end + 1);
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    private static String readUnicode(ByteBuffer d, int off) {
        return readUnicode(d, new int[]{off});
    }

    private static String readUnicode(ByteBuffer d, int[] o) {
        int start = o[0], end = start;
        while (end + 1 < d.limit() && (d.get(end) != 0 || d.get(end + 1) != 0)) end += 2;
        byte[] b = new byte[end - start];
        for (int k = 0; k < b.length; k++) b[k] = d.get(start + k);
        o[0] = align4(end + 2);
        return new String(b, StandardCharsets.UTF_16LE);
    }

    private static int align4(int i) {
        return (i + 3) & ~3;
    }

    // ---- LWPOLYLINE au format binaire DWG (flux de bits)

    private void lwpolyline(ByteBuffer d) {
        byte[] raw = new byte[d.limit()];
        for (int k = 0; k < raw.length; k++) raw[k] = d.get(k);
        BitReader bs = new BitReader(raw);
        bs.readRawLong(); // nombre d'octets de données
        int flag = bs.readBitShort();
        double elev = 0;
        if ((flag & 4) != 0) bs.readBitDouble();
        if ((flag & 8) != 0) elev = bs.readBitDouble();
        if ((flag & 2) != 0) bs.readBitDouble();
        if ((flag & 1) != 0) { bs.readBitDouble(); bs.readBitDouble(); bs.readBitDouble(); }
        boolean closed = (flag & 512) != 0;
        int np = bs.readBitLong();
        if (np <= 0 || np > 100000) return;
        int nb = 0;
        if ((flag & 16) != 0) nb = bs.readBitLong();
        if (p.version.compareTo("AC1024") >= 0) {
            if ((flag & 1024) != 0) bs.readBitLong();
            if ((flag & 32) != 0) bs.readBitLong();
        }
        double[] pts = new double[2 * np];
        pts[0] = bs.readRawDouble();
        pts[1] = bs.readRawDouble();
        for (int k = 1; k < np; k++) {
            pts[2 * k] = bs.readBitDoubleDefault(pts[2 * k - 2]);
            pts[2 * k + 1] = bs.readBitDoubleDefault(pts[2 * k - 1]);
        }
        double[] bulges = new double[np];
        for (int k = 0; k < nb && k < np; k++) bulges[k] = bs.readBitDouble();
        DxfParser.PointList pl = new DxfParser.PointList();
        int segs = closed ? np : np - 1;
        pl.add(pts[0], pts[1]);
        for (int k = 0; k < segs; k++) {
            int j = (k + 1) % np;
            double x1 = pts[2 * k], y1 = pts[2 * k + 1], x2 = pts[2 * j], y2 = pts[2 * j + 1];
            double b = bulges[k];
            if (Math.abs(b) > 1e-9) {
                double dx = x2 - x1, dy = y2 - y1, chord = Math.hypot(dx, dy);
                if (chord > 1e-12) {
                    double theta = 4 * Math.atan(b), sg = b * chord / 2;
                    double dd = (chord * chord / 4 - sg * sg) / (2 * sg);
                    double cx = (x1 + x2) / 2 - dy / chord * dd, cy = (y1 + y2) / 2 + dx / chord * dd;
                    double r = (chord * chord / 4 + sg * sg) / (2 * Math.abs(sg));
                    double a1 = Math.atan2(y1 - cy, x1 - cx);
                    int ns = p.arcSegments(r, Math.abs(theta));
                    for (int i = 1; i < ns; i++) {
                        double t = a1 + theta * i / ns;
                        pl.add(cx + r * Math.cos(t), cy + r * Math.sin(t));
                    }
                }
            }
            pl.add(x2, y2);
        }
        double[] xyz = new double[3 * pl.n];
        for (int k = 0; k < pl.n; k++) {
            xyz[3 * k] = pl.xy[2 * k];
            xyz[3 * k + 1] = pl.xy[2 * k + 1];
            xyz[3 * k + 2] = elev;
        }
        emitPts(xyz, pl.n, false);
    }

    static final class BitReader {
        private final byte[] buf;
        private int bit;

        BitReader(byte[] b) {
            buf = b;
        }

        int readBit() {
            int v = (buf[bit >> 3] & (0x80 >> (bit & 7))) != 0 ? 1 : 0;
            bit++;
            return v;
        }

        int readBits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | readBit();
            return v;
        }

        int readByte() {
            return readBits(8);
        }

        int readShort() {
            int a = readByte(), b = readByte();
            return (short) ((b << 8) | a);
        }

        int readRawLong() {
            int a = readByte(), b = readByte(), c = readByte(), d = readByte();
            return (d << 24) | (c << 16) | (b << 8) | a;
        }

        double readRawDouble() {
            long v = 0;
            for (int i = 0; i < 8; i++) v |= ((long) readByte()) << (8 * i);
            return Double.longBitsToDouble(v);
        }

        int readBitShort() {
            switch (readBits(2)) {
                case 0: return readShort();
                case 1: return readByte();
                case 2: return 0;
                default: return 256;
            }
        }

        int readBitLong() {
            switch (readBits(2)) {
                case 0: return readRawLong();
                case 1: return readByte();
                case 2: return 0;
                default: return 256;
            }
        }

        double readBitDouble() {
            switch (readBits(2)) {
                case 0: return readRawDouble();
                case 1: return 1.0;
                default: return 0.0;
            }
        }

        double readBitDoubleDefault(double def) {
            long bits = Double.doubleToRawLongBits(def);
            switch (readBits(2)) {
                case 0: return def;
                case 1: {
                    long v = bits & 0xFFFFFFFF00000000L;
                    for (int i = 0; i < 4; i++) v |= ((long) readByte()) << (8 * i);
                    return Double.longBitsToDouble(v);
                }
                case 2: {
                    long v = bits & 0xFFFF000000000000L;
                    v |= ((long) readByte()) << 32;
                    v |= ((long) readByte()) << 40;
                    for (int i = 0; i < 4; i++) v |= ((long) readByte()) << (8 * i);
                    return Double.longBitsToDouble(v);
                }
                default: return readRawDouble();
            }
        }
    }
}
