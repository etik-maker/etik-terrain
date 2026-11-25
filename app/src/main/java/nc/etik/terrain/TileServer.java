package nc.etik.terrain;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Petit serveur de tuiles local (127.0.0.1) entre la carte et les fonds en ligne.
 * Une tuile déjà sur le téléphone est servie sans réseau ; sinon elle est
 * téléchargée, enregistrée, puis servie. Ainsi tout ce qui a été affiché ou
 * préparé reste disponible hors connexion.
 */
final class TileServer {

    private final File root;
    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cacheAllowed = new ConcurrentHashMap<>();
    private ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private int port;

    TileServer(File root) {
        this.root = root;
    }

    /** Déclare un fond : identifiant, modèle d'URL {z}/{x}/{y}, enregistrement autorisé. */
    void register(String id, String urlTemplate, boolean allowCache) {
        templates.put(id, urlTemplate);
        cacheAllowed.put(id, allowCache);
    }

    synchronized int start() throws IOException {
        if (server != null) return port;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = server.getLocalPort();
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (server.isClosed()) return;
                }
            }
        }, "tuiles");
        t.setDaemon(true);
        t.start();
        return port;
    }

    String localUrl(String id) {
        return "http://127.0.0.1:" + port + "/" + id + "/{z}/{x}/{y}";
    }

    File tileFile(String id, int z, int x, int y) {
        return new File(root, id + "/" + z + "/" + x + "/" + y);
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String req = in.readLine();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* en-têtes ignorés */ }
            OutputStream out = sock.getOutputStream();
            byte[] data = null;
            if (req != null && req.startsWith("GET ")) {
                String path = req.split(" ")[1];
                String[] p = path.split("/");
                if (p.length >= 5) {
                    try {
                        data = get(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4].split("\\?")[0]), true);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (data == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } else {
                String type = data.length > 3 && (data[0] & 0xFF) == 0x89 ? "image/png" : "image/jpeg";
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
                        + "\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(data);
            }
            out.flush();
        } catch (IOException ignored) {
        }
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
