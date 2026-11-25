package nc.etik.terrain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enregistrement d'une trace GPS en tâche de premier plan : continue écran
 * éteint, téléphone en poche. Chaque position retenue est écrite aussitôt
 * dans le fichier CSV de la trace (rien n'est perdu si l'appli s'arrête).
 */
public class TrackService extends Service {

    static final String EXTRA_FILE = "fichier";
    static final String EXTRA_NAME = "nom";
    private static final String CHANNEL = "trace";
    private static final int NOTIF_ID = 42;

    /** Distance minimale entre deux points retenus (m) et précision maximale acceptée (m). */
    static final float MIN_STEP = 2f, MAX_ACC = 25f;

    /** État partagé avec l'activité (même processus). */
    static final class State {
        volatile boolean running, paused;
        String name;
        File file;
        long start;
        double length;
        int count;
        float lastAcc = -1;
        final List<double[]> latlon = new ArrayList<>(); // pour l'affichage en direct
        volatile Runnable listener;
    }

    static final State STATE = new State();

    private LocationManager lm;
    private Writer out;
    private Location last;

    private final LocationListener ll = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            onFix(l);
        }

        @Override
        public void onProviderEnabled(String p) {}

        @Override
        public void onProviderDisabled(String p) {}

        @Override
        public void onStatusChanged(String p, int s, Bundle b) {}
    };

    static void start(Context c, File file, String name) {
        Intent i = new Intent(c, TrackService.class);
        i.putExtra(EXTRA_FILE, file.getAbsolutePath());
        i.putExtra(EXTRA_NAME, name);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, TrackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STATE.running) return START_NOT_STICKY;
        File f = new File(intent.getStringExtra(EXTRA_FILE));
        synchronized (STATE) {
            STATE.name = intent.getStringExtra(EXTRA_NAME);
            STATE.file = f;
            STATE.start = System.currentTimeMillis();
            STATE.length = 0;
            STATE.count = 0;
            STATE.paused = false;
            STATE.latlon.clear();
            STATE.running = true;
        }
        try {
            f.getParentFile().mkdirs();
            out = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            STATE.running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification("Démarrage…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIF_ID, n);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, ll, Looper.getMainLooper());
        } catch (SecurityException e) {
            STATE.running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void onFix(Location l) {
        STATE.lastAcc = l.hasAccuracy() ? l.getAccuracy() : -1;
        if (STATE.paused) {
            last = null; // pas de segment fictif entre la pause et la reprise
            notifyUi();
            return;
        }
        if (l.hasAccuracy() && l.getAccuracy() > MAX_ACC) {
            notifyUi();
            return;
        }
        if (last != null) {
            float d = last.distanceTo(l);
            if (d < Math.max(MIN_STEP, l.hasAccuracy() ? l.getAccuracy() * 0.3f : 0)) return;
            synchronized (STATE) {
                STATE.length += d;
            }
        }
        last = l;
        try {
            out.write(String.format(Locale.ROOT, "%d;%.8f;%.8f;%s;%.1f\n", l.getTime(), l.getLatitude(), l.getLongitude(),
                    l.hasAltitude() ? String.format(Locale.ROOT, "%.1f", l.getAltitude()) : "", l.hasAccuracy() ? l.getAccuracy() : -1f));
            out.flush();
        } catch (IOException ignored) {
        }
        synchronized (STATE) {
            STATE.count++;
            STATE.latlon.add(new double[]{l.getLatitude(), l.getLongitude()});
        }
        update();
        notifyUi();
    }

    private void notifyUi() {
        Runnable r = STATE.listener;
        if (r != null) r.run();
    }

    private void update() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, notification(summary()));
    }

    static String summary() {
        long min = (System.currentTimeMillis() - STATE.start) / 60000;
        return String.format(Locale.FRANCE, "%s%.2f km · %d min · %d points", STATE.paused ? "En pause · " : "",
                STATE.length / 1000, min, STATE.count);
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Trace GPS", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Trace en cours : " + STATE.name)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (lm != null) lm.removeUpdates(ll);
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        STATE.running = false;
        notifyUi();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
