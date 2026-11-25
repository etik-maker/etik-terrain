package nc.etik.terrain;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import nc.etik.terrain.core.LambertNC;
import nc.etik.terrain.core.PlanData;
import nc.etik.terrain.core.TileCoverage;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 10;
    private static final int REQ_LOC = 11;

    private MapView mapView;
    private MapLibreMap map;
    private PlanOverlayView overlay;
    private TextView gpsBar, planName;
    private PlanStore store;
    private Basemaps basemaps;
    private SharedPreferences prefs;
    private String planId;
    private Location lastFix;
    private LocationManager lm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Uri pendingUri;
    private TileStore tiles;
    private SurveyUi survey;
    private static final double OFFLINE_BUFFER_M = 50;

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (lastFix != null && LocationManager.NETWORK_PROVIDER.equals(l.getProvider())
                    && LocationManager.GPS_PROVIDER.equals(lastFix.getProvider())
                    && l.getTime() - lastFix.getTime() < 10000) return; // on garde le GPS s'il est récent
            lastFix = l;
            updateGpsBar();
        }

        @Override
        public void onProviderEnabled(String p) {}

        @Override
        public void onProviderDisabled(String p) {}

        @Override
        public void onStatusChanged(String p, int s, Bundle b) {}
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        store = new PlanStore(this);
        basemaps = new Basemaps(this);
        tiles = new TileStore(new File(getFilesDir(), "tuiles"));
        for (Basemaps.Fond f : basemaps.fonds) if (!f.url.isEmpty()) tiles.register(f.id, f.url, true);
        mapView = findViewById(R.id.map);
        overlay = findViewById(R.id.overlay);
        gpsBar = findViewById(R.id.gpsBar);
        planName = findViewById(R.id.planName);
        mapView.onCreate(saved);

        findViewById(R.id.btnPlans).setOnClickListener(v -> showPlans());
        findViewById(R.id.btnLayers).setOnClickListener(v -> showLayers("Calques"));
        findViewById(R.id.btnBasemap).setOnClickListener(v -> chooseBasemap());
        findViewById(R.id.btnFit).setOnClickListener(v -> fitPlan());
        findViewById(R.id.btnLocate).setOnClickListener(v -> locate());
        findViewById(R.id.btnNorth).setOnClickListener(v -> northUp());
        survey = new SurveyUi(this, new SurveyUi.Host() {
            @Override public MapLibreMap map() { return map; }
            @Override public Location lastFix() { return lastFix; }
            @Override public void setCrosshairMode(boolean on) { crosshairMode(on); }
            @Override public void refreshTitle() { updateTitle(); }
        }, overlay, new File(getFilesDir(), "releves"));
        findViewById(R.id.btnSurvey).setOnClickListener(v -> survey.showDossiers());
        findViewById(R.id.btnAddPoint).setOnClickListener(v -> survey.addPoint());
        findViewById(R.id.btnCrossOk).setOnClickListener(v -> survey.pointFromCrosshair());
        findViewById(R.id.btnCrossCancel).setOnClickListener(v -> crosshairMode(false));

        mapView.getMapAsync(m -> {
            map = m;
            overlay.setMap(m);
            m.getUiSettings().setCompassEnabled(true);
            m.getUiSettings().setAttributionEnabled(true);
            m.getUiSettings().setLogoEnabled(false);
            m.addOnCameraMoveListener(overlay::invalidate);
            m.addOnCameraIdleListener(overlay::onCameraIdle);
            m.addOnMapClickListener(ll -> {
                android.graphics.PointF sp = m.getProjection().toScreenLocation(ll);
                PlanOverlayView.Marker mk = overlay.markerAt(sp.x, sp.y);
                if (mk == null) return false;
                survey.onMarkerTapped(mk);
                return true;
            });
            // Vue de départ : Nouvelle-Calédonie
            m.setCameraPosition(new CameraPosition.Builder().target(new LatLng(-21.5, 165.8)).zoom(6.5).build());
            applyBasemap(prefs.getString("fond", "georep_imagerie"));
            restoreLastPlan();
            if (pendingUri != null) {
                runImport(pendingUri);
                pendingUri = null;
            }
        });

        Uri incoming = incomingUri(getIntent());
        if (incoming != null) pendingUri = incoming;
        requestLocation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = incomingUri(intent);
        if (u != null) {
            if (map != null) runImport(u);
            else pendingUri = u;
        }
    }

    private static Uri incomingUri(Intent i) {
        if (i == null) return null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) return i.getData();
        if (Intent.ACTION_SEND.equals(i.getAction())) return i.getParcelableExtra(Intent.EXTRA_STREAM);
        return null;
    }

    // ------------------------------------------------------------------ Fonds de carte

    private void applyBasemap(String id) {
        Basemaps.Fond f = basemaps.get(id);
        prefs.edit().putString("fond", f.id).apply();
        overlay.setDarkBackground(f.dark);
        String url = f.url.isEmpty() ? null : tiles.localUrl(f.id);
        map.setStyle(new Style.Builder().fromJson(Basemaps.styleJson(f, url)), style -> enableLocationComponent(style));
    }

    private void chooseBasemap() {
        if (map == null) return;
        String cur = prefs.getString("fond", "georep_imagerie");
        String[] names = new String[basemaps.fonds.size()];
        int sel = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = basemaps.fonds.get(i).nom;
            if (basemaps.fonds.get(i).id.equals(cur)) sel = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Fond de carte")
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    applyBasemap(basemaps.fonds.get(w).id);
                    d.dismiss();
                })
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Hors ligne…", (d, w) -> showOffline())
                .show();
    }

    // ------------------------------------------------------------------ Fonds hors ligne

    private static String mo(long bytes) {
        return String.format(Locale.FRANCE, "%.0f Mo", bytes / 1048576.0);
    }

    private void showOffline() {
        Basemaps.Fond f = basemaps.get(prefs.getString("fond", "georep_imagerie"));
        PlanData p = overlay.getPlan();
        if (f.url.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Hors ligne").setMessage("Ce fond est un fond uni : il fonctionne déjà sans réseau.").setPositiveButton("OK", null).show();
            return;
        }
        new Thread(() -> {
            long[] use = tiles.usage(f.id);
            Set<Long> set = null;
            int have = 0;
            if (p != null && f.horsLigne) {
                set = TileCoverage.compute(p, OFFLINE_BUFFER_M, 8, 14, f.maxzoom);
                for (long k : set) {
                    File tf = tiles.tileFile(f.id, TileCoverage.z(k), TileCoverage.x(k), TileCoverage.y(k));
                    if (tf.exists()) have++;
                }
            }
            final Set<Long> cover = set;
            final int already = have;
            ui.post(() -> offlineDialog(f, p, cover, already, use));
        }).start();
    }

    private void offlineDialog(Basemaps.Fond f, PlanData p, Set<Long> cover, int already, long[] use) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fond : ").append(f.nom).append("\n");
        sb.append("Déjà sur le téléphone pour ce fond : ").append(mo(use[0])).append(" (").append(use[1]).append(" images)\n\n");
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Fond hors ligne");
        if (!f.horsLigne) {
            sb.append("Ce fond ne peut pas être téléchargé à l'avance (conditions d'utilisation). Seules les zones affichées récemment peuvent rester visibles sans réseau.");
        } else if (p == null) {
            sb.append("Ouvrez d'abord un plan : le fond est téléchargé sur une bande de ")
                    .append((int) OFFLINE_BUFFER_M).append(" m autour de ses objets.");
        } else if (cover.isEmpty()) {
            sb.append("Aucun calque affiché : affichez les calques à couvrir.");
        } else {
            int missing = cover.size() - already;
            sb.append("Plan : ").append(p.name).append("\n");
            sb.append("Bande de ").append((int) OFFLINE_BUFFER_M).append(" m autour des calques affichés, zoom jusqu'à ").append(f.maxzoom).append(".\n");
            sb.append(cover.size()).append(" images, dont ").append(already).append(" déjà présentes.\n");
            sb.append("À télécharger : ").append(missing).append(" images, environ ").append(mo(missing * 20_000L)).append(".\n\n");
            sb.append("Astuce : masquez d'abord les calques hors chantier (profils PRTR, cartouches) pour ne pas télécharger inutilement.");
            if (missing > 0) b.setPositiveButton("Télécharger", (d, w) -> downloadTiles(f, cover));
        }
        b.setMessage(sb);
        if (use[1] > 0) b.setNeutralButton("Vider ce fond", (d, w) -> new AlertDialog.Builder(this)
                .setMessage("Supprimer les " + mo(use[0]) + " d'images enregistrées pour « " + f.nom + " » ?")
                .setPositiveButton("Supprimer", (x, y) -> new Thread(() -> {
                    tiles.clear(f.id);
                    ui.post(() -> Toast.makeText(this, "Fond vidé", Toast.LENGTH_SHORT).show());
                }).start())
                .setNegativeButton("Annuler", null).show());
        b.setNegativeButton("Fermer", null);
        b.show();
    }

    private void downloadTiles(Basemaps.Fond f, Set<Long> cover) {
        List<Long> todo = new ArrayList<>();
        for (long k : cover) {
            if (!tiles.tileFile(f.id, TileCoverage.z(k), TileCoverage.x(k), TileCoverage.y(k)).exists()) todo.add(k);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad / 2);
        TextView label = new TextView(this);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(todo.size());
        box.addView(label);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AtomicBoolean cancel = new AtomicBoolean();
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Téléchargement du fond").setView(box).setCancelable(false)
                .setNegativeButton("Annuler", (d, w) -> cancel.set(true)).create();
        dlg.show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        ExecutorService ex = Executors.newFixedThreadPool(6);
        for (long k : todo) {
            ex.execute(() -> {
                if (cancel.get()) return;
                long n = tiles.download(f.id, TileCoverage.z(k), TileCoverage.x(k), TileCoverage.y(k));
                if (n < 0) failed.incrementAndGet(); else bytes.addAndGet(n);
                int d = done.incrementAndGet();
                if (d % 10 == 0 || d == todo.size()) ui.post(() -> {
                    bar.setProgress(d);
                    label.setText(d + " / " + todo.size() + " images – " + mo(bytes.get()));
                });
            });
        }
        ex.shutdown();
        new Thread(() -> {
            try {
                ex.awaitTermination(6, TimeUnit.HOURS);
            } catch (InterruptedException ignored) {
            }
            ui.post(() -> {
                dlg.dismiss();
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                String msg = (cancel.get() ? "Téléchargement interrompu. " : "Fond prêt pour le mode sans réseau. ")
                        + (done.get() - failed.get()) + " images enregistrées (" + mo(bytes.get()) + ")."
                        + (failed.get() > 0 ? "\n" + failed.get() + " images n'ont pas pu être téléchargées (hors couverture du fond ou réseau) : relancez pour réessayer." : "");
                new AlertDialog.Builder(this).setTitle("Fond hors ligne").setMessage(msg).setPositiveButton("OK", null).show();
            });
        }).start();
    }

    // ------------------------------------------------------------------ Plans

    private void showPlans() {
        List<PlanStore.Entry> list = store.entries();
        String[] items = new String[list.size() + 2];
        items[0] = "Importer un plan DXF (Dropbox, téléphone…)";
        for (int i = 0; i < list.size(); i++) items[i + 1] = (list.get(i).id.equals(planId) ? "● " : "") + list.get(i).name;
        items[items.length - 1] = "Supprimer un plan de l'appli…";
        new AlertDialog.Builder(this)
                .setTitle("Plans")
                .setItems(items, (d, w) -> {
                    if (w == 0) pickDxf();
                    else if (w == items.length - 1) deletePlan(list);
                    else openPlan(list.get(w - 1));
                })
                .show();
    }

    private void deletePlan(List<PlanStore.Entry> list) {
        if (list.isEmpty()) return;
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = list.get(i).name;
        new AlertDialog.Builder(this).setTitle("Supprimer un plan")
                .setItems(items, (d, w) -> {
                    PlanStore.Entry e = list.get(w);
                    new AlertDialog.Builder(this).setMessage("Supprimer « " + e.name + " » de l'appli ? Le DXF d'origine n'est pas touché. Les dossiers de levé de ce plan sont conservés sur le téléphone.")
                            .setPositiveButton("Supprimer", (x, y) -> {
                                store.delete(e.id);
                                if (e.id.equals(planId)) {
                                    planId = null;
                                    overlay.setPlan(null);
                                    survey.setPlan(null);
                                    planName.setText("Aucun plan ouvert");
                                }
                            })
                            .setNegativeButton("Annuler", null).show();
                }).show();
    }

    private void pickDxf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) runImport(data.getData());
        if (req == SurveyUi.REQ_CAMERA || req == SurveyUi.REQ_GALLERY) survey.onActivityResult(req, res, data);
    }

    private void runImport(Uri uri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad / 2);
        TextView label = new TextView(this);
        label.setText("Préparation…");
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(label);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Import du plan").setView(box).setCancelable(false).create();
        dlg.show();
        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
                PlanData p = store.importDxf(uri, (step, pct) -> ui.post(() -> {
                    label.setText(step + " – " + pct + " %");
                    bar.setProgress(pct);
                }));
                String id = store.current();
                long secs = (System.currentTimeMillis() - t0) / 1000;
                ui.post(() -> {
                    dlg.dismiss();
                    showPlan(id, p);
                    fitPlan();
                    Toast.makeText(this, "Plan importé en " + secs + " s : " + store.lastImportStats, Toast.LENGTH_LONG).show();
                    warnXrefs(p);
                    showLayers("Calques à afficher");
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    dlg.dismiss();
                    String msg = e instanceof OutOfMemoryError
                            ? "Mémoire insuffisante pour ce plan. Essayez d'enregistrer un DXF plus léger (calques inutiles supprimés)."
                            : e.getMessage();
                    new AlertDialog.Builder(this).setTitle("Import impossible").setMessage(msg).setPositiveButton("OK", null).show();
                });
            }
        }).start();
    }

    private void warnXrefs(PlanData p) {
        if (p.xrefs.isEmpty()) return;
        StringBuilder sb = new StringBuilder("Ce plan fait référence à " + p.xrefs.size()
                + " XREF dont le contenu n'est pas dans le DXF (chargement automatique prévu au lot 2) :\n");
        for (String x : p.xrefs) sb.append("\n• ").append(x.substring(0, x.indexOf('|')));
        new AlertDialog.Builder(this).setTitle("XREF non chargées").setMessage(sb).setPositiveButton("OK", null).show();
    }

    private void openPlan(PlanStore.Entry e) {
        new Thread(() -> {
            try {
                PlanData p = store.load(e.id);
                ui.post(() -> {
                    store.setCurrent(e.id);
                    store.touch(e.id);
                    showPlan(e.id, p);
                    fitPlan();
                });
            } catch (Exception ex) {
                ui.post(() -> Toast.makeText(this, "Plan illisible : " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void restoreLastPlan() {
        String id = store.current();
        if (id == null) return;
        new Thread(() -> {
            try {
                PlanData p = store.load(id);
                ui.post(() -> {
                    showPlan(id, p);
                    if (prefs.getFloat("camZoom", -1) < 0) fitPlan();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void showPlan(String id, PlanData p) {
        planId = id;
        overlay.setPlan(p);
        survey.setPlan(id);
        updateTitle();
    }

    private void updateTitle() {
        PlanData p = overlay.getPlan();
        if (p == null) {
            planName.setText("Aucun plan ouvert");
            return;
        }
        String d = survey.activeName();
        planName.setText(p.name + (d == null ? "" : "\nLevé : " + d));
    }

    private void crosshairMode(boolean on) {
        overlay.setCrosshair(on);
        findViewById(R.id.crossBar).setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.btnAddPoint).setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
        if (on) Toast.makeText(this, "Placez le réticule sur le point puis validez", Toast.LENGTH_SHORT).show();
    }

    private void showLayers(String title) {
        PlanData p = overlay.getPlan();
        if (p == null) {
            Toast.makeText(this, "Ouvrez d'abord un plan (bouton Plans).", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = planId;
        new LayerDialog(this, p, store, id, new LayerDialog.Listener() {
            @Override
            public void onChanged() {
                overlay.refreshVisibility();
            }

            @Override
            public void onClosed() {
                store.saveVisibility(id, p);
            }
        }).show(title);
    }

    private void fitPlan() {
        PlanData p = overlay.getPlan();
        if (p == null || map == null) return;
        // emprise des calques visibles (à défaut, du plan entier)
        float[] b = visibleBounds(p);
        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(toLatLng(p, b[0], b[1]))
                .include(toLatLng(p, b[2], b[3]))
                .build();
        int pad = (int) (40 * getResources().getDisplayMetrics().density);
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad), 600);
    }

    private static float[] visibleBounds(PlanData p) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int ci = 0; ci < p.cells.length; ci++) {
            PlanData.Cell c = p.cells[ci];
            if (c == null) continue;
            boolean any = false;
            if (c.chunks != null) for (PlanData.Chunk k : c.chunks) if (p.layers.get(k.layer).visible) { any = true; break; }
            if (!any) continue;
            int cx = ci % p.nx, cy = ci / p.nx;
            float ax = p.gridX + cx * p.cellSize, ay = p.gridY + cy * p.cellSize;
            x0 = Math.min(x0, ax); y0 = Math.min(y0, ay);
            x1 = Math.max(x1, ax + p.cellSize); y1 = Math.max(y1, ay + p.cellSize);
        }
        if (x0 > x1) return new float[]{p.minX, p.minY, p.maxX, p.maxY};
        return new float[]{x0, y0, x1, y1};
    }

    private static LatLng toLatLng(PlanData p, double lx, double ly) {
        return new LatLng(LambertNC.mercYToLat(p.originMy + ly), LambertNC.mercXToLon(p.originMx + lx));
    }

    // ------------------------------------------------------------------ GPS

    private boolean hasLocPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (hasLocPermission()) startGps();
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOC && hasLocPermission()) {
            startGps();
            if (map != null && map.getStyle() != null) enableLocationComponent(map.getStyle());
        } else if (req == REQ_LOC) {
            gpsBar.setText("GPS : autorisation refusée (Paramètres > Applis > ETIK Terrain > Autorisations)");
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startGps() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locListener);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, locListener);
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                gpsBar.setText("GPS désactivé : activez la localisation du téléphone");
        } catch (Exception e) {
            gpsBar.setText("GPS indisponible : " + e.getMessage());
        }
    }

    @SuppressWarnings("MissingPermission")
    private void enableLocationComponent(Style style) {
        if (!hasLocPermission() || map == null) return;
        try {
            LocationComponent lc = map.getLocationComponent();
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(this, style)
                    .useDefaultLocationEngine(true).build());
            lc.setLocationComponentEnabled(true);
            lc.setRenderMode(RenderMode.COMPASS);
        } catch (Exception ignored) {
        }
    }

    private void updateGpsBar() {
        Location l = lastFix;
        if (l == null) return;
        double[] xy = new double[2];
        LambertNC.fromLonLat(l.getLongitude(), l.getLatitude(), xy);
        String z = "";
        if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) z = String.format(Locale.FRANCE, "  Z %.0f m", l.getMslAltitudeMeters());
        else if (l.hasAltitude()) z = String.format(Locale.FRANCE, "  Z≈%.0f m", l.getAltitude());
        String acc = l.hasAccuracy() ? String.format(Locale.FRANCE, "  ±%.0f m", l.getAccuracy()) : "";
        String src = LocationManager.GPS_PROVIDER.equals(l.getProvider()) ? "" : " (réseau)";
        gpsBar.setText(String.format(Locale.FRANCE, "X %s  Y %s%s%s%s", fmt(xy[0]), fmt(xy[1]), z, acc, src));
    }

    private static String fmt(double v) {
        return String.format(Locale.FRANCE, "%,.1f", v).replace(' ', ' ').replace(' ', ' ');
    }

    @SuppressWarnings("MissingPermission")
    private void locate() {
        if (map == null) return;
        if (!hasLocPermission()) {
            requestLocation();
            return;
        }
        LocationComponent lc = map.getLocationComponent();
        if (lc.isLocationComponentActivated()) {
            if (lc.getCameraMode() == CameraMode.TRACKING) {
                lc.setCameraMode(CameraMode.TRACKING_COMPASS);
                Toast.makeText(this, "Carte orientée selon le cap", Toast.LENGTH_SHORT).show();
            } else if (lc.getCameraMode() == CameraMode.TRACKING_COMPASS) {
                lc.setCameraMode(CameraMode.NONE);
                map.easeCamera(CameraUpdateFactory.bearingTo(0));
                Toast.makeText(this, "Suivi désactivé, nord en haut", Toast.LENGTH_SHORT).show();
            } else {
                lc.setCameraMode(CameraMode.TRACKING);
                if (map.getCameraPosition().zoom < 16) map.easeCamera(CameraUpdateFactory.zoomTo(17));
                Toast.makeText(this, "Suivi de la position", Toast.LENGTH_SHORT).show();
            }
        } else if (lastFix != null) {
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lastFix.getLatitude(), lastFix.getLongitude()), 17));
        } else {
            Toast.makeText(this, "Position GPS pas encore disponible", Toast.LENGTH_SHORT).show();
        }
    }

    /** Remet le nord en haut (et la carte à plat). Si le suivi orienté était actif, on garde le suivi simple. */
    @SuppressWarnings("MissingPermission")
    private void northUp() {
        if (map == null) return;
        try {
            LocationComponent lc = map.getLocationComponent();
            if (lc.isLocationComponentActivated() && lc.getCameraMode() == CameraMode.TRACKING_COMPASS)
                lc.setCameraMode(CameraMode.TRACKING);
        } catch (Exception ignored) {
        }
        map.easeCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(map.getCameraPosition().target).zoom(map.getCameraPosition().zoom)
                .bearing(0).tilt(0).build()), 400);
    }

    // ------------------------------------------------------------------ Cycle de vie de la carte

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lm != null) lm.removeUpdates(locListener);
        mapView.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        mapView.onSaveInstanceState(out);
    }
}
