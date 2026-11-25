package nc.etik.terrain;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Fonds de carte déclarés dans assets/fonds.json. */
final class Basemaps {

    static final class Fond {
        String id, nom, url, attribution, couleur;
        int maxzoom;
        boolean dark;
    }

    final List<Fond> fonds = new ArrayList<>();

    Basemaps(Context ctx) {
        try (InputStream in = ctx.getAssets().open("fonds.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("fonds");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Fond f = new Fond();
                f.id = o.getString("id");
                f.nom = o.getString("nom");
                f.url = o.optString("url", "");
                f.attribution = o.optString("attribution", "");
                f.couleur = o.optString("couleur", "#E8E8E8");
                f.maxzoom = o.optInt("maxzoom", 19);
                f.dark = o.optBoolean("dark", false);
                fonds.add(f);
            }
        } catch (Exception e) {
            throw new RuntimeException("fonds.json illisible", e);
        }
    }

    Fond get(String id) {
        for (Fond f : fonds) if (f.id.equals(id)) return f;
        return fonds.get(0);
    }

    /** Style MapLibre (JSON) affichant ce fond. */
    static String styleJson(Fond f) {
        try {
            JSONObject style = new JSONObject();
            style.put("version", 8);
            JSONObject sources = new JSONObject();
            JSONArray layers = new JSONArray();
            JSONObject bg = new JSONObject();
            bg.put("id", "fond-uni");
            bg.put("type", "background");
            bg.put("paint", new JSONObject().put("background-color", f.url.isEmpty() ? f.couleur : (f.dark ? "#1A1F24" : "#EDEDED")));
            layers.put(bg);
            if (!f.url.isEmpty()) {
                JSONObject src = new JSONObject();
                src.put("type", "raster");
                src.put("tiles", new JSONArray().put(f.url));
                src.put("tileSize", 256);
                src.put("maxzoom", f.maxzoom);
                src.put("attribution", f.attribution);
                sources.put("fond", src);
                JSONObject l = new JSONObject();
                l.put("id", "fond");
                l.put("type", "raster");
                l.put("source", "fond");
                layers.put(l);
            }
            style.put("sources", sources);
            style.put("layers", layers);
            return style.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
