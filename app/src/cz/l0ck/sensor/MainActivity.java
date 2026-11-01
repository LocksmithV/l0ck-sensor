package cz.l0ck.sensor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Control panel and live readout, built in code and themed to the L0ck look. Reads the NDJSON the
 * service writes and renders readable device cards: class, distance, trend, adverts and beacons,
 * with a read-only GATT probe for a suspect device.
 */
public final class MainActivity extends Activity {

    private enum Tab { DEVICES, TRACKERS, WIFI, ALERTS, ABOUT }

    private static final String[] PERMS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
    };

    private int cBg, cPanel, cWidget, cBorder, cGreen, cGreenL, cOrange, cText, cText2, cDim, cOk, cAmber, cRed;
    private Store store;
    private RadioGroup modeGroup;
    private EditText tagInput;
    private TextView statusLine;
    private LinearLayout listHost;
    private Tab tab = Tab.DEVICES;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadColors();
        store = new Store(this);
        setContentView(buildUi());
        refresh();
        requestMissing();
    }

    private void loadColors() {
        cBg = getColor(R.color.bgPrimary);
        cPanel = getColor(R.color.bgPanel);
        cWidget = getColor(R.color.bgWidget);
        cBorder = getColor(R.color.bgBorder);
        cGreen = getColor(R.color.greenMid);
        cGreenL = getColor(R.color.greenLight);
        cOrange = getColor(R.color.orange);
        cText = getColor(R.color.textPrimary);
        cText2 = getColor(R.color.textSecondary);
        cDim = getColor(R.color.textDim);
        cOk = getColor(R.color.statusGreen);
        cAmber = getColor(R.color.statusAmber);
        cRed = getColor(R.color.statusRed);
    }

    // --- layout ---

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, 0);

        root.addView(header());
        root.addView(controls());
        root.addView(tabs());

        statusLine = new TextView(this);
        statusLine.setTextColor(cDim);
        statusLine.setTextSize(11);
        statusLine.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusLine);

        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(listHost);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        // Use the masked adaptive launcher icon: the mask hides the "ECP" sub-label that the
        // raw foreground artwork carries, giving a clean hexagon that matches the app icon.
        logo.setImageResource(R.mipmap.ic_launcher);
        h.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = new TextView(this);
        t.setText("  L0ck-Sensor");
        t.setTextColor(cText);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        h.addView(t);
        return h;
    }

    private View controls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rec = radio("Record baseline", "record");
        RadioButton mon = radio("Monitor", "monitor");
        modeGroup.addView(rec);
        modeGroup.addView(mon);
        SharedPreferences p = prefs();
        boolean recording = "record".equals(p.getString(SensorService.KEY_MODE, "monitor"));
        modeGroup.check(recording ? rec.getId() : mon.getId());
        box.addView(modeGroup);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tagInput = new EditText(this);
        tagInput.setInputType(InputType.TYPE_CLASS_TEXT);
        tagInput.setHint("location tag (blank = roaming)");
        tagInput.setHintTextColor(cDim);
        tagInput.setTextColor(cText);
        tagInput.setTextSize(13);
        tagInput.setText(p.getString(SensorService.KEY_TAG, ""));
        row.addView(tagInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btn("Start", cGreen, v -> startSensor()));
        row.addView(btn("Stop", cWidget, v -> {
            SensorService.stop(this);
            toast("Stopping");
            statusLine.postDelayed(this::refresh, 1200);
        }));
        box.addView(row);
        return box;
    }

    private View tabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        row.addView(tabBtn("Devices", Tab.DEVICES));
        row.addView(tabBtn("Trackers", Tab.TRACKERS));
        row.addView(tabBtn("Wi-Fi", Tab.WIFI));
        row.addView(tabBtn("Alerts", Tab.ALERTS));
        row.addView(tabBtn("About", Tab.ABOUT));
        return row;
    }

    private View tabBtn(String label, Tab t) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setTextColor(tab == t ? cBg : cText2);
        b.setBackgroundColor(tab == t ? cGreen : cWidget);
        b.setOnClickListener(v -> {
            tab = t;
            rebuildTabs();
            refresh();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void rebuildTabs() {
        // cheapest: rebuild the whole screen keeps tab colors in sync
        setContentView(buildUi());
    }

    // --- data + rendering ---

    private void refresh() {
        listHost.removeAllViews();
        boolean running = prefs().getBoolean(SensorService.KEY_RUNNING, false);
        statusLine.setText((running ? "● RUNNING" : "○ stopped")
                + "   " + tab.name().toLowerCase());
        statusLine.setTextColor(running ? cOk : cDim);
        switch (tab) {
            case DEVICES: renderDevices(false); break;
            case TRACKERS: renderDevices(true); break;
            case WIFI: renderWifi(); break;
            case ALERTS: renderAlerts(); break;
            case ABOUT: renderAbout(); break;
        }
    }

    /** Description screen, mirroring the L0ck project page: mission, principles, capability, stance. */
    private void renderAbout() {
        LinearLayout c = card();
        TextView t = new TextView(this);
        t.setText("L0CK-SENSOR");
        t.setTextColor(cText);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        c.addView(line("WIRELESS COUNTER-SURVEILLANCE", cGreenL));
        c.addView(line("One phone. Every nearby radio. Watched.", cText2));
        c.addView(line(appVersion() + " · node by L0ckSm1thV · github.com/LocksmithV", cDim));
        listHost.addView(c);

        aboutParagraph("MISSION",
                "L0ck-Sensor turns an accountless LineageOS phone into a passive sensor for the "
                        + "Bluetooth and Wi-Fi around you. It learns what is normal at the places you "
                        + "go, then flags what should not be there — a tracker that follows you, a "
                        + "familiar network answering from the wrong radio. No account, no cloud, no "
                        + "single point of exposure.");

        aboutLettered("PRINCIPLES", new String[]{
                "Free and open source — auditable, no proprietary lock-in.",
                "Offline by design — no INTERNET permission; data never leaves the phone.",
                "Passive and defensive — it listens, it never attacks, deauths or jams.",
                "Baseline per location — an anomaly is what is new, not just what is loud.",
        });

        aboutSection("WHAT IT WATCHES", new String[]{
                "Trackers that follow you — AirTag & Find My, Samsung SmartTag, Tile, Chipolo, Google FMDN.",
                "Evil-twin / rogue access points — a known network from an unexpected radio.",
                "Devices that reappear across the places you go.",
        });
        aboutSection("SIGNALS", new String[]{
                "Distance estimate, and whether a device is approaching or receding.",
                "AP fingerprint from vendor info elements, not just the name.",
                "Read-only GATT to identify a suspect device — no writes, no interference.",
        });

        LinearLayout close = card();
        close.addView(line("Baselines and operational detail stay on the device by design. "
                + "The trail you leave is the trail they follow — this watches the other direction.", cDim));
        listHost.addView(close);
    }

    private void aboutParagraph(String head, String body) {
        LinearLayout c = card();
        c.addView(aboutHead(head));
        c.addView(line(body, cText2));
        listHost.addView(c);
    }

    private void aboutLettered(String head, String[] items) {
        LinearLayout c = card();
        c.addView(aboutHead(head));
        char letter = 'A';
        for (String it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tag = new TextView(this);
            tag.setText(String.valueOf(letter++));
            tag.setTextColor(cBg);
            tag.setTypeface(Typeface.DEFAULT_BOLD);
            tag.setTextSize(11);
            tag.setPadding(dp(7), dp(1), dp(7), dp(1));
            GradientDrawable g = new GradientDrawable();
            g.setColor(cGreen);
            g.setCornerRadius(dp(5));
            tag.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), dp(8), 0);
            tag.setLayoutParams(lp);
            row.addView(tag);
            TextView tx = line(it, cText2);
            row.addView(tx);
            c.addView(row);
        }
        listHost.addView(c);
    }

    private TextView aboutHead(String head) {
        TextView h = new TextView(this);
        h.setText("// " + head);
        h.setTextColor(cOrange);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(13);
        h.setPadding(0, 0, 0, dp(4));
        return h;
    }

    private void aboutSection(String head, String[] lines) {
        LinearLayout c = card();
        c.addView(aboutHead(head));
        for (String l : lines) {
            TextView t = line("›  " + l, cText2);
            t.setPadding(0, dp(2), 0, dp(2));
            c.addView(t);
        }
        listHost.addView(c);
    }

    private String appVersion() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void renderDevices(boolean trackersOnly) {
        List<JSONObject> rows = readRows("ble");
        // aggregate to latest row per address, with an RSSI history for the trend
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        Map<String, List<Integer>> hist = new LinkedHashMap<>();
        for (JSONObject r : rows) {
            String addr = r.optString("addr");
            latest.put(addr, r);
            hist.computeIfAbsent(addr, k -> new ArrayList<>()).add(r.optInt("rssi", -127));
        }
        List<JSONObject> devs = new ArrayList<>(latest.values());
        devs.sort(Comparator.comparingInt(a -> -a.optInt("rssi", -127)));
        int shown = 0;
        for (JSONObject d : devs) {
            if (trackersOnly && !d.optBoolean("tracker")) continue;
            listHost.addView(deviceCard(d, hist.get(d.optString("addr"))));
            shown++;
        }
        if (shown == 0) emptyNote(trackersOnly ? "No trackers seen today." : "No BLE devices today. Start the sensor.");
    }

    private View deviceCard(JSONObject d, List<Integer> hist) {
        LinearLayout card = card();
        boolean tracker = d.optBoolean("tracker");
        String cls = d.optString("cls", "ble_other");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(cls, tracker ? cRed : cGreen));
        if (tracker) top.addView(badge("TRACKER", cRed));
        String name = d.optString("name", "");
        TextView nm = new TextView(this);
        nm.setText("  " + (name.isEmpty() ? "(no name)" : name));
        nm.setTextColor(cText);
        nm.setTextSize(14);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(nm);
        card.addView(top);

        int rssi = d.optInt("rssi", -127);
        // Distance reference: a beacon's calibrated 1 m power when present, else the common
        // BLE default of -59 dBm. The advertised TX-power level is not a 1 m RSSI, so it is
        // not used here — it would make distance wildly wrong.
        int tx = d.has("ref1m") ? d.optInt("ref1m") : -59;
        int trend = Util.trend(hist == null ? new ArrayList<>() : hist);
        String arrow = trend > 0 ? "▲ approaching" : trend < 0 ? "▼ receding" : "– steady";
        int arrowC = trend > 0 ? cRed : trend < 0 ? cOk : cDim;

        card.addView(line(d.optString("addr") + "  (" + d.optString("atype") + ")", cText2));
        LinearLayout sig = new LinearLayout(this);
        sig.setOrientation(LinearLayout.HORIZONTAL);
        sig.addView(line(rssi + " dBm · ~" + Util.distanceLabel(rssi, tx) + " · " + Util.proximity(rssi) + "   ", cGreenL));
        sig.addView(line(arrow, arrowC));
        card.addView(sig);

        JSONObject beacon = d.optJSONObject("beacon");
        if (beacon != null) card.addView(line("beacon: " + beaconText(beacon), cOrange));

        String adv = advertText(d);
        if (!adv.isEmpty()) card.addView(line(adv, cDim));

        if (tracker || d.optBoolean("conn")) {
            Button probe = btn("Investigate (read GATT)", cWidget, v -> investigate(d.optString("addr")));
            probe.setTextColor(cOrange);
            card.addView(probe);
        }
        return card;
    }

    private String beaconText(JSONObject b) {
        String t = b.optString("type");
        if (t.equals("ibeacon")) {
            return "iBeacon " + b.optString("id1") + " / " + b.optInt("major") + ":" + b.optInt("minor");
        }
        return t + " " + b.optString("id1");
    }

    private String advertText(JSONObject d) {
        StringBuilder s = new StringBuilder();
        JSONArray mfr = d.optJSONArray("mfr");
        if (mfr != null && mfr.length() > 0) s.append("mfr=").append(mfr).append("  ");
        JSONArray uu = d.optJSONArray("uuids");
        if (uu != null && uu.length() > 0) s.append("uuid=").append(shortUuids(uu)).append("  ");
        JSONArray sd = d.optJSONArray("sdata");
        if (sd != null && sd.length() > 0) s.append("sdata=").append(shortUuids(sd));
        return s.toString().trim();
    }

    private static String shortUuids(JSONArray a) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            String u = a.optString(i);
            if (u.length() == 36 && u.startsWith("0000")) out.put("0x" + u.substring(4, 8));
            else out.put(u);
        }
        return out.toString();
    }

    private void renderWifi() {
        List<JSONObject> rows = readRows("wifi");
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (JSONObject r : rows) latest.put(r.optString("bssid"), r);
        List<JSONObject> aps = new ArrayList<>(latest.values());
        aps.sort(Comparator.comparingInt(a -> -a.optInt("rssi", -127)));
        if (aps.isEmpty()) {
            emptyNote("No Wi-Fi APs today. Start the sensor.");
            return;
        }
        for (JSONObject a : aps) {
            LinearLayout card = card();
            String ssid = a.optString("ssid");
            TextView nm = new TextView(this);
            nm.setText(ssid.isEmpty() ? "(hidden)" : ssid);
            nm.setTextColor(cText);
            nm.setTextSize(14);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(nm);
            card.addView(line(a.optString("bssid") + "  ch " + a.optInt("ch")
                    + (a.optBoolean("open") ? "  OPEN" : ""), a.optBoolean("open") ? cAmber : cText2));
            card.addView(line(a.optInt("rssi") + " dBm · " + Util.proximity(a.optInt("rssi"))
                    + "   fp " + a.optString("fp"), cGreenL));
            String wps = a.optString("wps_mfr", "");
            if (!wps.isEmpty()) card.addView(line("WPS: " + wps + " " + a.optString("wps_model", ""), cDim));
            listHost.addView(card);
        }
    }

    private void renderAlerts() {
        List<JSONObject> rows = readRows("alerts");
        if (rows.isEmpty()) {
            emptyNote("No alerts today.");
            return;
        }
        Collections.reverse(rows);
        for (JSONObject a : rows) {
            LinearLayout card = card();
            String sev = a.optString("sev");
            int c = sev.equals("HIGH") ? cRed : sev.equals("MEDIUM") ? cAmber : cOk;
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.addView(badge(sev, c));
            TextView r = new TextView(this);
            r.setText("  " + a.optString("rule"));
            r.setTextColor(cText);
            r.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(r);
            card.addView(top);
            card.addView(line(a.optString("msg"), cText2));
            card.addView(line(a.optString("t"), cDim));
            listHost.addView(card);
        }
    }

    /** Active, read-only GATT investigation of one suspect device, shown in a dialog. */
    private void investigate(String addr) {
        if (addr == null || addr.isEmpty()) return;
        Toast.makeText(this, "Connecting to " + addr + " (read-only)...", Toast.LENGTH_SHORT).show();
        GattProbe.probe(this, addr, text -> {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(11);
            tv.setTextColor(cText);
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this)
                    .setTitle("GATT · " + addr)
                    .setView(sv)
                    .setPositiveButton("Close", null)
                    .show();
        });
    }

    private List<JSONObject> readRows(String stream) {
        List<JSONObject> out = new ArrayList<>();
        try {
            File f = new File(store.logDir(), stream + ".ndjson");
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - 4000); // cap work on long sessions
            for (int i = from; i < all.size(); i++) {
                String l = all.get(i).trim();
                if (!l.isEmpty()) out.add(new JSONObject(l));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // --- start / permissions ---

    private void startSensor() {
        RadioButton rb = findViewById(modeGroup.getCheckedRadioButtonId());
        String mode = rb != null && "record".equals(rb.getTag()) ? "record" : "monitor";
        prefs().edit()
                .putString(SensorService.KEY_MODE, mode)
                .putString(SensorService.KEY_TAG, tagInput.getText().toString().trim())
                .apply();
        if (missing().isEmpty()) {
            SensorService.start(this);
            toast("Started: " + mode);
            statusLine.postDelayed(this::refresh, 1500);
        } else {
            requestMissing();
            toast("Grant permissions, then Start again");
        }
    }

    private List<String> missing() {
        List<String> m = new ArrayList<>();
        for (String p : PERMS) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) m.add(p);
        return m;
    }

    private void requestMissing() {
        List<String> m = missing();
        if (!m.isEmpty()) requestPermissions(m.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine && checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            toast("For screen-off scanning, set Location to 'Allow all the time' in app settings.");
        }
        refresh();
    }

    // --- small view helpers ---

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(cPanel);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), cBorder);
        c.setBackground(g);
        int p = dp(10);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView badge(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(cBg);
        t.setPadding(dp(6), dp(1), dp(6), dp(1));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(5));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView line(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(12);
        return t;
    }

    private void emptyNote(String text) {
        TextView t = line(text, cDim);
        t.setPadding(dp(4), dp(16), dp(4), 0);
        listHost.addView(t);
    }

    private RadioButton radio(String label, String tag) {
        RadioButton r = new RadioButton(this);
        r.setId(View.generateViewId());
        r.setText(label);
        r.setTag(tag);
        r.setTextColor(cText);
        r.setTextSize(13);
        return r;
    }

    private Button btn(String label, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(cText);
        b.setTextSize(13);
        b.setBackgroundColor(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(SensorService.PREFS, Context.MODE_PRIVATE);
    }

    private TextView title(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(cText);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
