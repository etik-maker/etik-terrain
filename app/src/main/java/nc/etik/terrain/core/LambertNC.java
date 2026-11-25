package nc.etik.terrain.core;

/**
 * Projection RGNC91-93 / Lambert Nouvelle-Calédonie (EPSG:3163), ellipsoïde GRS80.
 * Conique conforme de Lambert à 2 parallèles. Conversions vers/depuis WGS84
 * (RGNC91-93 est compatible WGS84 au niveau métrique) et Web Mercator (EPSG:3857).
 */
public final class LambertNC {
    private LambertNC() {}

    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257222101;
    private static final double E2 = 2 * F - F * F;
    private static final double E = Math.sqrt(E2);
    private static final double LAT0 = Math.toRadians(-21.5);
    private static final double LON0 = Math.toRadians(166.0);
    private static final double LAT1 = Math.toRadians(-20.0 - 2.0 / 3.0);
    private static final double LAT2 = Math.toRadians(-22.0 - 1.0 / 3.0);
    private static final double X0 = 400000.0;
    private static final double Y0 = 300000.0;
    private static final double N;
    private static final double AF;
    private static final double RHO0;

    static {
        double m1 = m(LAT1), m2 = m(LAT2);
        double t1 = t(LAT1), t2 = t(LAT2), t0 = t(LAT0);
        N = (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
        double fc = m1 / (N * Math.pow(t1, N));
        AF = A * fc;
        RHO0 = AF * Math.pow(t0, N);
    }

    private static double m(double phi) {
        double s = Math.sin(phi);
        return Math.cos(phi) / Math.sqrt(1 - E2 * s * s);
    }

    private static double t(double phi) {
        double s = Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - E * s) / (1 + E * s), E / 2);
    }

    /** Lambert NC (x, y) en mètres vers {lon, lat} en degrés. */
    public static void toLonLat(double x, double y, double[] out) {
        double dx = x - X0;
        double dy = RHO0 - (y - Y0);
        double sign = Math.signum(N);
        double rho = sign * Math.sqrt(dx * dx + dy * dy);
        double theta = Math.atan2(sign * dx, sign * dy);
        double tt = Math.pow(rho / AF, 1.0 / N);
        double phi = Math.PI / 2 - 2 * Math.atan(tt);
        for (int i = 0; i < 15; i++) {
            double s = Math.sin(phi);
            double np = Math.PI / 2 - 2 * Math.atan(tt * Math.pow((1 - E * s) / (1 + E * s), E / 2));
            if (Math.abs(np - phi) < 1e-12) { phi = np; break; }
            phi = np;
        }
        out[0] = Math.toDegrees(theta / N + LON0);
        out[1] = Math.toDegrees(phi);
    }

    /** {lon, lat} en degrés vers Lambert NC {x, y} en mètres. */
    public static void fromLonLat(double lon, double lat, double[] out) {
        double phi = Math.toRadians(lat);
        double rho = AF * Math.pow(t(phi), N);
        double theta = N * (Math.toRadians(lon) - LON0);
        out[0] = X0 + rho * Math.sin(theta);
        out[1] = Y0 + RHO0 - rho * Math.cos(theta);
    }

    public static final double MERC_R = 6378137.0;

    public static double lonToMercX(double lon) {
        return MERC_R * Math.toRadians(lon);
    }

    public static double latToMercY(double lat) {
        return MERC_R * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
    }

    public static double mercXToLon(double mx) {
        return Math.toDegrees(mx / MERC_R);
    }

    public static double mercYToLat(double my) {
        return Math.toDegrees(2 * Math.atan(Math.exp(my / MERC_R)) - Math.PI / 2);
    }

    /** Lambert NC vers Web Mercator {mx, my}. */
    public static void toMercator(double x, double y, double[] out) {
        toLonLat(x, y, out);
        double lon = out[0], lat = out[1];
        out[0] = lonToMercX(lon);
        out[1] = latToMercY(lat);
    }
}
