package com.phonebridge.mock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receives positions from the bridge (Termux, same phone) as small JSON datagrams on
 * 127.0.0.1 and publishes them as mock locations for the GPS and network providers.
 * Google's fused provider and every app that asks for a location follow those two.
 *
 * Datagram: {"k":"<key>","lat":..,"lon":..,"acc":..,"spd":..,"brg":..,"alt":..}
 *           {"k":"<key>","clr":1}   stop mocking, the real location returns
 * Reply to the sender: "ok" or "err:<reason>".
 */
public class MockService extends Service {
    private static final String CHANNEL = "mock";
    private static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};

    private LocationManager lm;
    private SharedPreferences prefs;
    private volatile String key = "";
    private volatile int port = 8422;
    private volatile DatagramSocket socket;
    private Thread thread;
    private boolean providersReady = false;

    @Override
    public void onCreate() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        prefs = getSharedPreferences("mock", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // After the system restarts the service the intent is null: use what was saved.
        if (intent != null && intent.hasExtra("key")) {
            key = intent.getStringExtra("key");
            port = intent.getIntExtra("port", 8422);
            prefs.edit().putString("key", key).putInt("port", port).apply();
        } else {
            key = prefs.getString("key", "");
            port = prefs.getInt("port", 8422);
        }
        startForeground(1, notification());
        if (key == null || key.length() < 8) {
            stopSelf();
            return START_NOT_STICKY;
        }
        listen();
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mock location", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Bridge mock location")
                .setContentText("Following the position sent by the bridge")
                .setOngoing(true)
                .build();
    }

    private synchronized void listen() {
        DatagramSocket current = socket;
        if (current != null && !current.isClosed() && current.getLocalPort() == port) return;
        if (current != null) current.close();
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            socket = s;
        } catch (Exception e) {
            return;
        }
        final DatagramSocket mine = socket;
        thread = new Thread(() -> loop(mine), "mock-udp");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(DatagramSocket s) {
        byte[] buf = new byte[1024];
        while (!s.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String reply = handle(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
            } catch (Exception e) {
                if (s.isClosed()) return;
            }
        }
    }

    private String handle(String text) {
        try {
            JSONObject o = new JSONObject(text);
            // Same phone, but still: only the bridge knows the key.
            byte[] given = o.optString("k").getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(given, key.getBytes(StandardCharsets.UTF_8))) return "err:key";
            if (o.has("clr")) {
                removeProviders();
                return "ok";
            }
            if (!o.has("lat") || !o.has("lon")) return "err:bad";
            publish(o);
            return "ok";
        } catch (SecurityException e) {
            providersReady = false;
            return "err:mock"; // not allowed as mock location app: appops MOCK_LOCATION
        } catch (Exception e) {
            return "err:bad";
        }
    }

    private void ensureProviders() {
        if (providersReady) return;
        for (String p : PROVIDERS) {
            try {
                lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            } catch (IllegalArgumentException alreadyThere) {
                // a test provider with this name exists already: reuse it
            }
            lm.setTestProviderEnabled(p, true);
        }
        providersReady = true;
    }

    private void publish(JSONObject o) {
        ensureProviders();
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        for (String p : PROVIDERS) {
            Location l = new Location(p);
            l.setLatitude(o.optDouble("lat"));
            l.setLongitude(o.optDouble("lon"));
            l.setAccuracy((float) o.optDouble("acc", 20));
            l.setTime(now);
            l.setElapsedRealtimeNanos(elapsed);
            if (o.has("spd")) l.setSpeed((float) o.optDouble("spd"));
            if (o.has("brg")) l.setBearing((float) o.optDouble("brg"));
            if (o.has("alt")) l.setAltitude(o.optDouble("alt"));
            lm.setTestProviderLocation(p, l);
        }
    }

    private void removeProviders() {
        providersReady = false;
        for (String p : PROVIDERS) {
            try {
                lm.setTestProviderEnabled(p, false);
            } catch (Exception ignored) {
            }
            try {
                lm.removeTestProvider(p);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        removeProviders();
        DatagramSocket s = socket;
        if (s != null) s.close();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
