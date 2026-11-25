package nc.etik.terrain.core;

import java.util.ArrayList;
import java.util.List;

/** Une entité DXF brute : son type et la liste ordonnée de ses paires (code, valeur). */
public final class Ent {
    public final String type;
    int[] codes = new int[16];
    String[] vals = new String[16];
    int n;
    /** VERTEX d'une POLYLINE ou ATTRIB d'un INSERT. */
    List<Ent> children;

    Ent(String type) {
        this.type = type;
    }

    void add(int c, String v) {
        if (n == codes.length) {
            int[] nc = new int[n * 2];
            String[] nv = new String[n * 2];
            System.arraycopy(codes, 0, nc, 0, n);
            System.arraycopy(vals, 0, nv, 0, n);
            codes = nc;
            vals = nv;
        }
        codes[n] = c;
        vals[n] = v;
        n++;
    }

    void addChild(Ent e) {
        if (children == null) children = new ArrayList<>();
        children.add(e);
    }

    int indexOf(int c) {
        for (int k = 0; k < n; k++) if (codes[k] == c) return k;
        return -1;
    }

    boolean has(int c) {
        return indexOf(c) >= 0;
    }

    String s(int c, String def) {
        int k = indexOf(c);
        return k < 0 ? def : vals[k];
    }

    double d(int c, double def) {
        int k = indexOf(c);
        return k < 0 ? def : DxfTokenizer.parseDouble(vals[k]);
    }

    int i(int c, int def) {
        int k = indexOf(c);
        return k < 0 ? def : DxfTokenizer.parseInt(vals[k]);
    }

    String layer() {
        return s(8, "0");
    }
}
