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
 * Export des levés : DXF (format AutoCAD 2000, photos en images liées)
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
        if (s.length() > 40) s = s.substring(0, 40);
        String cand = "RELEVE_" + s;
        int k = 2;
        while (layerBase.containsValue(cand)) cand = "RELEVE_" + s + "_" + (k++);
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

    static final java.nio.charset.Charset CP1252 = java.nio.charset.Charset.forName("windows-1252");

    /** Texte compatible DXF (code page ANSI_1252) : caractères hors code page en \U+XXXX, pas de saut de ligne. */
    static String txt(String s) {
        if (s == null) return "";
        s = s.replace("\r", "").replace("\n", " / ").trim();
        if (s.length() > 240) s = s.substring(0, 240) + "…";
        StringBuilder sb = new StringBuilder();
        java.nio.charset.CharsetEncoder enc = CP1252.newEncoder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && (c < 128 || enc.canEncode(c))) sb.append(c);
            else if (c >= 32) sb.append(String.format(Locale.ROOT, "\\U+%04X", (int) c));
        }
        return sb.toString();
    }

    private static String f(double v) {
        if (Double.isNaN(v)) v = 0;
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // ------------------------------------------------------------------ DXF (AutoCAD 2000)

    /** Modèle DXF R2000 (assets/modele_r2000.dxf) avec ses marqueurs @@…@@. */
    public String template;
    /** Largeur des photos dans le dessin (m). */
    public double photoWidth = 6.0;

    private final StringBuilder out = new StringBuilder(1 << 16);
    private long nextHandle = 0x400;

    private String h() {
        return Long.toHexString(nextHandle++).toUpperCase(Locale.ROOT);
    }

    private void g(int code, String value) {
        String c = Integer.toString(code);
        for (int i = c.length(); i < 3; i++) out.append(' ');
        out.append(c).append('\n').append(value).append('\n');
    }

    private void g(int code, double v) {
        g(code, f(v));
    }

    private void g(int code, int v) {
        g(code, Integer.toString(v));
    }

    private String take() {
        String s = out.toString();
        out.setLength(0);
        return s;
    }

    /** Dimensions en pixels d'un JPEG (lecture de l'en-tête SOF), ou null. */
    static int[] jpegSize(File f) {
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
            if (in.read() != 0xFF || in.read() != 0xD8) return null;
            while (true) {
                int b = in.read();
                while (b != 0xFF) { if (b < 0) return null; b = in.read(); }
                int marker = in.read();
                while (marker == 0xFF) marker = in.read();
                if (marker < 0) return null;
                if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) continue;
                int len = (in.read() << 8) | in.read();
                if (len < 2) return null;
                boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) {
                    in.read(); // précision
                    int hgt = (in.read() << 8) | in.read();
                    int wid = (in.read() << 8) | in.read();
                    return new int[]{wid, hgt};
                }
                long skip = len - 2;
                while (skip > 0) {
                    long k = in.skip(skip);
                    if (k <= 0) return null;
                    skip -= k;
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    public String buildDxf() {
        if (template == null) throw new IllegalStateException("modèle DXF manquant");
        String[] lines0 = template.split("\n", 2);
        Map<String, String> th = new LinkedHashMap<>();
        for (String kv : lines0[0].split(" ")) {
            int e = kv.indexOf('=');
            if (e > 0) th.put(kv.substring(0, e), kv.substring(e + 1));
        }
        String tpl = lines0[1];
        String MS = th.get("MS"), ROOT = th.get("ROOT"), LT = th.get("LAYERTABLE"), BRT = th.get("BRTABLE"), PS = th.get("PLOTSTYLE");
        nextHandle = 0x400;

        // nommage des photos et dimensions
        for (Pt p : points) {
            p.photoNames.clear();
            for (int i = 0; i < p.photos.size(); i++)
                p.photoNames.add(sanitize(p.dossier) + "_P" + String.format(Locale.ROOT, "%03d", p.num) + "_" + (i + 1) + ".jpg");
        }
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (Pt p : points) { minx = Math.min(minx, p.x); maxx = Math.max(maxx, p.x); miny = Math.min(miny, p.y); maxy = Math.max(maxy, p.y); }
        for (Tr t : traces) for (double[] q : t.xyz) { minx = Math.min(minx, q[0]); maxx = Math.max(maxx, q[0]); miny = Math.min(miny, q[1]); maxy = Math.max(maxy, q[1]); }
        if (minx > maxx) { minx = miny = 0; maxx = maxy = 1; }
        double r = symbol, h = textHeight;

        // ---- calques
        for (Dossier d : dossiers) {
            String b = base(d.nom);
            int aci = nearestAci(d.color);
            for (String suf : new String[]{"_PT", "_PH", "_TR"}) {
                g(0, "LAYER"); g(5, h()); g(330, LT); g(100, "AcDbSymbolTableRecord"); g(100, "AcDbLayerTableRecord");
                g(2, b + suf); g(70, 0); g(62, aci); g(6, "Continuous"); g(370, -3); g(390, PS);
            }
        }
        String layers = take();

        // ---- bloc RELEVE_POINT : symbole, numéro et note visibles, autres attributs cachés
        String hBR = h();
        g(0, "BLOCK_RECORD"); g(5, hBR); g(330, BRT); g(100, "AcDbSymbolTableRecord"); g(100, "AcDbBlockTableRecord");
        g(2, "RELEVE_POINT"); g(340, "0");
        String blockRecords = take();

        String[][] atts = {{"NUM", "Numero", "0"}, {"NOTE", "Note", "0"}, {"CODE", "Code", "1"}, {"DATE", "Date", "1"},
                {"Z", "Altitude GPS", "1"}, {"PRECISION", "Precision (m)", "1"}, {"SOURCE", "Source", "1"},
                {"DOSSIER", "Dossier", "1"}, {"PHOTOS", "Photos", "1"}};
        double[][] attPos = new double[atts.length][3]; // x, y, hauteur
        for (int i = 0; i < atts.length; i++) {
            if (i == 0) attPos[i] = new double[]{r * 1.8, r * 0.3, h};
            else if (i == 1) attPos[i] = new double[]{r * 1.8, -h * 1.1, h * 0.7};
            else attPos[i] = new double[]{r * 1.8, -h * 1.1 - h * 1.1 * i, h * 0.6};
        }
        g(0, "BLOCK"); g(5, h()); g(330, hBR); g(100, "AcDbEntity"); g(8, "0"); g(100, "AcDbBlockBegin");
        g(2, "RELEVE_POINT"); g(70, 2); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(3, "RELEVE_POINT"); g(1, "");
        g(0, "CIRCLE"); g(5, h()); g(330, hBR); g(100, "AcDbEntity"); g(8, "0"); g(62, 0); g(100, "AcDbCircle");
        g(10, 0.0); g(20, 0.0); g(30, 0.0); g(40, r);
        for (int k = 0; k < 2; k++) {
            g(0, "LINE"); g(5, h()); g(330, hBR); g(100, "AcDbEntity"); g(8, "0"); g(62, 0); g(100, "AcDbLine");
            if (k == 0) { g(10, -r * 1.6); g(20, 0.0); g(30, 0.0); g(11, r * 1.6); g(21, 0.0); g(31, 0.0); }
            else { g(10, 0.0); g(20, -r * 1.6); g(30, 0.0); g(11, 0.0); g(21, r * 1.6); g(31, 0.0); }
        }
        for (int i = 0; i < atts.length; i++) {
            g(0, "ATTDEF"); g(5, h()); g(330, hBR); g(100, "AcDbEntity"); g(8, "0"); g(62, 0); g(100, "AcDbText");
            g(10, attPos[i][0]); g(20, attPos[i][1]); g(30, 0.0); g(40, attPos[i][2]); g(1, "");
            g(100, "AcDbAttributeDefinition"); g(3, atts[i][1]); g(2, atts[i][0]); g(70, Integer.parseInt(atts[i][2]));
        }
        g(0, "ENDBLK"); g(5, h()); g(330, hBR); g(100, "AcDbEntity"); g(8, "0"); g(100, "AcDbBlockEnd");
        String blocks = take();

        // ---- entités
        StringBuilder objs = new StringBuilder();
        StringBuilder imgDict = new StringBuilder();
        String hImgDict = h(), hRasterVars = h();
        boolean anyImage = false;
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        StringBuilder ents = new StringBuilder();
        for (Pt p : points) {
            String lay = base(p.dossier) + "_PT";
            double z = Double.isNaN(p.z) ? 0 : p.z;
            String hIns = h();
            g(0, "INSERT"); g(5, hIns); g(330, MS); g(100, "AcDbEntity"); g(8, lay); g(100, "AcDbBlockReference");
            g(66, 1); g(2, "RELEVE_POINT"); g(10, p.x); g(20, p.y); g(30, z);
            String[] vals = {Integer.toString(p.num), p.note, p.code, df.format(new Date(p.date)),
                    Double.isNaN(p.z) ? "" : String.format(Locale.ROOT, "%.2f", p.z),
                    p.acc < 0 ? "" : String.format(Locale.ROOT, "%.1f", p.acc), p.source, p.dossier,
                    String.join(", ", p.photoNames)};
            for (int i = 0; i < atts.length; i++) {
                g(0, "ATTRIB"); g(5, h()); g(330, hIns); g(100, "AcDbEntity"); g(8, lay); g(100, "AcDbText");
                g(10, p.x + attPos[i][0]); g(20, p.y + attPos[i][1]); g(30, z); g(40, attPos[i][2]); g(1, txt(vals[i]));
                g(100, "AcDbAttribute"); g(2, atts[i][0]); g(70, Integer.parseInt(atts[i][2]));
            }
            g(0, "SEQEND"); g(5, h()); g(330, hIns); g(100, "AcDbEntity"); g(8, lay);
            ents.append(take());

            // photos : image liée (fichier Photos\…), ligne de repère, nom du fichier
            String lph = base(p.dossier) + "_PH";
            double cursor = p.x - r * 3;
            for (int i = 0; i < p.photoNames.size(); i++) {
                int[] px = jpegSize(p.photos.get(i));
                if (px == null) px = new int[]{1920, 1440};
                double W = photoWidth, H = photoWidth * px[1] / px[0];
                double x0 = cursor - W, y0 = p.y + r * 3;
                cursor = x0 - h;
                String hImg = h(), hDef = h(), hReact = h();
                anyImage = true;
                g(0, "IMAGE"); g(5, hImg); g(330, MS); g(100, "AcDbEntity"); g(8, lph); g(100, "AcDbRasterImage"); g(90, 0);
                g(10, x0); g(20, y0); g(30, 0.0);
                g(11, W / px[0]); g(21, 0.0); g(31, 0.0);
                g(12, 0.0); g(22, H / px[1]); g(32, 0.0);
                g(13, (double) px[0]); g(23, (double) px[1]);
                g(340, hDef); g(70, 7); g(280, 0); g(281, 50); g(282, 50); g(283, 0); g(360, hReact);
                g(71, 1); g(91, 2); g(14, -0.5); g(24, -0.5); g(14, px[0] - 0.5); g(24, px[1] - 0.5);
                // repère : du point vers le coin bas droit de la photo
                g(0, "LINE"); g(5, h()); g(330, MS); g(100, "AcDbEntity"); g(8, lph); g(100, "AcDbLine");
                g(10, p.x); g(20, p.y); g(30, 0.0); g(11, x0 + W); g(21, y0); g(31, 0.0);
                ents.append(take());
                // objets : définition d'image et réacteur
                g(0, "IMAGEDEF"); g(5, hDef); g(102, "{ACAD_REACTORS"); g(330, hImgDict); g(330, hReact); g(102, "}");
                g(330, hImgDict); g(100, "AcDbRasterImageDef"); g(90, 0);
                g(1, ".\\Photos\\" + p.photoNames.get(i)); g(10, (double) px[0]); g(20, (double) px[1]);
                g(11, 0.01); g(21, 0.01); g(280, 1); g(281, 0);
                g(0, "IMAGEDEF_REACTOR"); g(5, hReact); g(330, hImg); g(100, "AcDbRasterImageDefReactor"); g(90, 2); g(330, hImg);
                objs.append(take());
                String key = p.photoNames.get(i);
                key = key.substring(0, key.length() - 4);
                imgDict.append(String.format("  3\n%s\n350\n%s\n", key, hDef));
            }
        }
        for (Tr t : traces) {
            if (t.xyz.size() < 2) continue;
            String lay = base(t.dossier) + "_TR";
            boolean has3d = true;
            for (double[] q : t.xyz) if (Double.isNaN(q[2])) { has3d = false; break; }
            if (has3d) {
                String hp = h();
                g(0, "POLYLINE"); g(5, hp); g(330, MS); g(100, "AcDbEntity"); g(8, lay); g(100, "AcDb3dPolyline");
                g(66, 1); g(10, 0.0); g(20, 0.0); g(30, 0.0); g(70, 8);
                for (double[] q : t.xyz) {
                    g(0, "VERTEX"); g(5, h()); g(330, hp); g(100, "AcDbEntity"); g(8, lay); g(100, "AcDbVertex");
                    g(100, "AcDb3dPolylineVertex"); g(10, q[0]); g(20, q[1]); g(30, q[2]); g(70, 32);
                }
                g(0, "SEQEND"); g(5, h()); g(330, hp); g(100, "AcDbEntity"); g(8, lay);
            } else {
                g(0, "LWPOLYLINE"); g(5, h()); g(330, MS); g(100, "AcDbEntity"); g(8, lay); g(100, "AcDbPolyline");
                g(90, t.xyz.size()); g(70, 0);
                for (double[] q : t.xyz) { g(10, q[0]); g(20, q[1]); }
            }
            double[] a0 = t.xyz.get(0);
            g(0, "TEXT"); g(5, h()); g(330, MS); g(100, "AcDbEntity"); g(8, lay); g(100, "AcDbText");
            g(10, a0[0] + h); g(20, a0[1] + h); g(30, 0.0); g(40, h * 0.8); g(1, txt(t.nom)); g(100, "AcDbText");
            ents.append(take());
        }

        String classes = "", rootDict = "", objects = objs.toString();
        if (anyImage) {
            for (String[] c : new String[][]{{"IMAGE", "AcDbRasterImage", "2175", "1"}, {"IMAGEDEF", "AcDbRasterImageDef", "0", "0"},
                    {"IMAGEDEF_REACTOR", "AcDbRasterImageDefReactor", "1", "0"}}) {
                g(0, "CLASS"); g(1, c[0]); g(2, c[1]); g(3, "ISM"); g(90, c[2]); g(280, 0); g(281, c[3]);
            }
            classes = take();
            rootDict = String.format("  3\nACAD_IMAGE_DICT\n350\n%s\n  3\nACAD_IMAGE_VARS\n350\n%s\n", hImgDict, hRasterVars);
            g(0, "DICTIONARY"); g(5, hImgDict); g(330, ROOT); g(100, "AcDbDictionary"); g(281, 1);
            String head = take();
            g(0, "RASTERVARIABLES"); g(5, hRasterVars); g(102, "{ACAD_REACTORS"); g(330, ROOT); g(102, "}");
            g(330, ROOT); g(100, "AcDbRasterVariables"); g(90, 0); g(70, 0); g(71, 1); g(72, 0);
            objects = head + imgDict + take() + objects;
        }

        String res = tpl
                .replace("@@EXTMINX@@", f(minx)).replace("@@EXTMINY@@", f(miny)).replace("@@EXTMINZ@@", f(0))
                .replace("@@EXTMAXX@@", f(maxx)).replace("@@EXTMAXY@@", f(maxy)).replace("@@EXTMAXZ@@", f(0))
                .replace("@@CLASSES@@\n", classes)
                .replace("@@LAYERS@@\n", layers)
                .replace("@@BLOCK_RECORDS@@\n", blockRecords)
                .replace("@@BLOCKS@@\n", blocks)
                .replace("@@ENTITIES@@\n", ents.toString())
                .replace("@@ROOTDICT@@\n", rootDict)
                .replace("@@OBJECTS@@\n", objects);
        res = res.replace("@@HANDSEED@@", Long.toHexString(nextHandle).toUpperCase(Locale.ROOT));
        return res;
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
            z.write(dxf.getBytes(CP1252));
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
        sb.append("  Calques par dossier : _PT points (bloc RELEVE_POINT : numéro et note affichés ; attributs cachés CODE, DATE, Z,\r\n");
        sb.append("  PRECISION, SOURCE, DOSSIER, PHOTOS), _PH photos (images liées au dossier Photos, avec un repère vers le point),\r\n");
        sb.append("  _TR traces GPS (polylignes 3D quand l'altitude est connue).\r\n");
        sb.append("  Garder le dossier Photos à côté de Releves.dxf (ou du DWG qui l'insère) pour que les images s'affichent.\r\n");
        sb.append("  Les attributs se récupèrent avec EXTRACTDONNEES (DATAEXTRACTION).\r\n");
        sb.append("Points.csv : tableau des points (séparateur ;) pour Excel.\r\n");
        if (withPhotos) sb.append("Photos : nommées DOSSIER_Pnnn_k.jpg (nnn = numéro du point).\r\n");
        sb.append("\r\nAltitudes : altitude GPS du téléphone, indicative (précision de plusieurs mètres).\r\n");
        sb.append("Dossiers exportés :\r\n");
        for (Dossier d : dossiers) sb.append("  ").append(d.nom).append(" -> calques ").append(base(d.nom)).append("_PT/_PH/_TR\r\n");
        return sb.toString();
    }
}
