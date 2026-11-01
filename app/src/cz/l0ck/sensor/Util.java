package cz.l0ck.sensor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Util {
    private Util() {}

    /**
     * SSIDs, device names and WPS strings are chosen by whoever runs the transmitter.
     * Strip control, format (bidi, zero-width) and other invisible characters and cap the
     * length, so logs stay one-line and cannot smuggle instructions to a later reader.
     */
    static String clean(CharSequence s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            int t = Character.getType(c);
            if (t == Character.CONTROL || t == Character.FORMAT || t == Character.UNASSIGNED
                    || t == Character.PRIVATE_USE || t == Character.LINE_SEPARATOR
                    || t == Character.PARAGRAPH_SEPARATOR) {
                continue;
            }
            b.append(c);
        }
        return b.toString().trim();
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    /** First 16 hex chars of SHA-256: short enough to read, long enough not to collide here. */
    static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(d).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static int channel(int freq) {
        if (freq == 2484) return 14;
        if (freq >= 2412 && freq <= 2472) return (freq - 2407) / 5;
        if (freq >= 5160 && freq <= 5885) return (freq - 5000) / 5;
        if (freq >= 5955 && freq <= 7115) return (freq - 5950) / 5;
        return 0;
    }

    /** Locally-administered bit: randomized client MACs, phone hotspots, many virtual APs. */
    static boolean locallyAdministered(String mac) {
        try {
            return (Integer.parseInt(mac.substring(0, 2), 16) & 0x02) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String oui(String mac) {
        return mac.length() >= 8 ? mac.substring(0, 8).toUpperCase() : "";
    }

    static double jaccard(Collection<String> a, Collection<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * Rough line-of-sight distance in metres from RSSI and the device's 1 m reference power,
     * via the log-distance path-loss model. RSSI is noisy, so this is an estimate, not a fix;
     * the proximity bucket below is the honest resolution.
     */
    static double distanceM(int rssi, int txAt1m, double n) {
        if (rssi == 0) return -1;
        return Math.pow(10.0, (txAt1m - rssi) / (10.0 * n));
    }

    static String distanceLabel(int rssi, int txAt1m) {
        double d = distanceM(rssi, txAt1m, 2.5);
        if (d < 0) return "?";
        if (d < 1) return String.format(java.util.Locale.US, "%.1f m", d);
        return Math.round(d) + " m";
    }

    static String proximity(int rssi) {
        if (rssi >= -55) return "very near";
        if (rssi >= -70) return "near";
        if (rssi >= -85) return "far";
        return "very far";
    }

    /** RSSI trend over time-ordered samples: + approaching, - receding, 0 steady. */
    static int trend(List<Integer> rssiOldToNew) {
        int k = rssiOldToNew.size();
        if (k < 3) return 0;
        int half = k / 2, older = 0, newer = 0;
        for (int i = 0; i < half; i++) older += rssiOldToNew.get(i);
        for (int i = k - half; i < k; i++) newer += rssiOldToNew.get(i);
        double delta = (double) newer / half - (double) older / half;
        if (delta >= 6) return 1;
        if (delta <= -6) return -1;
        return 0;
    }
}
