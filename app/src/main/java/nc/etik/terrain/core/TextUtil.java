package nc.etik.terrain.core;

/** Nettoyage des textes AutoCAD : codes %%, mise en forme MTEXT, \U+XXXX. */
public final class TextUtil {
    private TextUtil() {}

    public static String decodeText(String s) {
        if (s == null) return "";
        s = unicodeEscapes(s);
        if (s.indexOf("%%") < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length() && s.charAt(i + 1) == '%') {
                char k = Character.toLowerCase(s.charAt(i + 2));
                switch (k) {
                    case 'c': sb.append('Ø'); i += 2; continue;
                    case 'd': sb.append('°'); i += 2; continue;
                    case 'p': sb.append('±'); i += 2; continue;
                    case 'u': case 'o': case 'k': i += 2; continue;
                    case '%': sb.append('%'); i += 2; continue;
                    default:
                        if (Character.isDigit(k)) {
                            int j = i + 2, v = 0;
                            while (j < s.length() && j < i + 5 && Character.isDigit(s.charAt(j))) v = v * 10 + (s.charAt(j++) - '0');
                            sb.append((char) v);
                            i = j - 1;
                            continue;
                        }
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String unicodeEscapes(String s) {
        int i = s.indexOf("\\U+");
        if (i < 0) return s;
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (i >= 0 && i + 7 <= s.length()) {
            try {
                int cp = Integer.parseInt(s.substring(i + 3, i + 7), 16);
                sb.append(s, last, i).appendCodePoint(cp);
                last = i + 7;
            } catch (NumberFormatException e) {
                sb.append(s, last, i + 3);
                last = i + 3;
            }
            i = s.indexOf("\\U+", last);
        }
        sb.append(s.substring(last));
        return sb.toString();
    }

    /** Supprime la mise en forme MTEXT et convertit les retours à la ligne. */
    public static String decodeMText(String s) {
        s = unicodeEscapes(s);
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch == '{' || ch == '}') continue;
            if (ch == '\\' && i + 1 < n) {
                char k = s.charAt(i + 1);
                switch (k) {
                    case 'P': case 'X': sb.append('\n'); i++; continue;
                    case '~': sb.append(' '); i++; continue;
                    case '\\': case '{': case '}': sb.append(k); i++; continue;
                    case 'L': case 'l': case 'O': case 'o': case 'K': case 'k': i++; continue;
                    case 'S': { // fraction a^b ou a/b ou a#b
                        int end = s.indexOf(';', i);
                        if (end < 0) { i++; continue; }
                        String f = s.substring(i + 2, end).replace('^', '/').replace('#', '/');
                        sb.append(f.trim());
                        i = end;
                        continue;
                    }
                    case 'f': case 'F': case 'H': case 'h': case 'W': case 'w': case 'C': case 'c':
                    case 'A': case 'a': case 'Q': case 'q': case 'T': case 't': case 'p': {
                        int end = s.indexOf(';', i);
                        if (end < 0) { i++; continue; }
                        i = end;
                        continue;
                    }
                    default:
                        i++;
                        continue;
                }
            }
            sb.append(ch);
        }
        return decodeText(sb.toString());
    }
}
