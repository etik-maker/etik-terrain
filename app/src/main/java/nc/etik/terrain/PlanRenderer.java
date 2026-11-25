package nc.etik.terrain;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import nc.etik.terrain.core.PlanData;

/**
 * Dessine un plan sur un Canvas avec une matrice coordonnées locales → pixels.
 * Utilisé hors du fil principal, sur une image tampon (une seule instance par fil).
 */
final class PlanRenderer {

    private final float density;
    private final float lineWidthPx;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix inv = new Matrix();
    private final float[] mv = new float[9];
    private final Map<Long, float[]> buf = new HashMap<>();
    private final Map<Long, Integer> len = new HashMap<>();
    private PlanData pathsFor;
    private Path[] polyPaths;

    PlanRenderer(float density) {
        this.density = density;
        lineWidthPx = 1.3f * density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    static int resolve(int color, boolean dark) {
        if (color == 0) return dark ? 0xFFFFFFFF : 0xFF000000;
        if (!dark) {
            int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
            if (r > 230 && g > 230 && b > 200) return 0xFF333333;
        }
        return color;
    }

    /** @param cancel vérifié régulièrement : retourne false si le dessin a été abandonné */
    boolean render(Canvas canvas, Matrix m, int w, int h, PlanData plan, boolean[] visible, boolean dark,
                   java.util.function.BooleanSupplier cancel) {
        if (!m.invert(inv)) return false;
        float[] c = {0, 0, w, 0, 0, h, w, h};
        inv.mapPoints(c);
        float vx0 = Math.min(Math.min(c[0], c[2]), Math.min(c[4], c[6]));
        float vx1 = Math.max(Math.max(c[0], c[2]), Math.max(c[4], c[6]));
        float vy0 = Math.min(Math.min(c[1], c[3]), Math.min(c[5], c[7]));
        float vy1 = Math.max(Math.max(c[1], c[3]), Math.max(c[5], c[7]));
        m.getValues(mv);
        float scale = (float) Math.sqrt(Math.abs(mv[0] * mv[4] - mv[1] * mv[3]));
        if (pathsFor != plan) {
            pathsFor = plan;
            polyPaths = new Path[plan.polys.size()];
        }
        if (!fills(canvas, m, plan, visible, dark, vx0, vy0, vx1, vy1, scale, cancel)) return false;
        if (cancel.getAsBoolean()) return false;
        lines(canvas, m, plan, visible, dark, vx0, vy0, vx1, vy1, scale);
        if (cancel.getAsBoolean()) return false;
        texts(canvas, m, w, h, plan, visible, dark, vx0, vy0, vx1, vy1, scale);
        return true;
    }

    private boolean fills(Canvas canvas, Matrix m, PlanData plan, boolean[] visible, boolean dark,
                          float vx0, float vy0, float vx1, float vy1, float scale, java.util.function.BooleanSupplier cancel) {
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
            int col = resolve(p.color, dark);
            fillPaint.setColor((col & 0x00FFFFFF) | ((p.solid ? 150 : 60) << 24));
            canvas.drawPath(path, fillPaint);
            if ((++drawn & 255) == 0 && cancel.getAsBoolean()) {
                canvas.restore();
                return false;
            }
        }
        canvas.restore();
        return true;
    }

    private static int[] cellRange(PlanData plan, float vx0, float vy0, float vx1, float vy1) {
        float cs = plan.cellSize;
        return new int[]{
                Math.max(0, (int) Math.floor((vx0 - plan.gridX) / cs) - 1),
                Math.min(plan.nx - 1, (int) Math.floor((vx1 - plan.gridX) / cs) + 1),
                Math.max(0, (int) Math.floor((vy0 - plan.gridY) / cs) - 1),
                Math.min(plan.ny - 1, (int) Math.floor((vy1 - plan.gridY) / cs) + 1)};
    }

    private void lines(Canvas canvas, Matrix m, PlanData plan, boolean[] visible, boolean dark,
                       float vx0, float vy0, float vx1, float vy1, float scale) {
        int[] r = cellRange(plan, vx0, vy0, vx1, vy1);
        len.clear();
        float minLen = 0.8f / scale;
        for (int cy = r[2]; cy <= r[3]; cy++) {
            for (int cx = r[0]; cx <= r[1]; cx++) {
                PlanData.Cell cell = plan.cells[cy * plan.nx + cx];
                if (cell == null || cell.chunks == null) continue;
                for (PlanData.Chunk ch : cell.chunks) {
                    if (!visible[ch.layer]) continue;
                    int n = countAbove(ch.len, minLen);
                    if (n == 0) continue;
                    int col = resolve(ch.color, dark);
                    float px = Math.max(lineWidthPx, Math.max(ch.width * scale, ch.lw * 3.5f * density));
                    if (px > 400) px = 400;
                    long key = ((long) Math.round(px * 2) << 32) | (col & 0xFFFFFFFFL);
                    Integer used = len.get(key);
                    int u = used == null ? 0 : used;
                    float[] b = buf.get(key);
                    if (b == null || b.length < u + 4 * n) {
                        b = b == null ? new float[Math.max(4096, 8 * n)] : Arrays.copyOf(b, Math.max(b.length * 2, u + 4 * n));
                        buf.put(key, b);
                    }
                    m.mapPoints(b, u, ch.seg, 0, 2 * n);
                    len.put(key, u + 4 * n);
                }
            }
        }
        // les traits épais d'abord, les fins par-dessus
        Long[] keys = len.keySet().toArray(new Long[0]);
        Arrays.sort(keys, (a, b) -> Long.compare(b >>> 32, a >>> 32));
        for (Long key : keys) {
            linePaint.setColor((int) (long) key);
            linePaint.setStrokeWidth((key >>> 32) / 2f);
            canvas.drawLines(buf.get(key), 0, len.get(key), linePaint);
        }
    }

    private static int countAbove(float[] len, float min) {
        int lo = 0, hi = len.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (len[mid] >= min) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void texts(Canvas canvas, Matrix m, int w, int h, PlanData plan, boolean[] visible, boolean dark,
                       float vx0, float vy0, float vx1, float vy1, float scale) {
        int[] r = cellRange(plan, vx0, vy0, vx1, vy1);
        float minPx = 5f * density;
        float[] pt = new float[2], dir = new float[2];
        int drawn = 0;
        for (int cy = r[2]; cy <= r[3]; cy++) {
            for (int cx = r[0]; cx <= r[1]; cx++) {
                PlanData.Cell cell = plan.cells[cy * plan.nx + cx];
                if (cell == null || cell.texts == null) continue;
                for (PlanData.Text t : cell.texts) {
                    float px = t.h * scale;
                    if (px < minPx) break;
                    if (px > 600 || !visible[t.layer]) continue;
                    pt[0] = t.x;
                    pt[1] = t.y;
                    m.mapPoints(pt);
                    if (pt[0] < -2000 || pt[0] > w + 2000 || pt[1] < -2000 || pt[1] > h + 2000) continue;
                    dir[0] = (float) Math.cos(t.ang);
                    dir[1] = (float) Math.sin(t.ang);
                    m.mapVectors(dir);
                    float deg = (float) Math.toDegrees(Math.atan2(dir[1], dir[0]));
                    textPaint.setColor(resolve(t.color, dark));
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
