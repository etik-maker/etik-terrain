package nc.etik.terrain;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Projection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import nc.etik.terrain.core.LambertNC;
import nc.etik.terrain.core.PlanData;

/**
 * Dessine le plan AutoCAD au-dessus de la carte. La transformation
 * coordonnées locales (Web Mercator) → écran est recalculée à chaque image
 * à partir de la projection de la carte : le plan suit exactement la carte.
 */
public class PlanOverlayView extends View {

    private MapLibreMap map;
    private PlanData plan;
    private boolean[] visible = new boolean[0];
    private boolean darkBackground = true;
    private float lineWidthPx;

    private final Matrix m = new Matrix();
    private final Matrix inv = new Matrix();
    private final float[] mv = new float[9];
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, float[]> colorBuf = new HashMap<>();
    private final Map<Integer, Integer> colorLen = new HashMap<>();
    private Path[] polyPaths;

    public PlanOverlayView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        lineWidthPx = 1.3f * d;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(lineWidthPx);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    void setMap(MapLibreMap map) {
        this.map = map;
        invalidate();
    }

    void setPlan(PlanData p) {
        plan = p;
        polyPaths = p == null ? null : new Path[p.polys.size()];
        refreshVisibility();
    }

    PlanData getPlan() {
        return plan;
    }

    void refreshVisibility() {
        if (plan == null) {
            visible = new boolean[0];
        } else {
            visible = new boolean[plan.layers.size()];
            for (int i = 0; i < visible.length; i++) visible[i] = plan.layers.get(i).visible;
        }
        invalidate();
    }

    void setDarkBackground(boolean dark) {
        darkBackground = dark;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false; // les gestes passent à la carte
    }

    private int resolve(int color) {
        if (color == 0) return darkBackground ? 0xFFFFFFFF : 0xFF000000;
        if (!darkBackground) {
            // couleurs très claires illisibles sur fond clair : on les assombrit
            int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
            if (r > 230 && g > 230 && b > 200) return 0xFF333333;
        }
        return color;
    }

    /** Calcule la matrice local → écran. Retourne false si la carte n'est pas prête. */
    private boolean computeMatrix() {
        if (map == null || plan == null) return false;
        Projection proj = map.getProjection();
        double lat0 = LambertNC.mercYToLat(plan.originMy);
        double mpp = proj.getMetersPerPixelAtLatitude(lat0) / Math.cos(Math.toRadians(lat0));
        if (!(mpp > 0)) return false;
        double D = mpp * 500; // ~500 px
        PointF p0 = screen(proj, 0, 0), p1 = screen(proj, D, 0), p2 = screen(proj, 0, D);
        float ax = (float) ((p1.x - p0.x) / D), ay = (float) ((p1.y - p0.y) / D);
        float bx = (float) ((p2.x - p0.x) / D), by = (float) ((p2.y - p0.y) / D);
        m.setValues(new float[]{ax, bx, p0.x, ay, by, p0.y, 0, 0, 1});
        return m.invert(inv);
    }

    private PointF screen(Projection proj, double lx, double ly) {
        double lat = LambertNC.mercYToLat(plan.originMy + ly);
        double lon = LambertNC.mercXToLon(plan.originMx + lx);
        return proj.toScreenLocation(new LatLng(lat, lon));
    }

    /** Position écran d'un point Lambert (pour les marqueurs). */
    PointF lambertToScreen(double x, double y) {
        if (map == null) return null;
        double[] o = new double[2];
        LambertNC.toLonLat(x, y, o);
        return map.getProjection().toScreenLocation(new LatLng(o[1], o[0]));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!computeMatrix()) return;
        int w = getWidth(), h = getHeight();
        float[] corners = {0, 0, w, 0, 0, h, w, h};
        inv.mapPoints(corners);
        float vx0 = Math.min(Math.min(corners[0], corners[2]), Math.min(corners[4], corners[6]));
        float vx1 = Math.max(Math.max(corners[0], corners[2]), Math.max(corners[4], corners[6]));
        float vy0 = Math.min(Math.min(corners[1], corners[3]), Math.min(corners[5], corners[7]));
        float vy1 = Math.max(Math.max(corners[1], corners[3]), Math.max(corners[5], corners[7]));
        m.getValues(mv);
        float scale = (float) Math.sqrt(Math.abs(mv[0] * mv[4] - mv[1] * mv[3])); // px par mètre

        drawFills(canvas, vx0, vy0, vx1, vy1, scale);
        drawLines(canvas, vx0, vy0, vx1, vy1, scale);
        drawTexts(canvas, vx0, vy0, vx1, vy1, scale);
    }

    private void drawFills(Canvas canvas, float vx0, float vy0, float vx1, float vy1, float scale) {
        canvas.save();
        canvas.concat(m);
        int drawn = 0;
        for (int i = 0; i < plan.polys.size(); i++) {
            PlanData.Poly p = plan.polys.get(i);
            if (p.size * scale < 2f) break; // triées par taille décroissante
            if (!visible[p.layer]) continue;
            if (p.maxx < vx0 || p.minx > vx1 || p.maxy < vy0 || p.miny > vy1) continue;
            Path path = polyPaths[i];
            if (path == null) {
                path = new Path();
                path.setFillType(Path.FillType.EVEN_ODD);
                for (float[] r : p.rings) {
                    path.moveTo(r[0], r[1]);
                    for (int k = 2; k < r.length; k += 2) path.lineTo(r[k], r[k + 1]);
                    path.close();
                }
                polyPaths[i] = path;
            }
            int c = resolve(p.color);
            fillPaint.setColor((c & 0x00FFFFFF) | ((p.solid ? 150 : 60) << 24));
            canvas.drawPath(path, fillPaint);
            if (++drawn > 6000) break;
        }
        canvas.restore();
    }

    private void drawLines(Canvas canvas, float vx0, float vy0, float vx1, float vy1, float scale) {
        float cs = plan.cellSize;
        int cx0 = Math.max(0, (int) Math.floor((vx0 - plan.gridX) / cs) - 1);
        int cx1 = Math.min(plan.nx - 1, (int) Math.floor((vx1 - plan.gridX) / cs) + 1);
        int cy0 = Math.max(0, (int) Math.floor((vy0 - plan.gridY) / cs) - 1);
        int cy1 = Math.min(plan.ny - 1, (int) Math.floor((vy1 - plan.gridY) / cs) + 1);
        colorLen.clear();
        float minLen = 0.8f / scale; // segments de moins de 0,8 px ignorés
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                PlanData.Cell cell = plan.cells[cy * plan.nx + cx];
                if (cell == null || cell.chunks == null) continue;
                for (PlanData.Chunk ch : cell.chunks) {
                    if (!visible[ch.layer]) continue;
                    int n = countAbove(ch.len, minLen);
                    if (n == 0) continue;
                    int col = resolve(ch.color);
                    Integer used = colorLen.get(col);
                    int u = used == null ? 0 : used;
                    float[] buf = colorBuf.get(col);
                    if (buf == null || buf.length < u + 4 * n) {
                        buf = buf == null ? new float[Math.max(4096, 8 * n)] : Arrays.copyOf(buf, Math.max(buf.length * 2, u + 4 * n));
                        colorBuf.put(col, buf);
                    }
                    m.mapPoints(buf, u, ch.seg, 0, 2 * n);
                    colorLen.put(col, u + 4 * n);
                }
            }
        }
        for (Map.Entry<Integer, Integer> e : colorLen.entrySet()) {
            linePaint.setColor(e.getKey());
            canvas.drawLines(colorBuf.get(e.getKey()), 0, e.getValue(), linePaint);
        }
    }

    /** Nombre de segments de longueur ≥ min (tableau trié décroissant). */
    private static int countAbove(float[] len, float min) {
        int lo = 0, hi = len.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (len[mid] >= min) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void drawTexts(Canvas canvas, float vx0, float vy0, float vx1, float vy1, float scale) {
        float cs = plan.cellSize;
        int cx0 = Math.max(0, (int) Math.floor((vx0 - plan.gridX) / cs) - 1);
        int cx1 = Math.min(plan.nx - 1, (int) Math.floor((vx1 - plan.gridX) / cs) + 1);
        int cy0 = Math.max(0, (int) Math.floor((vy0 - plan.gridY) / cs) - 1);
        int cy1 = Math.min(plan.ny - 1, (int) Math.floor((vy1 - plan.gridY) / cs) + 1);
        float minPx = 5f * getResources().getDisplayMetrics().density;
        float[] pt = new float[2], dir = new float[2];
        int drawn = 0;
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                PlanData.Cell cell = plan.cells[cy * plan.nx + cx];
                if (cell == null || cell.texts == null) continue;
                for (PlanData.Text t : cell.texts) {
                    float px = t.h * scale;
                    if (px < minPx) break; // triés par hauteur décroissante
                    if (px > 600 || !visible[t.layer]) continue;
                    pt[0] = t.x;
                    pt[1] = t.y;
                    m.mapPoints(pt);
                    if (pt[0] < -2000 || pt[0] > getWidth() + 2000 || pt[1] < -2000 || pt[1] > getHeight() + 2000) continue;
                    dir[0] = (float) Math.cos(t.ang);
                    dir[1] = (float) Math.sin(t.ang);
                    m.mapVectors(dir);
                    float deg = (float) Math.toDegrees(Math.atan2(dir[1], dir[0]));
                    textPaint.setColor(resolve(t.color));
                    textPaint.setTextSize(px * 1.4f);
                    textPaint.setTextAlign(t.ha == 1 ? Paint.Align.CENTER : t.ha == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
                    float dy;
                    switch (t.va) {
                        case 1: dy = -textPaint.descent(); break;
                        case 2: dy = px / 2; break;
                        case 3: dy = px; break;
                        default: dy = 0;
                    }
                    canvas.save();
                    canvas.translate(pt[0], pt[1]);
                    canvas.rotate(deg);
                    canvas.drawText(t.s, 0, dy, textPaint);
                    canvas.restore();
                    if (++drawn > 4000) return;
                }
            }
        }
    }
}
