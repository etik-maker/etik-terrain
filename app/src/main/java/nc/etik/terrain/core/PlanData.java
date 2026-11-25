package nc.etik.terrain.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan prêt à afficher : coordonnées Web Mercator relatives à une origine
 * (en float, précision millimétrique), indexées dans une grille, triées par
 * taille décroissante pour n'afficher que ce qui est visible à l'échelle.
 */
public final class PlanData {
    public static final int FORMAT = 3;

    public String name = "";
    public double originMx, originMy;      // origine en Web Mercator (m)
    public double originX, originY;        // même origine en Lambert NC (m)
    public float minX, minY, maxX, maxY;   // emprise en coordonnées locales

    public final List<Layer> layers = new ArrayList<>();

    public float cellSize;
    public int nx, ny;
    public float gridX, gridY;
    public Cell[] cells;

    public final List<Poly> polys = new ArrayList<>();
    public final List<String> xrefs = new ArrayList<>();
    public String geoSystem = "";

    public static final class Layer {
        public String name;
        public String xref;
        public int color;
        public boolean visible;
        public boolean offInDwg;
        public int count;
    }

    public static final class Chunk {
        public int layer, color;
        public float[] seg;   // x1,y1,x2,y2 ...
        public float[] len;   // longueur de chaque segment, décroissante
    }

    public static final class Text {
        public int layer, color;
        public float x, y, h, ang;
        public byte ha, va;
        public String s;
    }

    public static final class Cell {
        public Chunk[] chunks;
        public Text[] texts; // hauteur décroissante
    }

    public static final class Poly {
        public int layer, color;
        public boolean solid;
        public float[][] rings;
        public float minx, miny, maxx, maxy, size;
    }

    public int segmentCount() {
        int n = 0;
        for (Cell c : cells) if (c != null && c.chunks != null) for (Chunk k : c.chunks) n += k.len.length;
        return n;
    }

    public int textCount() {
        int n = 0;
        for (Cell c : cells) if (c != null && c.texts != null) n += c.texts.length;
        return n;
    }

    // ------------------------------------------------------------------ enregistrement

    public void write(OutputStream os) throws IOException {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(os, 1 << 16));
        o.writeInt(0x45544B50); // "ETKP"
        o.writeInt(FORMAT);
        o.writeUTF(name);
        o.writeUTF(geoSystem);
        o.writeDouble(originMx); o.writeDouble(originMy);
        o.writeDouble(originX); o.writeDouble(originY);
        o.writeFloat(minX); o.writeFloat(minY); o.writeFloat(maxX); o.writeFloat(maxY);
        o.writeInt(xrefs.size());
        for (String s : xrefs) o.writeUTF(s);
        o.writeInt(layers.size());
        for (Layer l : layers) {
            o.writeUTF(l.name);
            o.writeUTF(l.xref == null ? "" : l.xref);
            o.writeInt(l.color);
            o.writeBoolean(l.visible);
            o.writeBoolean(l.offInDwg);
            o.writeInt(l.count);
        }
        o.writeFloat(cellSize); o.writeInt(nx); o.writeInt(ny); o.writeFloat(gridX); o.writeFloat(gridY);
        for (Cell c : cells) {
            if (c == null) { o.writeBoolean(false); continue; }
            o.writeBoolean(true);
            Chunk[] ch = c.chunks == null ? new Chunk[0] : c.chunks;
            o.writeInt(ch.length);
            for (Chunk k : ch) {
                o.writeInt(k.layer); o.writeInt(k.color);
                o.writeInt(k.len.length);
                for (float f : k.seg) o.writeFloat(f);
                for (float f : k.len) o.writeFloat(f);
            }
            Text[] tx = c.texts == null ? new Text[0] : c.texts;
            o.writeInt(tx.length);
            for (Text t : tx) {
                o.writeInt(t.layer); o.writeInt(t.color);
                o.writeFloat(t.x); o.writeFloat(t.y); o.writeFloat(t.h); o.writeFloat(t.ang);
                o.writeByte(t.ha); o.writeByte(t.va);
                o.writeUTF(t.s);
            }
        }
        o.writeInt(polys.size());
        for (Poly p : polys) {
            o.writeInt(p.layer); o.writeInt(p.color); o.writeBoolean(p.solid);
            o.writeFloat(p.minx); o.writeFloat(p.miny); o.writeFloat(p.maxx); o.writeFloat(p.maxy); o.writeFloat(p.size);
            o.writeInt(p.rings.length);
            for (float[] r : p.rings) {
                o.writeInt(r.length);
                for (float f : r) o.writeFloat(f);
            }
        }
        o.flush();
    }

    public static PlanData read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(is, 1 << 16));
        if (in.readInt() != 0x45544B50) throw new IOException("Fichier de plan invalide");
        if (in.readInt() != FORMAT) throw new IOException("Format de plan ancien : réimporter le DXF");
        PlanData p = new PlanData();
        p.name = in.readUTF();
        p.geoSystem = in.readUTF();
        p.originMx = in.readDouble(); p.originMy = in.readDouble();
        p.originX = in.readDouble(); p.originY = in.readDouble();
        p.minX = in.readFloat(); p.minY = in.readFloat(); p.maxX = in.readFloat(); p.maxY = in.readFloat();
        int nxr = in.readInt();
        for (int i = 0; i < nxr; i++) p.xrefs.add(in.readUTF());
        int nl = in.readInt();
        for (int i = 0; i < nl; i++) {
            Layer l = new Layer();
            l.name = in.readUTF();
            String x = in.readUTF();
            l.xref = x.isEmpty() ? null : x;
            l.color = in.readInt();
            l.visible = in.readBoolean();
            l.offInDwg = in.readBoolean();
            l.count = in.readInt();
            p.layers.add(l);
        }
        p.cellSize = in.readFloat(); p.nx = in.readInt(); p.ny = in.readInt(); p.gridX = in.readFloat(); p.gridY = in.readFloat();
        p.cells = new Cell[p.nx * p.ny];
        for (int ci = 0; ci < p.cells.length; ci++) {
            if (!in.readBoolean()) continue;
            Cell c = new Cell();
            int nch = in.readInt();
            c.chunks = new Chunk[nch];
            for (int k = 0; k < nch; k++) {
                Chunk ch = new Chunk();
                ch.layer = in.readInt(); ch.color = in.readInt();
                int n = in.readInt();
                ch.seg = new float[4 * n];
                ch.len = new float[n];
                for (int j = 0; j < 4 * n; j++) ch.seg[j] = in.readFloat();
                for (int j = 0; j < n; j++) ch.len[j] = in.readFloat();
                c.chunks[k] = ch;
            }
            int nt = in.readInt();
            c.texts = new Text[nt];
            for (int k = 0; k < nt; k++) {
                Text t = new Text();
                t.layer = in.readInt(); t.color = in.readInt();
                t.x = in.readFloat(); t.y = in.readFloat(); t.h = in.readFloat(); t.ang = in.readFloat();
                t.ha = in.readByte(); t.va = in.readByte();
                t.s = in.readUTF();
                c.texts[k] = t;
            }
            p.cells[ci] = c;
        }
        int np = in.readInt();
        for (int i = 0; i < np; i++) {
            Poly q = new Poly();
            q.layer = in.readInt(); q.color = in.readInt(); q.solid = in.readBoolean();
            q.minx = in.readFloat(); q.miny = in.readFloat(); q.maxx = in.readFloat(); q.maxy = in.readFloat(); q.size = in.readFloat();
            int nr = in.readInt();
            q.rings = new float[nr][];
            for (int r = 0; r < nr; r++) {
                int n = in.readInt();
                float[] a = new float[n];
                for (int j = 0; j < n; j++) a[j] = in.readFloat();
                q.rings[r] = a;
            }
            p.polys.add(q);
        }
        return p;
    }

    /** Coordonnées locales → Lambert NC. */
    public void localToLambert(double lx, double ly, double[] out) {
        double mx = originMx + lx, my = originMy + ly;
        double lon = LambertNC.mercXToLon(mx), lat = LambertNC.mercYToLat(my);
        LambertNC.fromLonLat(lon, lat, out);
    }
}
