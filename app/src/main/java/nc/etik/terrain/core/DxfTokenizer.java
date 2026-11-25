package nc.etik.terrain.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Lecture rapide d'un DXF ASCII sous forme de paires (code de groupe, valeur).
 * Compte les octets lus pour afficher une progression.
 */
public final class DxfTokenizer {
    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int pos, len;
    private long bytesRead;
    private byte[] line = new byte[256];
    private Charset charset = StandardCharsets.UTF_8;
    private boolean pushedBack;

    public int code;
    public String value;

    public DxfTokenizer(InputStream in) {
        this.in = in;
    }

    public void setCharset(Charset cs) {
        charset = cs;
    }

    public long bytesRead() {
        return bytesRead - (len - pos);
    }

    /** Remet la paire courante pour qu'elle soit relue au prochain appel à next(). */
    public void pushBack() {
        pushedBack = true;
    }

    private int readLine() throws IOException {
        int n = 0;
        while (true) {
            if (pos >= len) {
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return n == 0 ? -1 : n;
                }
                bytesRead += len;
            }
            byte b = buf[pos++];
            if (b == '\n') break;
            if (n == line.length) {
                byte[] nl = new byte[line.length * 2];
                System.arraycopy(line, 0, nl, 0, n);
                line = nl;
            }
            line[n++] = b;
        }
        if (n > 0 && line[n - 1] == '\r') n--;
        return n;
    }

    /** Lit la paire suivante. Retourne false en fin de fichier. */
    public boolean next() throws IOException {
        if (pushedBack) {
            pushedBack = false;
            return true;
        }
        int n = readLine();
        if (n < 0) return false;
        int c = 0, i = 0;
        boolean neg = false;
        while (i < n && line[i] == ' ') i++;
        if (i < n && line[i] == '-') { neg = true; i++; }
        for (; i < n; i++) {
            byte b = line[i];
            if (b >= '0' && b <= '9') c = c * 10 + (b - '0');
            else if (b != ' ') throw new IOException("Code de groupe DXF invalide (fichier binaire ou corrompu ?)");
        }
        code = neg ? -c : c;
        n = readLine();
        if (n < 0) return false;
        // Les valeurs numériques sont précédées d'espaces ; les textes doivent être conservés tels quels.
        boolean ascii = true;
        for (int k = 0; k < n; k++) if (line[k] < 0) { ascii = false; break; }
        if (ascii) value = new String(line, 0, n, StandardCharsets.ISO_8859_1);
        else value = new String(line, 0, n, charset);
        return true;
    }

    public double d() {
        return parseDouble(value);
    }

    public int i() {
        return parseInt(value);
    }

    public static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int parseInt(String s) {
        try {
            String t = s.trim();
            long v = Long.parseLong(t);
            return (int) v;
        } catch (NumberFormatException e) {
            return (int) parseDouble(s);
        }
    }
}
