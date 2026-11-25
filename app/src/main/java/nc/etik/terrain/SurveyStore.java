package nc.etik.terrain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dossiers de levé d'un plan : chaque dossier regroupe des points (code, note,
 * photos) relevés lors d'une visite ou pour un usage (repérage, suivi…).
 * Enregistré dans files/releves/&lt;plan&gt;/releves.json, photos dans photos/.
 */
final class SurveyStore {

    static final int[] COLORS = {0xFFFF8C00, 0xFFE040FB, 0xFFFFEB3B, 0xFF00E5FF, 0xFFFF1744, 0xFF76FF03, 0xFF2979FF, 0xFFFFFFFF};

    static final String[] CODES = {
            "Désordre / réserve", "Réseau EP", "Réseau EU", "Réseau AEP", "Électricité / télécom",
            "Ouvrage", "Point libre"};

    static final class Point {
        String id;
        int num;
        double x, y, z = Double.NaN, lat, lon;
        float acc = -1;
        String source = "GPS"; // GPS ou Plan
        String code = "Point libre";
        String note = "";
        long date;
        final List<String> photos = new ArrayList<>();
    }

    static final class Dossier {
        String id, nom;
        int color;
        boolean visible = true;
        long date;
        int nextNum = 1;
        final List<Point> points = new ArrayList<>();
    }

    final String planId;
    final File dir, photoDir;
    final List<Dossier> dossiers = new ArrayList<>();
    String activeId;

    SurveyStore(File root, String planId) {
        this.planId = planId;
        dir = new File(root, planId);
        photoDir = new File(dir, "photos");
        photoDir.mkdirs();
        load();
    }

    Dossier active() {
        for (Dossier d : dossiers) if (d.id.equals(activeId)) return d;
        return dossiers.isEmpty() ? null : dossiers.get(dossiers.size() - 1);
    }

    Dossier byId(String id) {
        for (Dossier d : dossiers) if (d.id.equals(id)) return d;
        return null;
    }

    Dossier create(String nom) {
        Dossier d = new Dossier();
        d.id = Long.toString(System.currentTimeMillis(), 36);
        d.nom = nom;
        d.date = System.currentTimeMillis();
        d.color = COLORS[dossiers.size() % COLORS.length];
        dossiers.add(d);
        activeId = d.id;
        save();
        return d;
    }

    void delete(Dossier d) {
        for (Point p : d.points) for (String ph : p.photos) new File(photoDir, ph).delete();
        dossiers.remove(d);
        if (d.id.equals(activeId)) activeId = dossiers.isEmpty() ? null : dossiers.get(dossiers.size() - 1).id;
        save();
    }

    Point newPoint(Dossier d) {
        Point p = new Point();
        p.id = Long.toString(System.nanoTime(), 36);
        p.num = d.nextNum++;
        p.date = System.currentTimeMillis();
        return p;
    }

    void deletePoint(Dossier d, Point p) {
        for (String ph : p.photos) new File(photoDir, ph).delete();
        d.points.remove(p);
        save();
    }

    File photoFile(String name) {
        return new File(photoDir, name);
    }

    /** Nom de fichier pour une nouvelle photo d'un point. */
    String newPhotoName(Dossier d, Point p) {
        return d.id + "_P" + p.num + "_" + Long.toString(System.currentTimeMillis(), 36) + ".jpg";
    }

    // ------------------------------------------------------------------ JSON

    private File file() {
        return new File(dir, "releves.json");
    }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONObject o = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            activeId = o.optString("actif", null);
            JSONArray ds = o.getJSONArray("dossiers");
            for (int i = 0; i < ds.length(); i++) {
                JSONObject jd = ds.getJSONObject(i);
                Dossier d = new Dossier();
                d.id = jd.getString("id");
                d.nom = jd.getString("nom");
                d.color = jd.optInt("couleur", COLORS[i % COLORS.length]);
                d.visible = jd.optBoolean("visible", true);
                d.date = jd.optLong("date");
                d.nextNum = jd.optInt("prochainNum", 1);
                JSONArray pts = jd.getJSONArray("points");
                for (int k = 0; k < pts.length(); k++) {
                    JSONObject jp = pts.getJSONObject(k);
                    Point p = new Point();
                    p.id = jp.getString("id");
                    p.num = jp.getInt("num");
                    p.x = jp.getDouble("x");
                    p.y = jp.getDouble("y");
                    p.z = jp.has("z") ? jp.getDouble("z") : Double.NaN;
                    p.lat = jp.optDouble("lat", 0);
                    p.lon = jp.optDouble("lon", 0);
                    p.acc = (float) jp.optDouble("precision", -1);
                    p.source = jp.optString("source", "GPS");
                    p.code = jp.optString("code", "Point libre");
                    p.note = jp.optString("note", "");
                    p.date = jp.optLong("date");
                    JSONArray ph = jp.optJSONArray("photos");
                    if (ph != null) for (int j = 0; j < ph.length(); j++) p.photos.add(ph.getString(j));
                    d.points.add(p);
                }
                dossiers.add(d);
            }
        } catch (Exception e) {
            // fichier illisible : on le garde de côté pour ne rien perdre
            f.renameTo(new File(dir, "releves_illisible_" + System.currentTimeMillis() + ".json"));
        }
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("format", 1);
            o.put("plan", planId);
            if (activeId != null) o.put("actif", activeId);
            JSONArray ds = new JSONArray();
            for (Dossier d : dossiers) {
                JSONObject jd = new JSONObject();
                jd.put("id", d.id).put("nom", d.nom).put("couleur", d.color).put("visible", d.visible)
                        .put("date", d.date).put("prochainNum", d.nextNum);
                JSONArray pts = new JSONArray();
                for (Point p : d.points) {
                    JSONObject jp = new JSONObject();
                    jp.put("id", p.id).put("num", p.num).put("x", p.x).put("y", p.y);
                    if (!Double.isNaN(p.z)) jp.put("z", p.z);
                    jp.put("lat", p.lat).put("lon", p.lon).put("precision", (double) p.acc)
                            .put("source", p.source).put("code", p.code).put("note", p.note).put("date", p.date);
                    JSONArray ph = new JSONArray();
                    for (String s : p.photos) ph.put(s);
                    jp.put("photos", ph);
                    pts.put(jp);
                }
                jd.put("points", pts);
                ds.put(jd);
            }
            o.put("dossiers", ds);
            File tmp = new File(dir, "releves.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            File f = file();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
        }
    }
}
