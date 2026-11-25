package nc.etik.terrain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stockage des tuiles de fond de carte sur le téléphone (mode sans réseau) :
 * téléchargement, espace occupé, suppression. La carte lit ces fichiers
 * directement (URL file://).
 */
final class TileStore {

    private final File root;
    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cacheAllowed = new ConcurrentHashMap<>();

    TileStore(File root) {
        this.root = root;
    }

    /** Déclare un fond : identifiant, modèle d'URL {z}/{x}/{y}, enregistrement autorisé. */
    void register(String id, String urlTemplate, boolean allowCache) {
        templates.put(id, urlTemplate);
        cacheAllowed.put(id, allowCache);
    }

    /** Modèle d'URL file:// des tuiles enregistrées, lisible directement par la carte. */
    String localUrl(String id) {
        return "file://" + new File(root, id).getAbsolutePath() + "/{z}/{x}/{y}";
    }

    File tileFile(String id, int z, int x, int y) {
        return new File(root, id + "/" + z + "/" + x + "/" + y);
    }

    /** Tuile depuis le téléphone, sinon depuis le réseau (et enregistrée si autorisé). */
    byte[] get(String id, int z, int x, int y, boolean network) {
        File f = tileFile(id, z, x, y);
        if (f.exists() && f.length() > 0) {
            try {
                return readAll(new FileInputStream(f));
            } catch (IOException ignored) {
            }
        }
        if (!network) return null;
        byte[] data = fetch(id, z, x, y);
        if (data != null && Boolean.TRUE.equals(cacheAllowed.get(id))) save(f, data);
        return data;
    }

    /** Télécharge et enregistre une tuile si elle n'est pas déjà là. Retourne le nombre d'octets, -1 si échec. */
    long download(String id, int z, int x, int y) {
        File f = tileFile(id, z, x, y);
        if (f.exists() && f.length() > 0) return f.length();
        byte[] data = fetch(id, z, x, y);
        if (data == null) return -1;
        save(f, data);
        return data.length;
    }

    private byte[] fetch(String id, int z, int x, int y) {
        String tpl = templates.get(id);
        if (tpl == null || tpl.isEmpty()) return null;
        String url = tpl.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "ETIK-Terrain/0.4 (Android)");
            if (c.getResponseCode() != 200) return null;
            byte[] d = readAll(c.getInputStream());
            return d.length > 0 ? d : null;
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void save(File f, byte[] data) {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32768);
            byte[] b = new byte[16384];
            int n;
            while ((n = i.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    /** Espace occupé par les tuiles d'un fond (octets) et nombre de fichiers. */
    long[] usage(String id) {
        long[] r = new long[2];
        walk(new File(root, id), r);
        return r;
    }

    private static void walk(File f, long[] r) {
        File[] l = f.listFiles();
        if (l == null) return;
        for (File c : l) {
            if (c.isDirectory()) walk(c, r);
            else { r[0] += c.length(); r[1]++; }
        }
    }

    void clear(String id) {
        delete(new File(root, id));
    }

    private static void delete(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) delete(c);
        f.delete();
    }
}
