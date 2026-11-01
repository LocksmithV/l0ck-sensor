package cz.l0ck.sensor;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything lives under the app's external files dir
 * (/sdcard/Android/data/cz.l0ck.sensor/files): readable over adb and later shareable,
 * but not by other apps.
 */
final class Store {
    final File base;

    Store(Context c) {
        base = c.getExternalFilesDir(null);
    }

    File logDir() {
        File d = new File(base, "logs/" + LocalDate.now(ZoneOffset.UTC));
        d.mkdirs();
        return d;
    }

    File baselineDir() {
        File d = new File(base, "baseline");
        d.mkdirs();
        return d;
    }

    File stateDir() {
        File d = new File(base, "state");
        d.mkdirs();
        return d;
    }

    synchronized void log(String stream, List<JSONObject> rows) {
        if (rows.isEmpty()) return;
        File f = new File(logDir(), stream + ".ndjson");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            for (JSONObject o : rows) {
                w.write(o.toString());
                w.write('\n');
            }
        } catch (IOException ignored) {
            // A full or unmounted storage must not kill the sensor loop
        }
    }

    void log(String stream, JSONObject row) {
        List<JSONObject> l = new ArrayList<>(1);
        l.add(row);
        log(stream, l);
    }

    static JSONObject load(File f) {
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Write to a temp file and rename, so a crash never leaves a half-written baseline. */
    static void save(File f, JSONObject o) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }

    /** Last n lines of today's stream, newest first; used by the UI. */
    List<String> tail(String stream, int n) {
        List<String> out = new ArrayList<>();
        try {
            List<String> all = Files.readAllLines(new File(logDir(), stream + ".ndjson").toPath(), StandardCharsets.UTF_8);
            for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) out.add(all.get(i));
        } catch (IOException ignored) {
        }
        return out;
    }
}
