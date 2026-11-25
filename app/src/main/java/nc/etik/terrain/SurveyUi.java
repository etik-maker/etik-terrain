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
    static final int REQ_SAVE_EXPORT = 22;
    private File lastExport;

    interface Host {
        MapLibreMap map();

        Location lastFix();

        void setCrosshairMode(boolean on);

        void refreshTitle();

        android.widget.Button trackButton();

        /** Demande les autorisations nécessaires à la trace (notifications) puis relance l'action. */
        boolean ensureTrackPermissions(Runnable then);
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
    private LinearLayout photoStrip;

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
        traceCache.clear();
        refreshMarkers();
        updateTrackButton();
        host.refreshTitle();
    }

    String activeName() {
        if (store == null) return null;
        SurveyStore.Dossier d = store.active();
        return d == null ? null : d.nom;
    }

    private final java.util.Map<String, double[]> traceCache = new java.util.HashMap<>();

    private double[] traceLatLon(SurveyStore.Trace t) {
        double[] c = traceCache.get(t.id);
        if (c != null) return c;
        List<double[]> pts = store.readTrace(t);
        c = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            c[2 * i] = pts.get(i)[0];
            c[2 * i + 1] = pts.get(i)[1];
        }
        if (t.fin > 0) traceCache.put(t.id, c);
        return c;
    }

    void refreshMarkers() {
        List<PlanOverlayView.TrackLine> tl = new ArrayList<>();
        if (store != null) {
            for (SurveyStore.Dossier d : store.dossiers) {
                if (!d.visible) continue;
                for (SurveyStore.Trace t : d.traces) {
                    if (t.fin == 0 && TrackService.STATE.running && t == recording) continue; // dessinée en direct
                    PlanOverlayView.TrackLine l = new PlanOverlayView.TrackLine();
                    l.color = d.color;
                    l.latlon = traceLatLon(t);
                    tl.add(l);
                }
            }
        }
        overlay.setTracks(tl);
        refreshPointMarkers();
    }

    private void refreshPointMarkers() {
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
                .setNegativeButton("Exporter…", (x, w) -> showExport())
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
        String[] items = {"Liste des points (" + d.points.size() + ")", "Liste des traces (" + d.traces.size() + ")", "Renommer", "Changer de couleur", "Supprimer le dossier"};
        new AlertDialog.Builder(act).setTitle(d.nom).setItems(items, (x, w) -> {
            switch (w) {
                case 0: listPoints(d); break;
                case 1: listTraces(d); break;
                case 2: askName("Renommer", d.nom, n -> { d.nom = n; store.save(); host.refreshTitle(); adapter.notifyDataSetChanged(); }); break;
                case 3: {
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
                            .setMessage("Ses " + d.points.size() + " points, leurs photos et ses " + d.traces.size() + " traces seront supprimés définitivement.")
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

    // ------------------------------------------------------------------ Export

    void showExport() {
        if (needPlan()) return;
        if (store.dossiers.isEmpty()) {
            Toast.makeText(act, "Aucun dossier de levé à exporter", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = store.dossiers.size();
        String[] names = new String[n];
        boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            SurveyStore.Dossier d = store.dossiers.get(i);
            names[i] = d.nom + " (" + d.points.size() + " pts, " + d.traces.size() + " traces)";
            checked[i] = d.visible;
        }
        final boolean[] photos = {true};
        new AlertDialog.Builder(act)
                .setTitle("Exporter les levés (DXF + CSV + photos)")
                .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("Envoyer / Dropbox…", (d, w) -> buildExport(checked, photos[0], false))
                .setNeutralButton("Enregistrer dans…", (d, w) -> buildExport(checked, photos[0], true))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void buildExport(boolean[] checked, boolean withPhotos, boolean saveAs) {
        nc.etik.terrain.core.DxfExport e = new nc.etik.terrain.core.DxfExport();
        e.planName = overlayPlanName();
        try (InputStream in = act.getAssets().open("modele_r2000.dxf")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int k;
            while ((k = in.read(b)) > 0) bo.write(b, 0, k);
            e.template = bo.toString("UTF-8");
        } catch (Exception ex) {
            Toast.makeText(act, "Modèle DXF introuvable", Toast.LENGTH_LONG).show();
            return;
        }
        int nPts = 0, nTr = 0;
        for (int i = 0; i < checked.length; i++) {
            if (!checked[i]) continue;
            SurveyStore.Dossier d = store.dossiers.get(i);
            nc.etik.terrain.core.DxfExport.Dossier ed = new nc.etik.terrain.core.DxfExport.Dossier();
            ed.nom = d.nom;
            ed.color = d.color & 0xFFFFFF;
            e.dossiers.add(ed);
            for (SurveyStore.Point p : d.points) {
                nc.etik.terrain.core.DxfExport.Pt q = new nc.etik.terrain.core.DxfExport.Pt();
                q.dossier = d.nom; q.num = p.num; q.x = p.x; q.y = p.y; q.z = p.z; q.acc = p.acc;
                q.source = p.source; q.code = p.code; q.note = p.note; q.date = p.date;
                for (String ph : p.photos) q.photos.add(store.photoFile(ph));
                e.points.add(q);
                nPts++;
            }
            for (SurveyStore.Trace t : d.traces) {
                if (t == recording) continue;
                nc.etik.terrain.core.DxfExport.Tr q = new nc.etik.terrain.core.DxfExport.Tr();
                q.dossier = d.nom; q.nom = t.nom; q.debut = t.debut;
                double[] xy = new double[2];
                for (double[] r : store.readTrace(t)) {
                    LambertNC.fromLonLat(r[1], r[0], xy);
                    q.xyz.add(new double[]{xy[0], xy[1], r[2]});
                }
                e.traces.add(q);
                nTr++;
            }
        }
        if (e.dossiers.isEmpty()) {
            Toast.makeText(act, "Cochez au moins un dossier", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRANCE).format(new Date());
        String base = nc.etik.terrain.core.DxfExport.sanitizeFileName(stripExt(e.planName)) + "_releves_" + stamp;
        File dir = new File(act.getCacheDir(), "export");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File zip = new File(dir, base + ".zip");
        final int fp = nPts, ft = nTr;
        Toast.makeText(act, "Préparation de l'export…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try (OutputStream os = new FileOutputStream(zip)) {
                e.writeZip(os, base, withPhotos);
            } catch (Exception ex) {
                err = ex.getMessage();
            }
            final String error = err;
            act.runOnUiThread(() -> {
                if (error != null) {
                    Toast.makeText(act, "Export impossible : " + error, Toast.LENGTH_LONG).show();
                    return;
                }
                lastExport = zip;
                String msg = fp + " points, " + ft + " traces";
                if (saveAs) {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, zip.getName());
                    act.startActivityForResult(i, REQ_SAVE_EXPORT);
                } else {
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(act, act.getPackageName() + ".fichiers", zip);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.putExtra(Intent.EXTRA_SUBJECT, "Levés " + e.planName + " – " + msg);
                    i.putExtra(Intent.EXTRA_TEXT, "Export ETIK Terrain : " + msg + ". DXF en Lambert NC (RGNC91-93), CSV et photos.");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    act.startActivity(Intent.createChooser(i, "Envoyer l'export (" + msg + ")"));
                }
            });
        }).start();
    }

    /** Résultat de « Enregistrer dans… ». */
    void onSaveExport(Intent data) {
        if (data == null || data.getData() == null || lastExport == null) return;
        Uri dst = data.getData();
        new Thread(() -> {
            boolean ok = true;
            try (InputStream in = new java.io.FileInputStream(lastExport); OutputStream out = act.getContentResolver().openOutputStream(dst)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } catch (Exception ex) {
                ok = false;
            }
            final boolean r = ok;
            act.runOnUiThread(() -> Toast.makeText(act, r ? "Export enregistré" : "Enregistrement impossible", Toast.LENGTH_LONG).show());
        }).start();
    }

    private String overlayPlanName() {
        nc.etik.terrain.core.PlanData p = overlay.getPlan();
        return p == null ? "plan" : p.name;
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    // ------------------------------------------------------------------ Traces

    private SurveyStore.Trace recording;
    private SurveyStore.Dossier recordingDossier;

    private void listTraces(SurveyStore.Dossier d) {
        if (d.traces.isEmpty()) {
            Toast.makeText(act, "Aucune trace dans ce dossier", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[d.traces.size()];
        for (int i = 0; i < items.length; i++) {
            SurveyStore.Trace t = d.traces.get(i);
            items[i] = t.nom + " · " + String.format(Locale.FRANCE, "%.2f km", t.longueur / 1000) + " · " + date(t.debut)
                    + (t.fin == 0 ? (t == recording ? " · en cours" : " · interrompue") : "");
        }
        new AlertDialog.Builder(act).setTitle("Traces – " + d.nom).setItems(items, (x, w) -> {
            SurveyStore.Trace t = d.traces.get(w);
            new AlertDialog.Builder(act).setTitle(t.nom)
                    .setItems(new String[]{"Voir sur la carte", "Renommer", "Supprimer"}, (a, b) -> {
                        if (b == 0) {
                            if (!d.visible) { d.visible = true; store.save(); refreshMarkers(); }
                            double[] ll = traceLatLon(t);
                            if (ll.length >= 4) {
                                org.maplibre.android.geometry.LatLngBounds.Builder bb = new org.maplibre.android.geometry.LatLngBounds.Builder();
                                for (int i = 0; i < ll.length; i += 2) bb.include(new LatLng(ll[i], ll[i + 1]));
                                host.map().easeCamera(CameraUpdateFactory.newLatLngBounds(bb.build(), dp(60)), 600);
                            }
                        } else if (b == 1) {
                            askName("Renommer la trace", t.nom, n -> { t.nom = n; store.save(); });
                        } else if (t != recording) {
                            new AlertDialog.Builder(act).setTitle("Supprimer « " + t.nom + " » ?")
                                    .setPositiveButton("Supprimer", (q, r) -> { store.deleteTrace(d, t); traceCache.remove(t.id); refreshMarkers(); })
                                    .setNegativeButton("Annuler", null).show();
                        } else Toast.makeText(act, "Arrêtez d'abord l'enregistrement", Toast.LENGTH_SHORT).show();
                    }).show();
        }).setNegativeButton("Fermer", null).show();
    }

    private boolean skipPermission;

    /** Relance après la demande d'autorisation de notification (acceptée ou non). */
    void trackButtonForce() {
        skipPermission = true;
        trackButton();
        skipPermission = false;
    }

    /** Bouton « Trace » : démarrer, ou pause / arrêt si une trace est en cours. */
    void trackButton() {
        if (TrackService.STATE.running) {
            boolean paused = TrackService.STATE.paused;
            new AlertDialog.Builder(act)
                    .setTitle("Trace : " + TrackService.STATE.name)
                    .setMessage(TrackService.summary())
                    .setPositiveButton("Arrêter et enregistrer", (d, w) -> stopTrack())
                    .setNeutralButton(paused ? "Reprendre" : "Pause", (d, w) -> {
                        TrackService.STATE.paused = !paused;
                        updateTrackButton();
                    })
                    .setNegativeButton("Continuer", null).show();
            return;
        }
        if (needPlan()) return;
        if (!skipPermission && !host.ensureTrackPermissions(this::trackButton)) return;
        if (store.active() == null) {
            askName("Créer un dossier de levé", defaultName(), n -> {
                store.create(n);
                host.refreshTitle();
                trackButton();
            });
            return;
        }
        String def = "Trace " + new SimpleDateFormat("HH'h'mm", Locale.FRANCE).format(new Date());
        askName("Nouvelle trace – " + store.active().nom, def, n -> startTrack(n));
    }

    private void startTrack(String name) {
        SurveyStore.Dossier d = store.active();
        SurveyStore.Trace t = new SurveyStore.Trace();
        t.id = "T" + Long.toString(System.currentTimeMillis(), 36);
        t.nom = name;
        t.debut = System.currentTimeMillis();
        d.traces.add(t);
        store.save(); // la trace existe même si l'appli s'arrête en cours de route
        recording = t;
        recordingDossier = d;
        TrackService.STATE.listener = this::onTrackUpdate;
        TrackService.start(act, store.traceFile(t), name);
        updateTrackButton();
        Toast.makeText(act, "Enregistrement démarré : il continue écran éteint", Toast.LENGTH_LONG).show();
    }

    private void stopTrack() {
        SurveyStore.Trace t = recording;
        SurveyStore.Dossier d = recordingDossier;
        TrackService.STATE.listener = null;
        TrackService.stop(act);
        if (t != null && d != null) {
            t.fin = System.currentTimeMillis();
            t.longueur = TrackService.STATE.length;
            t.nbPoints = TrackService.STATE.count;
            if (t.nbPoints < 2) {
                store.deleteTrace(d, t);
                Toast.makeText(act, "Trace vide (pas assez de positions GPS) : non enregistrée", Toast.LENGTH_LONG).show();
            } else {
                store.save();
                Toast.makeText(act, String.format(Locale.FRANCE, "Trace enregistrée : %.2f km", t.longueur / 1000), Toast.LENGTH_LONG).show();
            }
        }
        recording = null;
        recordingDossier = null;
        overlay.setLiveTrack(null);
        refreshMarkers();
        updateTrackButton();
    }

    private void onTrackUpdate() {
        act.runOnUiThread(() -> {
            if (recordingDossier != null) {
                PlanOverlayView.TrackLine l = new PlanOverlayView.TrackLine();
                l.color = recordingDossier.color;
                synchronized (TrackService.STATE) {
                    List<double[]> pts = TrackService.STATE.latlon;
                    l.latlon = new double[pts.size() * 2];
                    for (int i = 0; i < pts.size(); i++) {
                        l.latlon[2 * i] = pts.get(i)[0];
                        l.latlon[2 * i + 1] = pts.get(i)[1];
                    }
                }
                overlay.setLiveTrack(recordingDossier.visible ? l : null);
            }
            if (!TrackService.STATE.running && recording != null) stopTrack();
            else updateTrackButton();
        });
    }

    /** Remet à jour le bouton (et se raccroche à une trace en cours après un redémarrage de l'activité). */
    void updateTrackButton() {
        android.widget.Button b = host.trackButton();
        if (TrackService.STATE.running) {
            if (recording == null && store != null) {
                // activité recréée pendant l'enregistrement : on retrouve la trace en cours
                for (SurveyStore.Dossier d : store.dossiers) for (SurveyStore.Trace t : d.traces)
                    if (t.fin == 0 && TrackService.STATE.file != null && store.traceFile(t).equals(TrackService.STATE.file)) {
                        recording = t;
                        recordingDossier = d;
                    }
                TrackService.STATE.listener = this::onTrackUpdate;
            }
            b.setText(String.format(Locale.FRANCE, "%s %.2f km", TrackService.STATE.paused ? "❚❚" : "■", TrackService.STATE.length / 1000));
            b.setBackgroundResource(R.drawable.btn_record);
            b.setTextColor(0xFFFFFFFF);
        } else {
            b.setText("● Trace");
            b.setBackgroundResource(R.drawable.btn_bg);
            b.setTextColor(0xFF1F4E79);
        }
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
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        LinearLayout strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(strip);
        box.addView(hs);
        LinearLayout pb = new LinearLayout(act);
        pb.setOrientation(LinearLayout.HORIZONTAL);
        pb.addView(small("📷 Prendre une photo", v -> { apply(p, code, note); takePhoto(d, p, ph, strip, false); }));
        pb.addView(small("Galerie", v -> { apply(p, code, note); takePhoto(d, p, ph, strip, true); }));
        box.addView(pb);
        refreshPhotos(ph, strip, d, p);

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

    /** Libellé et vignettes des photos d'un point (toucher une vignette ouvre la visionneuse). */
    private void refreshPhotos(TextView t, LinearLayout strip, SurveyStore.Dossier d, SurveyStore.Point p) {
        t.setText(p.photos.isEmpty() ? "Aucune photo" : p.photos.size() + " photo(s) – touchez pour agrandir");
        if (strip == null) return;
        strip.removeAllViews();
        int size = dp(84);
        for (int i = 0; i < p.photos.size(); i++) {
            final int idx = i;
            android.widget.ImageView iv = new android.widget.ImageView(act);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFDDDDDD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(dp(6));
            lp.topMargin = dp(6);
            strip.addView(iv, lp);
            iv.setOnClickListener(v -> viewPhotos(d, p, t, strip, idx));
            File f = store.photoFile(p.photos.get(i));
            new Thread(() -> {
                Bitmap b = PhotoViewer.thumbnail(f, size);
                if (b != null) act.runOnUiThread(() -> iv.setImageBitmap(b));
            }).start();
        }
    }

    // ------------------------------------------------------------------ Photos

    private void takePhoto(SurveyStore.Dossier d, SurveyStore.Point p, TextView label, LinearLayout strip, boolean gallery) {
        // le point est enregistré dès la première photo, pour ne rien perdre
        if (!d.points.contains(p)) d.points.add(p);
        store.save();
        refreshMarkers();
        photoDossier = d;
        photoPoint = p;
        photoLabel = label;
        photoStrip = strip;
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
        final LinearLayout strip = photoStrip;
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
                if (label != null) refreshPhotos(label, strip, d, p);
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

    private void viewPhotos(SurveyStore.Dossier d, SurveyStore.Point p, TextView label, LinearLayout strip, int start) {
        if (p.photos.isEmpty()) return;
        List<File> files = new ArrayList<>();
        for (String n : p.photos) files.add(store.photoFile(n));
        new PhotoViewer(act, files, start, "Point " + p.num + " · " + p.code, i -> {
            String name = p.photos.remove(i);
            store.photoFile(name).delete();
            store.save();
            refreshMarkers();
            refreshPhotos(label, strip, d, p);
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
