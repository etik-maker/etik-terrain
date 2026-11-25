package nc.etik.terrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nc.etik.terrain.core.LambertNC;

/** Dossiers de levé et points : interface (dialogues, appareil photo, marqueurs). */
final class SurveyUi {

    static final int REQ_CAMERA = 20;
    static final int REQ_GALLERY = 21;

    interface Host {
        MapLibreMap map();

        Location lastFix();

        void setCrosshairMode(boolean on);

        void refreshTitle();
    }

    private final Activity act;
    private final Host host;
    private final PlanOverlayView overlay;
    private final File root;
    private SurveyStore store;

    // photo en cours
    private SurveyStore.Dossier photoDossier;
    private SurveyStore.Point photoPoint;
    private File photoTmp;
    private TextView photoLabel;

    SurveyUi(Activity act, Host host, PlanOverlayView overlay, File root) {
        this.act = act;
        this.host = host;
        this.overlay = overlay;
        this.root = root;
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String date(long t) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(t));
    }

    void setPlan(String planId) {
        store = planId == null ? null : new SurveyStore(root, planId);
        refreshMarkers();
        host.refreshTitle();
    }

    String activeName() {
        if (store == null) return null;
        SurveyStore.Dossier d = store.active();
        return d == null ? null : d.nom;
    }

    void refreshMarkers() {
        List<PlanOverlayView.Marker> list = new ArrayList<>();
        if (store != null) {
            for (SurveyStore.Dossier d : store.dossiers) {
                if (!d.visible) continue;
                for (SurveyStore.Point p : d.points) {
                    PlanOverlayView.Marker m = new PlanOverlayView.Marker();
                    m.lat = p.lat;
                    m.lon = p.lon;
                    m.color = d.color;
                    m.label = Integer.toString(p.num);
                    m.photo = !p.photos.isEmpty();
                    m.tag = new Object[]{d, p};
                    list.add(m);
                }
            }
        }
        overlay.setMarkers(list);
    }

    private boolean needPlan() {
        if (store == null) {
            Toast.makeText(act, "Ouvrez d'abord un plan (bouton Plans).", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Dossiers

    void showDossiers() {
        if (needPlan()) return;
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);
        TextView hint = new TextView(act);
        hint.setText("Case : afficher / masquer · Toucher le nom : dossier actif (reçoit les nouveaux points) · ⋮ : options");
        hint.setTextSize(12);
        hint.setTextColor(0xFF555555);
        root.addView(hint);
        LinearLayout bar = new LinearLayout(act);
        ListView list = new ListView(act);
        final AlertDialog[] dlg = new AlertDialog[1];
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return store.dossiers.size(); }
            public Object getItem(int i) { return store.dossiers.get(i); }
            public long getItemId(int i) { return i; }

            public View getView(int pos, View convert, ViewGroup parent) {
                SurveyStore.Dossier d = store.dossiers.get(store.dossiers.size() - 1 - pos); // récents en haut
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));
                CheckBox cb = new CheckBox(act);
                cb.setChecked(d.visible);
                cb.setOnCheckedChangeListener((b, c) -> { d.visible = c; store.save(); refreshMarkers(); });
                row.addView(cb);
                View sw = new View(act);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(d.color);
                g.setStroke(dp(1), Color.BLACK);
                sw.setBackground(g);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(18), dp(18));
                sp.setMarginEnd(dp(8));
                row.addView(sw, sp);
                TextView name = new TextView(act);
                boolean active = d == store.active();
                name.setText((active ? "● " : "") + d.nom + "\n" + d.points.size() + " points · " + date(d.date));
                name.setTextSize(15);
                name.setTextColor(active ? 0xFF1F4E79 : 0xFF222222);
                name.setOnClickListener(v -> {
                    store.activeId = d.id;
                    d.visible = true;
                    store.save();
                    refreshMarkers();
                    host.refreshTitle();
                    notifyDataSetChanged();
                });
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button more = new Button(act);
                more.setText("⋮");
                more.setTextSize(18);
                more.setOnClickListener(v -> dossierOptions(d, this));
                row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
                return row;
            }
        };
        list.setAdapter(adapter);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(small("Tout afficher", v -> { for (SurveyStore.Dossier d : store.dossiers) d.visible = true; store.save(); refreshMarkers(); adapter.notifyDataSetChanged(); }));
        bar.addView(small("Tout masquer", v -> { for (SurveyStore.Dossier d : store.dossiers) d.visible = false; store.save(); refreshMarkers(); adapter.notifyDataSetChanged(); }));
        root.addView(bar);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        dlg[0] = new AlertDialog.Builder(act)
                .setTitle("Dossiers de levé")
                .setView(root)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("+ Nouveau dossier", null)
                .create();
        dlg[0].setOnShowListener(x -> dlg[0].getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                askName("Nouveau dossier de levé", defaultName(), n -> {
                    store.create(n);
                    refreshMarkers();
                    host.refreshTitle();
                    adapter.notifyDataSetChanged();
                })));
        dlg[0].show();
    }

    private String defaultName() {
        return "Levé du " + new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date());
    }

    private Button small(String t, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    interface NameCallback {
        void onName(String name);
    }

    private void askName(String title, String def, NameCallback cb) {
        EditText e = new EditText(act);
        e.setText(def);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) cb.onName(n);
                })
                .setNegativeButton("Annuler", null).show();
    }

    private void dossierOptions(SurveyStore.Dossier d, BaseAdapter adapter) {
        String[] items = {"Liste des points (" + d.points.size() + ")", "Renommer", "Changer de couleur", "Supprimer le dossier"};
        new AlertDialog.Builder(act).setTitle(d.nom).setItems(items, (x, w) -> {
            switch (w) {
                case 0: listPoints(d); break;
                case 1: askName("Renommer", d.nom, n -> { d.nom = n; store.save(); host.refreshTitle(); adapter.notifyDataSetChanged(); }); break;
                case 2: {
                    int i = 0;
                    for (int k = 0; k < SurveyStore.COLORS.length; k++) if (SurveyStore.COLORS[k] == d.color) i = k;
                    d.color = SurveyStore.COLORS[(i + 1) % SurveyStore.COLORS.length];
                    store.save();
                    refreshMarkers();
                    adapter.notifyDataSetChanged();
                    break;
                }
                default:
                    new AlertDialog.Builder(act).setTitle("Supprimer « " + d.nom + " » ?")
                            .setMessage("Ses " + d.points.size() + " points et leurs photos seront supprimés définitivement.")
                            .setPositiveButton("Supprimer", (a, b) -> {
                                store.delete(d);
                                refreshMarkers();
                                host.refreshTitle();
                                adapter.notifyDataSetChanged();
                            })
                            .setNegativeButton("Annuler", null).show();
            }
        }).show();
    }

    private void listPoints(SurveyStore.Dossier d) {
        if (d.points.isEmpty()) {
            Toast.makeText(act, "Aucun point dans ce dossier", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[d.points.size()];
        for (int i = 0; i < items.length; i++) {
            SurveyStore.Point p = d.points.get(i);
            items[i] = p.num + " · " + p.code + (p.note.isEmpty() ? "" : " · " + firstLine(p.note))
                    + (p.photos.isEmpty() ? "" : " · " + p.photos.size() + " photo(s)");
        }
        new AlertDialog.Builder(act).setTitle(d.nom).setItems(items, (x, w) -> {
            SurveyStore.Point p = d.points.get(w);
            if (!d.visible) { d.visible = true; store.save(); refreshMarkers(); }
            host.map().easeCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(p.lat, p.lon),
                    Math.max(18, host.map().getCameraPosition().zoom)));
            editPoint(d, p, false);
        }).setNegativeButton("Fermer", null).show();
    }

    private static String firstLine(String s) {
        String l = s.split("\n")[0];
        return l.length() > 40 ? l.substring(0, 40) + "…" : l;
    }

    // ------------------------------------------------------------------ Ajout de point

    /** Bouton « + Point ». */
    void addPoint() {
        if (needPlan()) return;
        if (store.active() == null) {
            askName("Créer un dossier de levé", defaultName(), n -> {
                store.create(n);
                host.refreshTitle();
                addPoint();
            });
            return;
        }
        Location l = host.lastFix();
        String gps = l == null ? "pas encore de position" : String.format(Locale.FRANCE, "précision ±%.0f m", l.getAccuracy());
        new AlertDialog.Builder(act)
                .setTitle("Nouveau point – " + store.active().nom)
                .setItems(new String[]{"À ma position GPS (" + gps + ")", "Viser sur la carte (réticule)"}, (d, w) -> {
                    if (w == 0) pointFromGps();
                    else host.setCrosshairMode(true);
                })
                .setNegativeButton("Annuler", null).show();
    }

    private void pointFromGps() {
        Location l = host.lastFix();
        if (l == null) {
            Toast.makeText(act, "Pas encore de position GPS. Attendez quelques secondes à découvert.", Toast.LENGTH_LONG).show();
            return;
        }
        if (System.currentTimeMillis() - l.getTime() > 60000) {
            Toast.makeText(act, "Position GPS ancienne (> 1 min) : vérifiez le GPS.", Toast.LENGTH_LONG).show();
        }
        Runnable create = () -> {
            SurveyStore.Dossier d = store.active();
            SurveyStore.Point p = store.newPoint(d);
            p.lat = l.getLatitude();
            p.lon = l.getLongitude();
            double[] xy = new double[2];
            LambertNC.fromLonLat(p.lon, p.lat, xy);
            p.x = xy[0];
            p.y = xy[1];
            if (l.hasAltitude()) p.z = l.getAltitude();
            p.acc = l.hasAccuracy() ? l.getAccuracy() : -1;
            p.source = "GPS";
            editPoint(d, p, true);
        };
        if (l.hasAccuracy() && l.getAccuracy() > 15) {
            new AlertDialog.Builder(act).setTitle("Précision faible")
                    .setMessage(String.format(Locale.FRANCE, "La position est connue à ±%.0f m seulement. Enregistrer quand même ?", l.getAccuracy()))
                    .setPositiveButton("Enregistrer", (a, b) -> create.run())
                    .setNegativeButton("Attendre", null).show();
        } else create.run();
    }

    /** Validation du réticule : point au centre de la carte. */
    void pointFromCrosshair() {
        host.setCrosshairMode(false);
        LatLng c = host.map().getCameraPosition().target;
        SurveyStore.Dossier d = store.active();
        SurveyStore.Point p = store.newPoint(d);
        p.lat = c.getLatitude();
        p.lon = c.getLongitude();
        double[] xy = new double[2];
        LambertNC.fromLonLat(p.lon, p.lat, xy);
        p.x = xy[0];
        p.y = xy[1];
        p.source = "Plan";
        editPoint(d, p, true);
    }

    // ------------------------------------------------------------------ Fiche point

    void onMarkerTapped(PlanOverlayView.Marker m) {
        Object[] t = (Object[]) m.tag;
        editPoint((SurveyStore.Dossier) t[0], (SurveyStore.Point) t[1], false);
    }

    private void editPoint(SurveyStore.Dossier d, SurveyStore.Point p, boolean isNew) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView info = new TextView(act);
        info.setTextSize(12);
        info.setTextColor(0xFF555555);
        info.setText(String.format(Locale.FRANCE, "%s · %s\nX %.2f  Y %.2f%s%s", d.nom, date(p.date), p.x, p.y,
                Double.isNaN(p.z) ? "" : String.format(Locale.FRANCE, "  Z≈%.1f", p.z),
                p.source.equals("GPS") ? (p.acc >= 0 ? String.format(Locale.FRANCE, "  ±%.0f m (GPS)", p.acc) : "  (GPS)") : "  (visé sur la carte)"));
        box.addView(info);
        Spinner code = new Spinner(act);
        ArrayAdapter<String> ad = new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, SurveyStore.CODES);
        code.setAdapter(ad);
        for (int i = 0; i < SurveyStore.CODES.length; i++) if (SurveyStore.CODES[i].equals(p.code)) code.setSelection(i);
        box.addView(code);
        EditText note = new EditText(act);
        note.setHint("Note (constat, quantité, action à mener…)");
        note.setText(p.note);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(3);
        note.setGravity(Gravity.TOP | Gravity.START);
        box.addView(note);
        TextView ph = new TextView(act);
        ph.setTextSize(14);
        ph.setPadding(0, dp(8), 0, 0);
        box.addView(ph);
        LinearLayout pb = new LinearLayout(act);
        pb.setOrientation(LinearLayout.HORIZONTAL);
        pb.addView(small("📷 Photo", v -> { apply(p, code, note); takePhoto(d, p, ph, false); }));
        pb.addView(small("Galerie", v -> { apply(p, code, note); takePhoto(d, p, ph, true); }));
        pb.addView(small("Voir", v -> viewPhotos(d, p, ph)));
        box.addView(pb);
        updatePhotoLabel(ph, p);

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle((isNew ? "Nouveau point " : "Point ") + p.num)
                .setView(box)
                .setPositiveButton("Enregistrer", (x, w) -> {
                    apply(p, code, note);
                    if (!d.points.contains(p)) d.points.add(p);
                    store.save();
                    refreshMarkers();
                })
                .setNegativeButton(isNew ? "Annuler" : "Fermer", (x, w) -> {
                    // un point neuf annulé sans photo libère son numéro ; avec photos, il reste enregistré
                    if (isNew && !d.points.contains(p) && p.num == d.nextNum - 1) {
                        d.nextNum--;
                        store.save();
                    }
                });
        if (!isNew) b.setNeutralButton("Supprimer", (x, w) -> new AlertDialog.Builder(act)
                .setTitle("Supprimer le point " + p.num + " ?")
                .setPositiveButton("Supprimer", (a, c) -> { store.deletePoint(d, p); refreshMarkers(); })
                .setNegativeButton("Annuler", null).show());
        b.show();
    }

    private void apply(SurveyStore.Point p, Spinner code, EditText note) {
        p.code = SurveyStore.CODES[Math.max(0, code.getSelectedItemPosition())];
        p.note = note.getText().toString().trim();
    }

    private static void updatePhotoLabel(TextView t, SurveyStore.Point p) {
        t.setText(p.photos.isEmpty() ? "Aucune photo" : p.photos.size() + " photo(s)");
    }

    // ------------------------------------------------------------------ Photos

    private void takePhoto(SurveyStore.Dossier d, SurveyStore.Point p, TextView label, boolean gallery) {
        // le point est enregistré dès la première photo, pour ne rien perdre
        if (!d.points.contains(p)) d.points.add(p);
        store.save();
        refreshMarkers();
        photoDossier = d;
        photoPoint = p;
        photoLabel = label;
        try {
            if (gallery) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                act.startActivityForResult(i, REQ_GALLERY);
            } else {
                photoTmp = new File(store.photoDir, "prise_en_cours.jpg");
                Uri uri = androidx.core.content.FileProvider.getUriForFile(act, act.getPackageName() + ".fichiers", photoTmp);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivityForResult(i, REQ_CAMERA);
            }
        } catch (Exception e) {
            Toast.makeText(act, "Appareil photo indisponible : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Résultat de l'appareil photo ou de la galerie (appelé par l'activité). */
    void onActivityResult(int req, int res, Intent data) {
        if (photoPoint == null || res != Activity.RESULT_OK) return;
        final SurveyStore.Dossier d = photoDossier;
        final SurveyStore.Point p = photoPoint;
        final TextView label = photoLabel;
        final Uri src = req == REQ_GALLERY && data != null ? data.getData() : null;
        final File tmp = photoTmp;
        Toast.makeText(act, "Enregistrement de la photo…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String name = store.newPhotoName(d, p);
            boolean ok;
            try {
                if (src != null) {
                    File t = new File(store.photoDir, "import_en_cours.jpg");
                    try (InputStream in = act.getContentResolver().openInputStream(src); OutputStream out = new FileOutputStream(t)) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                    ok = compress(t, store.photoFile(name));
                    t.delete();
                } else {
                    ok = tmp != null && tmp.exists() && compress(tmp, store.photoFile(name));
                    if (tmp != null) tmp.delete();
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean saved = ok;
            act.runOnUiThread(() -> {
                if (!saved) {
                    Toast.makeText(act, "La photo n'a pas pu être enregistrée.", Toast.LENGTH_LONG).show();
                    return;
                }
                p.photos.add(name);
                store.save();
                refreshMarkers();
                if (label != null) updatePhotoLabel(label, p);
            });
        }).start();
    }

    /** Réduit la photo à 1920 px de côté maximum (JPEG 85 %), en respectant son orientation. */
    private static boolean compress(File in, File out) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(in.getPath(), o);
            int max = Math.max(o.outWidth, o.outHeight);
            if (max <= 0) return false;
            int sample = 1;
            while (max / (sample * 2) >= 1920) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(in.getPath(), o);
            if (b == null) return false;
            int rot = 0;
            try {
                int or = new ExifInterface(in.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {
            }
            float s = Math.min(1f, 1920f / Math.max(b.getWidth(), b.getHeight()));
            Matrix m = new Matrix();
            m.postScale(s, s);
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            try (OutputStream os = new FileOutputStream(out)) {
                r.compress(Bitmap.CompressFormat.JPEG, 85, os);
            }
            if (r != b) r.recycle();
            b.recycle();
            return out.length() > 0;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    private void viewPhotos(SurveyStore.Dossier d, SurveyStore.Point p, TextView label) {
        if (p.photos.isEmpty()) {
            Toast.makeText(act, "Aucune photo", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[p.photos.size()];
        for (int i = 0; i < items.length; i++) items[i] = "Photo " + (i + 1);
        new AlertDialog.Builder(act).setTitle("Photos du point " + p.num).setItems(items, (x, w) -> {
            String name = p.photos.get(w);
            new AlertDialog.Builder(act).setTitle("Photo " + (w + 1))
                    .setPositiveButton("Ouvrir", (a, b) -> open(store.photoFile(name)))
                    .setNeutralButton("Supprimer", (a, b) -> {
                        p.photos.remove(name);
                        store.photoFile(name).delete();
                        store.save();
                        refreshMarkers();
                        updatePhotoLabel(label, p);
                    })
                    .setNegativeButton("Fermer", null).show();
        }).show();
    }

    private void open(File f) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(act, act.getPackageName() + ".fichiers", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "image/jpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "Aucune appli pour afficher la photo", Toast.LENGTH_SHORT).show();
        }
    }
}
