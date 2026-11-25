package nc.etik.terrain;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nc.etik.terrain.core.DxfParser;
import nc.etik.terrain.core.PlanBuilder;
import nc.etik.terrain.core.PlanData;

/** Import des DXF, cache des plans convertis, visibilité des calques, plans récents. */
final class PlanStore {

    interface ProgressListener {
        void onProgress(String step, int percent);
    }

    static final class Entry {
        String id, name;
        long date;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File dir;

    PlanStore(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("plans", Context.MODE_PRIVATE);
        dir = new File(ctx.getFilesDir(), "plans");
        if (!dir.exists()) dir.mkdirs();
    }

    static String displayName(ContentResolver cr, Uri uri) {
        String name = null;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "plan.dxf" : name;
    }

    static long size(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Importe un DXF (copie locale, lecture, conversion, enregistrement). À appeler hors du fil principal. */
    PlanData importDxf(Uri uri, ProgressListener pl) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String name = displayName(cr, uri);
        // 1. copie locale (Dropbox et les autres fournisseurs peuvent être lents ou distants)
        File tmp = new File(ctx.getCacheDir(), "import.dxf");
        long total = size(cr, uri);
        pl.onProgress("Téléchargement de " + name, 0);
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Fichier inaccessible");
            byte[] b = new byte[1 << 16];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != last) { pl.onProgress("Téléchargement de " + name, pct); last = pct; }
                }
            }
        }
        total = tmp.length();
        checkAscii(tmp);
        // 2. en-tête et calques
        DxfParser head = new DxfParser();
        try (InputStream in = new BufferedInputStream(new FileInputStream(tmp))) {
            head.readTablesOnly(in);
        }
        PlanBuilder builder = new PlanBuilder();
        if (head.extMin != null && head.extMax != null
                && Math.abs(head.extMax[0] - head.extMin[0]) < 1e7 && Math.abs(head.extMax[0]) < 1e8) {
            builder.setOrigin((head.extMin[0] + head.extMax[0]) / 2, (head.extMin[1] + head.extMax[1]) / 2);
        }
        // 3. lecture complète
        DxfParser parser = new DxfParser();
        final long tot = total;
        try (InputStream in = new FileInputStream(tmp)) {
            parser.parse(in, builder, bytes -> pl.onProgress("Lecture du plan", (int) (bytes * 100 / Math.max(1, tot))));
        }
        pl.onProgress("Préparation de l'affichage", 100);
        PlanData plan = builder.build(parser, name);
        if (plan.segmentCount() == 0 && plan.textCount() == 0 && plan.polys.isEmpty())
            throw new IOException("Aucun objet lisible dans l'espace objet de ce DXF.");
        // 4. enregistrement
        String id = Long.toString(System.currentTimeMillis(), 36);
        File f = new File(dir, id + ".plan");
        try (OutputStream out = new FileOutputStream(f)) {
            plan.write(out);
        }
        tmp.delete();
        addEntry(id, name);
        setCurrent(id);
        lastImportStats = parser.entityCount + " objets, dont " + parser.proxyCount + " objets Covadis/métier";
        return plan;
    }

    String lastImportStats = "";

    private static void checkAscii(File f) throws IOException {
        byte[] b = new byte[22];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(b);
            if (n >= 18 && new String(b, 0, 18, "ISO-8859-1").startsWith("AutoCAD Binary DXF"))
                throw new IOException("DXF binaire non pris en charge : enregistrez le DXF au format ASCII dans AutoCAD.");
            if (n >= 6 && new String(b, 0, 6, "ISO-8859-1").startsWith("AC10"))
                throw new IOException("Ce fichier est un DWG : enregistrez-le en DXF depuis AutoCAD (ENREGISTRER SOUS > DXF).");
        }
    }

    PlanData load(String id) throws IOException {
        File f = new File(dir, id + ".plan");
        PlanData p;
        try (InputStream in = new FileInputStream(f)) {
            p = PlanData.read(in);
        }
        applyVisibility(id, p);
        return p;
    }

    // ---- visibilité des calques

    void saveVisibility(String id, PlanData p) {
        Set<String> vis = new HashSet<>();
        for (PlanData.Layer l : p.layers) if (l.visible) vis.add(l.name);
        prefs.edit().putStringSet("vis_" + id, vis).putBoolean("visset_" + id, true).apply();
    }

    private void applyVisibility(String id, PlanData p) {
        if (!prefs.getBoolean("visset_" + id, false)) return;
        Set<String> vis = prefs.getStringSet("vis_" + id, new HashSet<>());
        for (PlanData.Layer l : p.layers) l.visible = vis.contains(l.name);
    }

    // ---- jeux de calques

    List<String> layerSetNames(String id) {
        List<String> names = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("sets_" + id, "{}"));
            JSONArray a = o.names();
            if (a != null) for (int i = 0; i < a.length(); i++) names.add(a.getString(i));
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(names);
        return names;
    }

    void saveLayerSet(String id, String name, PlanData p) {
        try {
            JSONObject o = new JSONObject(prefs.getString("sets_" + id, "{}"));
            JSONArray a = new JSONArray();
            for (PlanData.Layer l : p.layers) if (l.visible) a.put(l.name);
            o.put(name, a);
            prefs.edit().putString("sets_" + id, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void applyLayerSet(String id, String name, PlanData p) {
        try {
            JSONArray a = new JSONObject(prefs.getString("sets_" + id, "{}")).getJSONArray(name);
            Set<String> vis = new HashSet<>();
            for (int i = 0; i < a.length(); i++) vis.add(a.getString(i));
            for (PlanData.Layer l : p.layers) l.visible = vis.contains(l.name);
        } catch (Exception ignored) {
        }
    }

    void deleteLayerSet(String id, String name) {
        try {
            JSONObject o = new JSONObject(prefs.getString("sets_" + id, "{}"));
            o.remove(name);
            prefs.edit().putString("sets_" + id, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---- plans récents

    List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.getString("id");
                e.name = o.getString("name");
                e.date = o.optLong("date");
                if (new File(dir, e.id + ".plan").exists()) list.add(e);
            }
        } catch (Exception ignored) {
        }
        list.sort((x, y) -> Long.compare(y.date, x.date));
        return list;
    }

    private void addEntry(String id, String name) {
        try {
            JSONArray a = new JSONArray(prefs.getString("entries", "[]"));
            JSONObject o = new JSONObject();
            o.put("id", id).put("name", name).put("date", System.currentTimeMillis());
            a.put(o);
            prefs.edit().putString("entries", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void touch(String id) {
        try {
            JSONArray a = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("id").equals(id))
                a.getJSONObject(i).put("date", System.currentTimeMillis());
            prefs.edit().putString("entries", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void delete(String id) {
        new File(dir, id + ".plan").delete();
        try {
            JSONArray a = new JSONArray(prefs.getString("entries", "[]")), b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).getString("id").equals(id)) b.put(a.get(i));
            prefs.edit().putString("entries", b.toString()).remove("vis_" + id).remove("visset_" + id).remove("sets_" + id).apply();
        } catch (Exception ignored) {
        }
        if (id.equals(current())) prefs.edit().remove("current").apply();
    }

    String current() {
        return prefs.getString("current", null);
    }

    void setCurrent(String id) {
        prefs.edit().putString("current", id).apply();
    }
}
