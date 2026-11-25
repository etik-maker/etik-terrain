package nc.etik.terrain;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Projection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import nc.etik.terrain.core.LambertNC;
import nc.etik.terrain.core.PlanData;

/**
 * Affiche le plan au-dessus de la carte. Le plan est dessiné en tâche de fond
 * dans une image un peu plus grande que l'écran ; pendant les gestes, cette
 * image est simplement déplacée / agrandie (fluide quel que soit le plan),
 * puis redessinée nette quand la carte s'arrête.
 */
public class PlanOverlayView extends View {

    private static final float MARGIN = 0.25f; // marge autour de l'écran dans l'image tampon

    private MapLibreMap map;
    private PlanData plan;
    private boolean[] visible = new boolean[0];
    private boolean darkBackground = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PlanRenderer renderer;
    private final AtomicInteger generation = new AtomicInteger();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private final Matrix tmp = new Matrix();

    private Bitmap cache;          // dernière image du plan
    private Matrix cacheM;         // matrice local → pixels de l'image tampon au moment du dessin
    private boolean cacheDark;
    private boolean renderPending;

    public PlanOverlayView(Context c, AttributeSet a) {
        super(c, a);
        renderer = new PlanRenderer(getResources().getDisplayMetrics().density);
        setWillNotDraw(false);
    }

    void setMap(MapLibreMap map) {
        this.map = map;
        requestRender();
    }

    void setPlan(PlanData p) {
        plan = p;
        dropCache();
        refreshVisibility();
    }

    PlanData getPlan() {
        return plan;
    }

    void refreshVisibility() {
        if (plan == null) visible = new boolean[0];
        else {
            visible = new boolean[plan.layers.size()];
            for (int i = 0; i < visible.length; i++) visible[i] = plan.layers.get(i).visible;
        }
        requestRender();
    }

    void setDarkBackground(boolean dark) {
        darkBackground = dark;
        requestRender();
    }

    /** À appeler quand la carte s'arrête de bouger. */
    void onCameraIdle() {
        requestRender();
    }

    private void dropCache() {
        generation.incrementAndGet();
        cache = null;
        cacheM = null;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false; // les gestes passent à la carte
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        requestRender();
    }

    /** Matrice local → écran d'après la projection actuelle de la carte. */
    private boolean computeMatrix(Matrix out) {
        if (map == null || plan == null) return false;
        Projection proj = map.getProjection();
        double lat0 = LambertNC.mercYToLat(plan.originMy);
        double mpp = proj.getMetersPerPixelAtLatitude(lat0) / Math.cos(Math.toRadians(lat0));
        if (!(mpp > 0)) return false;
        double D = mpp * 500;
        PointF p0 = screen(proj, 0, 0), p1 = screen(proj, D, 0), p2 = screen(proj, 0, D);
        float ax = (float) ((p1.x - p0.x) / D), ay = (float) ((p1.y - p0.y) / D);
        float bx = (float) ((p2.x - p0.x) / D), by = (float) ((p2.y - p0.y) / D);
        out.setValues(new float[]{ax, bx, p0.x, ay, by, p0.y, 0, 0, 1});
        return true;
    }

    private PointF screen(Projection proj, double lx, double ly) {
        double lat = LambertNC.mercYToLat(plan.originMy + ly);
        double lon = LambertNC.mercXToLon(plan.originMx + lx);
        return proj.toScreenLocation(new LatLng(lat, lon));
    }

    /** Lance un nouveau dessin du plan en tâche de fond pour la vue actuelle. */
    void requestRender() {
        invalidate();
        if (plan == null || map == null || getWidth() == 0) return;
        if (renderPending) return;
        renderPending = true;
        // on laisse la carte finir son image avant de lire la projection
        ui.post(this::startRender);
    }

    private void startRender() {
        renderPending = false;
        Matrix screenM = new Matrix();
        if (!computeMatrix(screenM)) return;
        int w = getWidth(), h = getHeight();
        int mx = (int) (w * MARGIN), my = (int) (h * MARGIN);
        int bw = w + 2 * mx, bh = h + 2 * my;
        Matrix bm = new Matrix(screenM);
        bm.postTranslate(mx, my);
        final PlanData p = plan;
        final boolean[] vis = visible.clone();
        final boolean dark = darkBackground;
        final int gen = generation.incrementAndGet();
        worker.execute(() -> {
            if (generation.get() != gen) return;
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                return;
            }
            Canvas c = new Canvas(bmp);
            boolean ok = renderer.render(c, bm, bw, bh, p, vis, dark, () -> generation.get() != gen);
            if (!ok) {
                bmp.recycle();
                return;
            }
            ui.post(() -> {
                if (generation.get() != gen || p != plan) {
                    bmp.recycle();
                    return;
                }
                Bitmap old = cache;
                cache = bmp;
                cacheM = bm;
                cacheDark = dark;
                invalidate();
                if (old != null) old.recycle();
            });
        });
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
        if (cache == null || cacheM == null || cache.isRecycled()) return;
        if (!computeMatrix(m)) return;
        // écran ← local ← image tampon : M × cacheM⁻¹
        if (!cacheM.invert(tmp)) return;
        tmp.postConcat(m);
        canvas.drawBitmap(cache, tmp, bitmapPaint);
    }
}
