package nc.etik.terrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** Visionneuse plein écran des photos d'un point : zoom à deux doigts, double tap, balayage. */
final class PhotoViewer {

    interface OnDelete {
        void delete(int index);
    }

    private final Activity act;
    private final List<File> files;
    private final String title;
    private final OnDelete onDelete;
    private int index;
    private ZoomImageView image;
    private TextView caption;
    private Dialog dialog;
    private Bitmap current;

    PhotoViewer(Activity act, List<File> files, int start, String title, OnDelete onDelete) {
        this.act = act;
        this.files = files;
        this.index = start;
        this.title = title;
        this.onDelete = onDelete;
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    void show() {
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(0xFF000000);
        image = new ZoomImageView(act);
        image.listener = dir -> go(index + dir);
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        caption = new TextView(act);
        caption.setTextColor(0xFFFFFFFF);
        caption.setTextSize(15);
        caption.setBackgroundColor(0x99000000);
        caption.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(caption, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0x99000000);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.addView(button("◀", v -> go(index - 1)));
        bar.addView(button("▶", v -> go(index + 1)));
        if (onDelete != null) bar.addView(button("Supprimer", v -> new AlertDialog.Builder(act)
                .setTitle("Supprimer cette photo ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    onDelete.delete(index);
                    files.remove(index);
                    if (files.isEmpty()) dialog.dismiss();
                    else go(Math.min(index, files.size() - 1));
                })
                .setNegativeButton("Annuler", null).show()));
        bar.addView(button("Fermer", v -> dialog.dismiss()));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            image.setImageDrawable(null);
            if (current != null) current.recycle();
            current = null;
        });
        dialog.show();
        go(index);
    }

    private Button button(String t, android.view.View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1));
        return b;
    }

    private void go(int i) {
        if (files.isEmpty()) return;
        if (i < 0) i = files.size() - 1;
        if (i >= files.size()) i = 0;
        index = i;
        caption.setText(title + " · photo " + (index + 1) + "/" + files.size());
        Bitmap old = current;
        current = decode(files.get(index));
        image.setImageBitmap(current);
        image.resetZoom();
        if (old != null && old != current) old.recycle();
    }

    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int max = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (max / (sample * 2) >= 2400) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            o.inSampleSize = sample * 2;
            return BitmapFactory.decodeFile(f.getPath(), o);
        }
    }

    /** Petite image pour les vignettes de la fiche. */
    static Bitmap thumbnail(File f, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int max = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (max / (sample * 2) >= size) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** ImageView avec zoom à deux doigts, déplacement, double tap et balayage gauche/droite. */
    static final class ZoomImageView extends ImageView {
        interface SwipeListener {
            void onSwipe(int dir);
        }

        SwipeListener listener;
        private final Matrix m = new Matrix();
        private float fitScale = 1, scale = 1;
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        ZoomImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float f = d.getScaleFactor();
                    float ns = Math.max(fitScale, Math.min(fitScale * 8, scale * f));
                    f = ns / scale;
                    scale = ns;
                    m.postScale(f, f, d.getFocusX(), d.getFocusY());
                    clamp();
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scale > fitScale * 1.01f) {
                        m.postTranslate(-dx, -dy);
                        clamp();
                    }
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale > fitScale * 1.01f) resetZoom();
                    else {
                        float f = 2.5f;
                        scale = fitScale * f;
                        m.postScale(f, f, e.getX(), e.getY());
                        clamp();
                    }
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (scale <= fitScale * 1.01f && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 800 && listener != null) {
                        listener.onSwipe(vx < 0 ? 1 : -1);
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            resetZoom();
        }

        void resetZoom() {
            if (getDrawable() == null || getWidth() == 0) return;
            float iw = getDrawable().getIntrinsicWidth(), ih = getDrawable().getIntrinsicHeight();
            fitScale = Math.min(getWidth() / iw, getHeight() / ih);
            scale = fitScale;
            m.reset();
            m.postScale(fitScale, fitScale);
            m.postTranslate((getWidth() - iw * fitScale) / 2, (getHeight() - ih * fitScale) / 2);
            setImageMatrix(m);
        }

        /** Garde l'image dans le cadre (centrée si plus petite que l'écran). */
        private void clamp() {
            if (getDrawable() == null) return;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            m.mapRect(r);
            float dx = 0, dy = 0, w = getWidth(), h = getHeight();
            if (r.width() <= w) dx = (w - r.width()) / 2 - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < w) dx = w - r.right;
            if (r.height() <= h) dy = (h - r.height()) / 2 - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < h) dy = h - r.bottom;
            m.postTranslate(dx, dy);
            setImageMatrix(m);
        }
    }
}
