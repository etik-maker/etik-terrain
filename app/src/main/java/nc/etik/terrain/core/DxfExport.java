package nc.etik.terrain.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Export des levés : DXF (format R12, ouvert par toutes les versions d'AutoCAD)
 * en coordonnées Lambert NC, CSV des points et photos, dans un ZIP.
 * Calques par dossier : RELEVE_&lt;DOSSIER&gt;_PT (points), _PH (photos), _TR (traces).
 */
public final class DxfExport {

    public static final class Pt {
        public String dossier;
        public int num;
        public double x, y, z = Double.NaN;
        public float acc = -1;
        public String source = "", code = "", note = "";
        public long date;
        public final List<File> photos = new ArrayList<>();
        /** Noms des photos dans le ZIP (remplis à l'export). */
        final List<String> photoNames = new ArrayList<>();
    }

    public static final class Tr {
        public String dossier, nom;
        public long debut;
        public final List<double[]> xyz = new ArrayList<>(); // x, y, z (NaN si inconnu)
    }

    public static final class Dossier {
        public String nom;
        public int color; // 0xRRGGBB
    }

    public final List<Dossier> dossiers = new ArrayList<>();
    public final List<Pt> points = new ArrayList<>();
    public final List<Tr> traces = new ArrayList<>();
    public String planName = "";
    /** Taille du symbole de point (m) et hauteur des textes (m). */
    public double symbol = 0.5, textHeight = 1.0;

    // ------------------------------------------------------------------ noms

    /** Nom compatible DXF R12 : majuscules, chiffres, _ et -, 31 caractères au plus. */
    static String sanitize(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
        return n.isEmpty() ? "SANS_NOM" : n;
    }

    /** Nom de fichier sûr (lettres, chiffres, _ et -), casse conservée. */
    public static String sanitizeFileName(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return n.isEmpty() ? "plan" : n;
    }

    private final Map<String, String> layerBase = new LinkedHashMap<>();

    private String base(String dossier) {
        String b = layerBase.get(dossier);
        if (b != null) return b;
        String s = sanitize(dossier);
        if (s.length() > 21) s = s.substring(0, 21);
        String cand = "RELEVE_" + s;
        int k = 2;
        while (layerBase.containsValue(cand)) cand = "RELEVE_" + s.substring(0, Math.min(s.length(), 18)) + "_" + (k++);
        layerBase.put(dossier, cand);
        return cand;
    }

    /** Code ACI le plus proche d'une couleur RGB (le R12 ne connaît pas les couleurs vraies). */
    static int nearestAci(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 7;
        long bd = Long.MAX_VALUE;
        for (int i = 1; i < 255; i++) {
            int c = AciColors.RGB[i];
            long dr = r - ((c >> 16) & 0xFF), dg = g - ((c >> 8) & 0xFF), db = b - (c & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Texte compatible DXF : accents en \U+XXXX, pas de saut de ligne. */
    static String txt(String s) {
        if (s == null) return "";
        s = s.replace("\r", "").replace("\n", " / ").trim();
        if (s.length() > 240) s = s.substring(0, 240) + "…";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) sb.append(c);
            else sb.append(String.format(Locale.ROOT, "\\U+%04X", (int) c));
        }
        return sb.toString();
    }

    private static String f(double v) {
        if (Double.isNaN(v)) v = 0;
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // ------------------------------------------------------------------ DXF

    private final StringBuilder out = new StringBuilder(1 << 16);

    private void g(int code, String value) {
        out.append(code < 10 ? "  " : code < 100 ? " " : "").append(code).append("\r\n").append(value).append("\r\n");
    }

    private void g(int code, double v) {
        g(code, f(v));
    }

    private void g(int code, int v) {
        g(code, Integer.toString(v));
    }

    public String buildDxf() {
        out.setLength(0);
        // nommage des photos (avant l'écriture des attributs)
        for (Pt p : points) {
            p.photoNames.clear();
            for (int i = 0; i < p.photos.size(); i++)
                p.photoNames.add(sanitize(p.dossier) + "_P" + String.format(Locale.ROOT, "%03d", p.num) + "_" + (i + 1) + ".jpg");
        }
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (Pt p : points) { minx = Math.min(minx, p.x); maxx = Math.max(maxx, p.x); miny = Math.min(miny, p.y); maxy = Math.max(maxy, p.y); }
        for (Tr t : traces) for (double[] q : t.xyz) { minx = Math.min(minx, q[0]); maxx = Math.max(maxx, q[0]); miny = Math.min(miny, q[1]); maxy = Math.max(maxy, q[1]); }
        if (minx > maxx) { minx = miny = 0; maxx = maxy = 1; }

        // HEADER
        g(0, "SECTION"); g(2, "HEADER");
        g(9, "$ACADVER"); g(1, "AC1009");
        g(9, "$DWGCODEPAGE"); g(3, "ANSI_1252");
        g(9, "$INSBASE"); g(10, 0.0); g(20, 0.0); g(30, 0.0);
        g(9, "$EXTMIN"); g(10, minx); g(20, miny); g(30, 0.0);
        g(9, "$EXTMAX"); g(10, maxx); g(20, maxy); g(30, 0.0);
        g(9, "$LIMMIN"); g(10, minx); g(20, miny);
        g(9, "$LIMMAX"); g(10, maxx); g(20, maxy);
        g(0, "ENDSEC");

        // TABLES
        g(0, "SECTION"); g(2, "TABLES");
        g(0, "TABLE"); g(2, "LTYPE"); g(70, 1);
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, 0); g(3, "Solid line"); g(72, 65); g(73, 0); g(40, 0.0);
        g(0, "ENDTAB");
        List<String[]> layers = new ArrayList<>(); // nom, aci
        layers.add(new String[]{"0", "7"});
        for (Dossier d : dossiers) {
            String b = base(d.nom);
            String aci = Integer.toString(nearestAci(d.color));
            layers.add(new String[]{b + "_PT", aci});
            layers.add(new String[]{b + "_PH", aci});
            layers.add(new String[]{b + "_TR", aci});
        }
        g(0, "TABLE"); g(2, "LAYER"); g(70, layers.size());
        for (String[] l : layers) {
            g(0, "LAYER"); g(2, l[0]); g(70, 0); g(62, Integer.parseInt(l[1])); g(6, "CONTINUOUS");
        }
        g(0, "ENDTAB");
        g(0, "TABLE"); g(2, "STYLE"); g(70, 1);
        g(0, "STYLE"); g(2, "STANDARD"); g(70, 0); g(40, 0.0); g(41, 1.0); g(50, 0.0); g(71, 0); g(42, 2.5); g(3, "txt"); g(4, "");
        g(0, "ENDTAB");
        g(0, "ENDSEC");

        // BLOCKS : symbole de point avec attributs
        double r = symbol, h = textHeight;
        g(0, "SECTION"); g(2, "BLOCKS");
        g(0, "BLOCK"); g(8, "0"); g(2, "RELEVE_POINT"); g(70, 2); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(3, "RELEVE_POINT");
        g(0, "CIRCLE"); g(8, "0"); g(62, 0); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(40, r);
        g(0, "LINE"); g(8, "0"); g(62, 0); g(10, -r * 1.6); g(20, 0.0); g(30, 0.0); g(11, r * 1.6); g(21, 0.0); g(31, 0.0);
        g(0, "LINE"); g(8, "0"); g(62, 0); g(10, 0.0); g(20, -r * 1.6); g(30, 0.0); g(11, 0.0); g(21, r * 1.6); g(31, 0.0);
        String[][] atts = {{"NUM", "Numero", "0"}, {"CODE", "Code", "1"}, {"NOTE", "Note", "1"}, {"DATE", "Date", "1"},
                {"Z", "Altitude GPS", "1"}, {"PRECISION", "Precision (m)", "1"}, {"SOURCE", "Source", "1"},
                {"DOSSIER", "Dossier", "1"}, {"PHOTOS", "Photos", "1"}};
        for (int i = 0; i < atts.length; i++) {
            g(0, "ATTDEF"); g(8, "0"); g(62, 0);
            g(10, r * 1.8); g(20, i == 0 ? r * 0.3 : -h * 1.4 * i); g(30, 0.0);
            g(40, i == 0 ? h : h * 0.6); g(1, ""); g(3, atts[i][1]); g(2, atts[i][0]); g(70, Integer.parseInt(atts[i][2]));
        }
        g(0, "ENDBLK"); g(8, "0");
        // pictogramme photo
        g(0, "BLOCK"); g(8, "0"); g(2, "RELEVE_PHOTO"); g(70, 0); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(3, "RELEVE_PHOTO");
        double s = r * 1.2;
        polyline2d(new double[]{-s, -s * 0.7, s, -s * 0.7, s, s * 0.7, -s, s * 0.7}, true, "0", 0);
        g(0, "CIRCLE"); g(8, "0"); g(62, 0); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(40, s * 0.45);
        g(0, "ENDBLK"); g(8, "0");
        g(0, "ENDSEC");

        // ENTITIES
        g(0, "SECTION"); g(2, "ENTITIES");
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        for (Pt p : points) {
            String lay = base(p.dossier) + "_PT";
            g(0, "INSERT"); g(8, lay); g(66, 1); g(2, "RELEVE_POINT");
            g(10, p.x); g(20, p.y); g(30, Double.isNaN(p.z) ? 0 : p.z);
            String[] vals = {Integer.toString(p.num), p.code, p.note, df.format(new Date(p.date)),
                    Double.isNaN(p.z) ? "" : String.format(Locale.ROOT, "%.2f", p.z),
                    p.acc < 0 ? "" : String.format(Locale.ROOT, "%.1f", p.acc), p.source, p.dossier,
                    String.join(", ", p.photoNames)};
            for (int i = 0; i < atts.length; i++) {
                g(0, "ATTRIB"); g(8, lay);
                g(10, p.x + r * 1.8); g(20, p.y + (i == 0 ? r * 0.3 : -h * 1.4 * i)); g(30, Double.isNaN(p.z) ? 0 : p.z);
                g(40, i == 0 ? h : h * 0.6); g(1, txt(vals[i])); g(2, atts[i][0]); g(70, Integer.parseInt(atts[i][2]));
            }
            g(0, "SEQEND"); g(8, lay);
            // photos : pictogramme décalé, ligne de repère vers le point, nom du fichier
            String lph = base(p.dossier) + "_PH";
            for (int i = 0; i < p.photoNames.size(); i++) {
                double ox = p.x - h * 4, oy = p.y + h * 3 + i * h * 2.5;
                g(0, "LINE"); g(8, lph); g(10, p.x); g(20, p.y); g(30, 0.0); g(11, ox); g(21, oy); g(31, 0.0);
                g(0, "INSERT"); g(8, lph); g(2, "RELEVE_PHOTO"); g(10, ox); g(20, oy); g(30, 0.0);
                g(0, "TEXT"); g(8, lph); g(10, ox - s - h * 0.3); g(20, oy - h * 0.3); g(30, 0.0); g(40, h * 0.6);
                g(1, txt(p.photoNames.get(i))); g(72, 2); g(11, ox - s - h * 0.3); g(21, oy - h * 0.3); g(31, 0.0);
            }
        }
        for (Tr t : traces) {
            if (t.xyz.size() < 2) continue;
            String lay = base(t.dossier) + "_TR";
            boolean has3d = true;
            for (double[] q : t.xyz) if (Double.isNaN(q[2])) { has3d = false; break; }
            g(0, "POLYLINE"); g(8, lay); g(66, 1); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(70, has3d ? 8 : 0);
            for (double[] q : t.xyz) {
                g(0, "VERTEX"); g(8, lay); g(10, q[0]); g(20, q[1]); g(30, has3d ? q[2] : 0); g(70, has3d ? 32 : 0);
            }
            g(0, "SEQEND"); g(8, lay);
            double[] a = t.xyz.get(0);
            g(0, "TEXT"); g(8, lay); g(10, a[0] + h); g(20, a[1] + h); g(30, 0.0); g(40, h * 0.8); g(1, txt(t.nom));
        }
        g(0, "ENDSEC");
        g(0, "EOF");
        return out.toString();
    }

    private void polyline2d(double[] xy, boolean closed, String layer, int color) {
        g(0, "POLYLINE"); g(8, layer); g(62, color); g(66, 1); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(70, closed ? 1 : 0);
        for (int i = 0; i < xy.length; i += 2) {
            g(0, "VERTEX"); g(8, layer); g(62, color); g(10, xy[i]); g(20, xy[i + 1]); g(30, 0.0);
        }
        g(0, "SEQEND"); g(8, layer);
    }

    // ------------------------------------------------------------------ CSV

    public String buildCsv() {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Dossier;Num;X;Y;Z;Precision_m;Source;Code;Note;Date;Photos\r\n");
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        for (Pt p : points) {
            sb.append(csv(p.dossier)).append(';').append(p.num).append(';')
                    .append(String.format(Locale.FRANCE, "%.3f;%.3f;", p.x, p.y))
                    .append(Double.isNaN(p.z) ? "" : String.format(Locale.FRANCE, "%.2f", p.z)).append(';')
                    .append(p.acc < 0 ? "" : String.format(Locale.FRANCE, "%.1f", p.acc)).append(';')
                    .append(csv(p.source)).append(';').append(csv(p.code)).append(';').append(csv(p.note)).append(';')
                    .append(df.format(new Date(p.date))).append(';').append(csv(String.join(", ", p.photoNames))).append("\r\n");
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ------------------------------------------------------------------ ZIP

    /** Écrit le ZIP complet. withPhotos : inclure les fichiers JPEG. */
    public void writeZip(OutputStream os, String folder, boolean withPhotos) throws IOException {
        String dxf = buildDxf(); // nomme aussi les photos
        String csvText = buildCsv();
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry(folder + "/Releves.dxf"));
            z.write(dxf.getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(folder + "/Points.csv"));
            z.write(csvText.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            if (withPhotos) {
                byte[] buf = new byte[65536];
                for (Pt p : points) {
                    for (int i = 0; i < p.photos.size(); i++) {
                        File f = p.photos.get(i);
                        if (!f.exists()) continue;
                        z.putNextEntry(new ZipEntry(folder + "/Photos/" + p.photoNames.get(i)));
                        try (InputStream in = new FileInputStream(f)) {
                            int n;
                            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                        }
                        z.closeEntry();
                    }
                }
            }
            z.putNextEntry(new ZipEntry(folder + "/LISEZMOI.txt"));
            z.write(readme(withPhotos).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    private String readme(boolean withPhotos) {
        StringBuilder sb = new StringBuilder();
        sb.append("Export ETIK Terrain – ").append(planName).append("\r\n");
        sb.append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date())).append("\r\n\r\n");
        sb.append("Releves.dxf : coordonnées RGNC91-93 / Lambert Nouvelle-Calédonie (EPSG:3163), en mètres.\r\n");
        sb.append("  Insérer en XREF ou copier dans le plan : les levés tombent à leur place sans recalage.\r\n");
        sb.append("  Calques par dossier : _PT points (bloc RELEVE_POINT avec attributs NUM, CODE, NOTE, DATE, Z, PRECISION, SOURCE, DOSSIER, PHOTOS),\r\n");
        sb.append("  _PH repères photo et noms de fichiers, _TR traces GPS (polylignes 3D quand l'altitude est connue).\r\n");
        sb.append("  Les attributs se récupèrent avec EXTRACTDONNEES (DATAEXTRACTION).\r\n");
        sb.append("Points.csv : tableau des points (séparateur ;) pour Excel.\r\n");
        if (withPhotos) sb.append("Photos : nommées DOSSIER_Pnnn_k.jpg (nnn = numéro du point).\r\n");
        sb.append("\r\nAltitudes : altitude GPS du téléphone, indicative (précision de plusieurs mètres).\r\n");
        sb.append("Dossiers exportés :\r\n");
        for (Dossier d : dossiers) sb.append("  ").append(d.nom).append(" -> calques ").append(base(d.nom)).append("_PT/_PH/_TR\r\n");
        return sb.toString();
    }
}
