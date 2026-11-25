package nc.etik.terrain.core;

import java.util.List;

/** Évaluation de B-splines (NURBS) par l'algorithme de De Boor. */
final class Spline {
    private Spline() {}

    static void sample(List<double[]> ctrl, List<Double> knots, List<Double> weights, int p, DxfParser.PointList out) {
        int n = ctrl.size();
        double[] U = new double[knots.size()];
        for (int i = 0; i < U.length; i++) U[i] = knots.get(i);
        boolean rational = weights.size() == n;
        double u0 = U[p], u1 = U[n];
        int samples = Math.min(2000, Math.max(16, n * 8));
        double[] wx = new double[p + 1], wy = new double[p + 1], ww = new double[p + 1];
        for (int s = 0; s <= samples; s++) {
            double u = u0 + (u1 - u0) * s / samples;
            int k = p;
            while (k < n - 1 && u >= U[k + 1]) k++;
            for (int j = 0; j <= p; j++) {
                double[] c = ctrl.get(k - p + j);
                double w = rational ? weights.get(k - p + j) : 1.0;
                wx[j] = c[0] * w;
                wy[j] = c[1] * w;
                ww[j] = w;
            }
            for (int r = 1; r <= p; r++) {
                for (int j = p; j >= r; j--) {
                    int i = k - p + j;
                    double den = U[i + p - r + 1] - U[i];
                    double a = den == 0 ? 0 : (u - U[i]) / den;
                    wx[j] = (1 - a) * wx[j - 1] + a * wx[j];
                    wy[j] = (1 - a) * wy[j - 1] + a * wy[j];
                    ww[j] = (1 - a) * ww[j - 1] + a * ww[j];
                }
            }
            double w = ww[p] == 0 ? 1 : ww[p];
            out.add(wx[p] / w, wy[p] / w);
        }
    }
}
