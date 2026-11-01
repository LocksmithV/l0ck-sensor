package cz.l0ck.sensor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The sensor loop. Runs as a foreground service so Android keeps scanning with the screen off.
 * Entirely receive-only: it requests scans and reads results, and holds no INTERNET permission,
 * so nothing it collects can leave the device until the owner exports it deliberately.
 */
public final class SensorService extends Service {
    static final String PREFS = "sensor";
    static final String KEY_MODE = "mode";     // "record" | "monitor"
    static final String KEY_TAG = "tag";       // location label for the baseline
    static final String KEY_RUNNING = "running";
    static final String ACTION_STOP = "cz.l0ck.sensor.STOP";

    private static final String CH_STATUS = "status";
    private static final String CH_ALERT = "alert";
    private static final int NOTE_STATUS = 1;
    private static final long CYCLE_MS = 60_000;
    private static final long WIFI_MAX_AGE_MS = 120_000;
    private static final long FIX_MAX_AGE_MS = 5 * 60_000;
    private static final int PLACE_TOP_APS = 6;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wake;
    private Store store;
    private WifiCollector wifi;
    private BleCollector ble;
    private Analyzer analyzer;
    private LocationManager locations;
    // Updates are only used to keep getLastKnownLocation fresh; the fix itself is read in place()
    private final LocationListener locListener = l -> {};
    private int cycles, alertCount;
    private volatile boolean running;

    @Override public IBinder onBind(Intent i) {
        return null;
    }

    @Override public void onCreate() {
        store = new Store(this);
        wifi = new WifiCollector(this);
        ble = new BleCollector(this);
        locations = getSystemService(LocationManager.class);
        channels();
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "l0ck:sensor");
        thread = new HandlerThread("sensor");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean recording = "record".equals(p.getString(KEY_MODE, "monitor"));
        String tag = p.getString(KEY_TAG, "").trim();
        analyzer = new Analyzer(store, tag, recording);

        startForeground(NOTE_STATUS, status(recording, tag), foregroundType());
        if (!wake.isHeld()) wake.acquire();
        running = true;
        p.edit().putBoolean(KEY_RUNNING, true).apply();
        requestLocationUpdates();
        ble.restart();
        handler.post(this::loop);
        return START_STICKY;
    }

    private void loop() {
        if (!running) return;
        long now = System.currentTimeMillis();
        try {
            List<JSONObject> wifiRows = wifi.snapshot(now, WIFI_MAX_AGE_MS);
            List<JSONObject> bleRows = ble.drain(now);
            Analyzer.Place here = place(wifiRows);

            store.log("wifi", wifiRows);
            store.log("ble", bleRows);

            List<Analyzer.Alert> alerts = analyzer.cycle(wifiRows, bleRows, here, now);
            for (Analyzer.Alert a : alerts) {
                try {
                    store.log("alerts", a.toJson(now));
                } catch (Exception ignored) {
                }
                notifyAlert(a);
                alertCount++;
            }
            cycles++;
        } catch (Exception ignored) {
            // One bad cycle must never stop the sensor
        }
        // Ask for the next Wi-Fi scan and re-arm BLE (Android downgrades long-running scans)
        wifi.requestScan();
        ble.restart();
        updateStatus();
        handler.postDelayed(this::loop, CYCLE_MS);
    }

    /**
     * Where the phone is now. A fresh GPS/network fix is preferred; otherwise the set of the
     * strongest fixed (non-randomized) APs around serves as a coarse place signature, so
     * follow-me logic still works indoors or with location briefly unavailable.
     */
    private Analyzer.Place place(List<JSONObject> wifiRows) {
        Location best = lastFix();
        Set<String> anchors = new HashSet<>();
        List<JSONObject> sorted = new ArrayList<>(wifiRows);
        sorted.sort((a, b) -> b.optInt("rssi", -127) - a.optInt("rssi", -127));
        for (JSONObject r : sorted) {
            if (r.optBoolean("la")) continue;
            anchors.add(r.optString("bssid"));
            if (anchors.size() >= PLACE_TOP_APS) break;
        }
        if (best != null) {
            return new Analyzer.Place(best.getLatitude(), best.getLongitude(), anchors, best.getTime());
        }
        return new Analyzer.Place(Double.NaN, Double.NaN, anchors, System.currentTimeMillis());
    }

    private Location lastFix() {
        Location best = null;
        try {
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = locations.getLastKnownLocation(prov);
                if (l == null) continue;
                if (SystemClock.elapsedRealtimeNanos() / 1_000_000 - l.getElapsedRealtimeNanos() / 1_000_000 > FIX_MAX_AGE_MS)
                    continue;
                if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    private void requestLocationUpdates() {
        try {
            // Coarse, infrequent: just enough to tell distinct places apart
            locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000, 50, locListener, thread.getLooper());
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 120_000, 50, locListener, thread.getLooper());
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private int foregroundType() {
        return Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
    }

    private void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Sensor status", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Anomaly alerts", NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);
    }

    private Notification status(boolean recording, String tag) {
        String mode = recording ? "Recording baseline" : "Monitoring";
        String where = tag.isEmpty() ? "roaming" : tag;
        Intent stop = new Intent(this, SensorService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_STATUS)
                .setContentTitle("L0ck-Sensor - " + mode)
                .setContentText(where + " | cycles " + cycles + " | alerts " + alertCount)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", pi).build())
                .build();
    }

    private void updateStatus() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean recording = "record".equals(p.getString(KEY_MODE, "monitor"));
        String tag = p.getString(KEY_TAG, "").trim();
        getSystemService(NotificationManager.class).notify(NOTE_STATUS, status(recording, tag));
    }

    private void notifyAlert(Analyzer.Alert a) {
        // a.msg is built from attacker-settable fields (SSID, device name); they are already
        // stripped of control characters in Util.clean, so they are safe to show as plain text.
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setContentTitle(sev(a.severity) + ": " + a.rule)
                .setContentText(a.msg)
                .setStyle(new Notification.BigTextStyle().bigText(a.msg))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(a.rule.hashCode() ^ a.key.hashCode(), n);
        if (a.severity == Analyzer.HIGH) alarm();
    }

    /** Loud, physical alert for a HIGH finding (a tracker confirmed following you). */
    private void alarm() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400, 200, 400}, -1));
            }
        } catch (Exception ignored) {
        }
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), uri);
            if (r != null && !r.isPlaying()) {
                r.play();
                // let it sound briefly, then stop so it is an alert, not a siren
                handler.postDelayed(() -> {
                    try {
                        if (r.isPlaying()) r.stop();
                    } catch (Exception ignored) {
                    }
                }, 3500);
            }
        } catch (Exception ignored) {
        }
    }

    private static String sev(int s) {
        return s == Analyzer.HIGH ? "HIGH" : s == Analyzer.MEDIUM ? "MEDIUM" : "LOW";
    }

    @Override public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.stop();
        try {
            locations.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        if (wake != null && wake.isHeld()) wake.release();
        if (thread != null) thread.quitSafely();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SensorService.class));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, SensorService.class).setAction(ACTION_STOP));
    }
}
