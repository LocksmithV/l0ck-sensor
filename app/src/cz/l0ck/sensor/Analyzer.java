package cz.l0ck.sensor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baseline per location tag and the anomaly rules.
 *
 * Modes:
 * - tag + recording: merge what is seen into baseline/<tag>.json (no Wi-Fi alerts)
 * - tag + monitoring: compare against that tag's baseline
 * - no tag (roaming): compare against the union of all baselines, which catches your home or
 *   work SSID appearing somewhere else from a different AP (evil twin / karma)
 * Follow-me tracking runs in every mode.
 */
final class Analyzer {
    static final int LOW = 1, MEDIUM = 2, HIGH = 3;
    private static final long DEDUP_MS = 6 * 3600 * 1000L;
    private static final double PLACE_DISTANCE_M = 400;
    private static final double PLACE_MAX_JACCARD = 0.2;

    static final class Alert {
        final int severity;
        final String rule, key, msg;
        final JSONObject details;

        Alert(int severity, String rule, String key, String msg, JSONObject details) {
            this.severity = severity;
            this.rule = rule;
            this.key = key;
            this.msg = msg;
            this.details = details;
        }

        JSONObject toJson(long now) throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", Util.iso(now));
            o.put("sev", severity == HIGH ? "HIGH" : severity == MEDIUM ? "MEDIUM" : "LOW");
            o.put("rule", rule);
            o.put("key", key);
            o.put("msg", msg);
            o.put("details", details);
            return o;
        }
    }

    /** Where the phone is: a GPS fix when there is a good one, otherwise the strongest fixed APs around. */
    static final class Place {
        final double lat, lon;
        final Set<String> bssids;
        final long time;

        Place(double lat, double lon, Set<String> bssids, long time) {
            this.lat = lat;
            this.lon = lon;
            this.bssids = bssids;
            this.time = time;
        }

        boolean hasFix() {
            return !Double.isNaN(lat);
        }

        boolean distinctFrom(Place o) {
            if (hasFix() && o.hasFix()) return Util.distanceM(lat, lon, o.lat, o.lon) > PLACE_DISTANCE_M;
            if (!bssids.isEmpty() && !o.bssids.isEmpty()) return Util.jaccard(bssids, o.bssids) < PLACE_MAX_JACCARD;
            return false;
        }
    }

    private static final class Track {
        String cls;
        boolean tracker;
        long first, last;
        final List<Place> places = new ArrayList<>();
    }

    private final Store store;
    private final String tag;
    private final boolean recording;
    private final JSONObject ownBaseline;
    private final Map<String, JSONObject> knownBssid = new HashMap<>();
    private final Map<String, Set<String>> ssidFps = new HashMap<>(), ssidOuis = new HashMap<>();
    private final Map<String, Boolean> ssidSecured = new HashMap<>();
    private final Set<String> knownBle = new HashSet<>();
    private final Map<String, Track> tracks = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();

    Analyzer(Store store, String tag, boolean recording) {
        this.store = store;
        this.tag = tag;
        this.recording = recording;
        JSONObject own = null;
        File[] files = store.baselineDir().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                JSONObject b = Store.load(f);
                if (b == null) continue;
                String t = f.getName().replace(".json", "");
                if (t.equals(tag)) own = b;
                // Every baseline suppresses follow-me noise from your own devices;
                // Wi-Fi comparison uses only this tag's baseline, or all of them when roaming
                indexBle(b);
                if (tag.isEmpty() || t.equals(tag)) indexWifi(b);
            }
        }
        ownBaseline = own != null ? own : emptyBaseline(tag);
    }

    private static JSONObject emptyBaseline(String tag) {
        JSONObject b = new JSONObject();
        try {
            b.put("tag", tag);
            b.put("wifi", new JSONObject());
            b.put("ble", new JSONObject());
        } catch (JSONException ignored) {
        }
        return b;
    }

    private void indexWifi(JSONObject b) {
        JSONObject w = b.optJSONObject("wifi");
        if (w == null) return;
        for (Iterator<String> it = w.keys(); it.hasNext(); ) {
            String bssid = it.next();
            JSONObject e = w.optJSONObject(bssid);
            if (e == null) continue;
            knownBssid.put(bssid, e);
            String ssid = e.optString("ssid");
            if (ssid.isEmpty()) continue;
            Set<String> fps = ssidFps.computeIfAbsent(ssid, k -> new HashSet<>());
            JSONArray a = e.optJSONArray("fps");
            if (a != null) for (int i = 0; i < a.length(); i++) fps.add(a.optString(i));
            ssidOuis.computeIfAbsent(ssid, k -> new HashSet<>()).add(Util.oui(bssid));
            if (!e.optBoolean("open")) ssidSecured.put(ssid, true);
            else ssidSecured.putIfAbsent(ssid, false);
        }
    }

    private void indexBle(JSONObject b) {
        JSONObject ble = b.optJSONObject("ble");
        if (ble == null) return;
        for (Iterator<String> it = ble.keys(); it.hasNext(); ) knownBle.add(it.next());
    }

    /** One sensor cycle. Returns alerts that should be notified (deduplicated). */
    List<Alert> cycle(List<JSONObject> wifi, List<JSONObject> ble, Place here, long now) {
        List<Alert> out = new ArrayList<>();
        try {
            if (recording) {
                record(wifi, ble, now);
            } else {
                checkWifi(wifi, out);
            }
            checkFollow(ble, here, now, out);
        } catch (JSONException ignored) {
        }
        List<Alert> fresh = new ArrayList<>();
        for (Alert a : out) {
            String k = a.rule + "|" + a.key;
            Long prev = lastAlert.get(k);
            if (prev != null && now - prev < DEDUP_MS) continue;
            lastAlert.put(k, now);
            fresh.add(a);
        }
        return fresh;
    }

    private void record(List<JSONObject> wifi, List<JSONObject> ble, long now) throws JSONException {
        JSONObject w = ownBaseline.getJSONObject("wifi");
        for (JSONObject r : wifi) {
            String bssid = r.getString("bssid");
            JSONObject e = w.optJSONObject(bssid);
            if (e == null) {
                e = new JSONObject();
                e.put("first", Util.iso(now));
                e.put("fps", new JSONArray());
                e.put("n", 0);
                w.put(bssid, e);
            }
            e.put("ssid", r.optString("ssid"));
            e.put("open", r.optBoolean("open"));
            e.put("caps", r.optString("caps"));
            e.put("last", Util.iso(now));
            e.put("n", e.optInt("n") + 1);
            addUnique(e.getJSONArray("fps"), r.optString("fp"));
            if (r.has("wps_mfr")) e.put("wps_mfr", r.optString("wps_mfr"));
            if (r.has("wps_model")) e.put("wps_model", r.optString("wps_model"));
        }
        JSONObject b = ownBaseline.getJSONObject("ble");
        for (JSONObject r : ble) {
            String key = r.getString("key");
            if (r.optBoolean("weak")) continue;
            JSONObject e = b.optJSONObject(key);
            if (e == null) {
                e = new JSONObject();
                e.put("first", Util.iso(now));
                e.put("n", 0);
                b.put(key, e);
            }
            e.put("cls", r.optString("cls"));
            e.put("name", r.optString("name"));
            e.put("last", Util.iso(now));
            e.put("n", e.optInt("n") + 1);
            knownBle.add(key);
        }
        ownBaseline.put("updated", Util.iso(now));
        Store.save(new File(store.baselineDir(), tag + ".json"), ownBaseline);
    }

    private void checkWifi(List<JSONObject> wifi, List<Alert> out) throws JSONException {
        if (knownBssid.isEmpty()) return;
        String scope = tag.isEmpty() ? "all baselines" : "baseline '" + tag + "'";
        for (JSONObject r : wifi) {
            String bssid = r.getString("bssid"), ssid = r.optString("ssid"), fp = r.optString("fp");
            boolean open = r.optBoolean("open");
            JSONObject known = knownBssid.get(bssid);

            if (known != null) {
                // A BSSID already in the baseline. The clearest spoof signal is an encryption
                // downgrade: the real AP is secured, but a beacon with the same BSSID is now open.
                if (open && !known.optBoolean("open")) {
                    out.add(new Alert(HIGH, "wifi_security_downgrade", bssid,
                            "Known AP '" + ssid + "' (" + bssid + ") now advertises OPEN; in "
                                    + scope + " it was encrypted - possible evil twin.", r));
                }
                continue;
            }

            // New BSSID. Only of interest if it claims an SSID the baseline already knows.
            Set<String> knownFps = ssidFps.get(ssid);
            if (ssid.isEmpty() || knownFps == null) continue;

            if (Boolean.TRUE.equals(ssidSecured.get(ssid)) && open) {
                out.add(new Alert(HIGH, "wifi_evil_twin_open", bssid,
                        "SSID '" + ssid + "' seen OPEN from new BSSID " + bssid + "; in " + scope
                                + " it is encrypted - likely evil twin / karma AP.", r));
                continue;
            }

            Set<String> ouis = ssidOuis.get(ssid);
            boolean newVendor = ouis != null && !ouis.contains(Util.oui(bssid));
            boolean newFp = !knownFps.contains(fp);
            if (newVendor && newFp) {
                out.add(new Alert(MEDIUM, "wifi_unexpected_bssid", bssid,
                        "SSID '" + ssid + "' from new BSSID " + bssid + " (vendor " + Util.oui(bssid)
                                + ") with an unknown fingerprint - not in " + scope + ".", r));
            } else if (newFp) {
                out.add(new Alert(LOW, "wifi_new_bssid", bssid,
                        "New BSSID " + bssid + " for known SSID '" + ssid + "'.", r));
            }
        }
    }

    /**
     * Follow-me detection. A device - a tracker above all - that keeps appearing next to you
     * across several distinct places over time is a candidate for following you. Anything already
     * in a baseline (your own devices, the household) is ignored so it does not raise noise.
     */
    private void checkFollow(List<JSONObject> ble, Place here, long now, List<Alert> out)
            throws JSONException {
        for (JSONObject r : ble) {
            String key = r.getString("key");
            boolean tracker = r.optBoolean("tracker");
            // An unnamed, non-tracker weak signature is too ambiguous to follow across places
            if (r.optBoolean("weak") && !tracker) continue;
            if (knownBle.contains(key)) continue;

            Track tr = tracks.get(key);
            if (tr == null) {
                tr = new Track();
                tr.cls = r.optString("cls");
                tr.tracker = tracker;
                tr.first = now;
                tracks.put(key, tr);
            }
            tr.last = now;
            if (here != null) addPlace(tr, here);

            int places = tr.places.size();
            long span = tr.last - tr.first;
            if (tracker && places >= 2) {
                out.add(new Alert(HIGH, "tracker_follows", key,
                        "Tracker (" + tr.cls + ") seen at " + places + " distinct places over "
                                + humanSpan(span) + " - possible tracker following you.", r));
            } else if (tracker && span > 20 * 60 * 1000L) {
                out.add(new Alert(MEDIUM, "tracker_persistent", key,
                        "Tracker (" + tr.cls + ") present for " + humanSpan(span)
                                + " - persistent tracker nearby.", r));
            } else if (!tracker && places >= 3) {
                out.add(new Alert(MEDIUM, "device_follows", key,
                        "Device '" + r.optString("name") + "' (" + tr.cls + ") seen at " + places
                                + " distinct places - follow-me candidate.", r));
            }
        }
    }

    /** Record a place only when it is distinct from every place already stored for this device. */
    private void addPlace(Track tr, Place here) {
        for (Place p : tr.places) if (!p.distinctFrom(here)) return;
        tr.places.add(here);
    }

    private static String humanSpan(long ms) {
        long m = ms / 60000;
        return m < 60 ? m + " min" : (m / 60) + "h " + (m % 60) + "m";
    }

    private static void addUnique(JSONArray a, String v) {
        if (v == null || v.isEmpty()) return;
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return;
        a.put(v);
    }
}
