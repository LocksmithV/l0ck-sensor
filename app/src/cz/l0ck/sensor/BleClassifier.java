package cz.l0ck.sensor;

import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Threat: covert trackers (AirTag and other Find My accessories, SmartTag, Tile, Google FMDN tags).
 * They are recognised by their advertising payload, not by address, because addresses rotate.
 */
final class BleClassifier {
    static final int APPLE = 0x004C;

    private static final ParcelUuid SMARTTAG = uuid16(0xFD5A);
    private static final ParcelUuid TILE_A = uuid16(0xFEED);
    private static final ParcelUuid TILE_B = uuid16(0xFEEC);
    private static final ParcelUuid CHIPOLO = uuid16(0xFE33);
    private static final ParcelUuid EDDYSTONE = uuid16(0xFEAA);

    static final class Result {
        final String cls;
        final boolean tracker;
        final int appleType;

        Result(String cls, boolean tracker, int appleType) {
            this.cls = cls;
            this.tracker = tracker;
            this.appleType = appleType;
        }
    }

    static ParcelUuid uuid16(int v) {
        return ParcelUuid.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", v));
    }

    static Result classify(ScanRecord rec) {
        if (rec == null) return new Result("ble_other", false, -1);

        byte[] apple = rec.getManufacturerSpecificData(APPLE);
        if (apple != null && apple.length >= 2) {
            int type = apple[0] & 0xff, len = apple[1] & 0xff;
            if (type == 0x12) {
                // Find My "offline finding": the long (25-byte) form is only sent when the
                // accessory is away from its owner, which is exactly the stalking case
                return len >= 25 ? new Result("apple_findmy_separated", true, type)
                        : new Result("apple_findmy_near_owner", false, type);
            }
            if (type == 0x07) return new Result("apple_proximity_pairing", false, type);
            if (type == 0x10) return new Result("apple_nearby_info", false, type);
            if (type == 0x0F) return new Result("apple_nearby_action", false, type);
            return new Result("apple_other", false, type);
        }

        Map<ParcelUuid, byte[]> sdata = rec.getServiceData();
        if (sdata != null) {
            if (sdata.containsKey(SMARTTAG)) return new Result("samsung_smarttag", true, -1);
            byte[] ed = sdata.get(EDDYSTONE);
            if (ed != null && ed.length > 0) {
                int frame = ed[0] & 0xff;
                // Find My Device Network (Google) uses Eddystone frames 0x40/0x41
                if (frame == 0x40 || frame == 0x41) return new Result("google_fmdn", true, -1);
                return new Result("eddystone", false, -1);
            }
        }
        List<ParcelUuid> uuids = rec.getServiceUuids();
        if (uuids != null) {
            if (uuids.contains(TILE_A) || uuids.contains(TILE_B)) return new Result("tile", true, -1);
            if (uuids.contains(CHIPOLO)) return new Result("chipolo", true, -1);
        }
        if (sdata != null && (sdata.containsKey(TILE_A) || sdata.containsKey(TILE_B))) return new Result("tile", true, -1);
        return new Result("ble_other", false, -1);
    }

    /** Parsed beacon frame, when the advert is a recognised beacon format. */
    static final class Beacon {
        final String type, id1;
        final int id2, id3, power;

        Beacon(String type, String id1, int id2, int id3, int power) {
            this.type = type;
            this.id1 = id1;
            this.id2 = id2;
            this.id3 = id3;
            this.power = power;
        }
    }

    /** iBeacon (Apple 0x02 0x15: UUID/major/minor/measured-power) or Eddystone, else null. */
    static Beacon beacon(ScanRecord rec) {
        if (rec == null) return null;
        byte[] apple = rec.getManufacturerSpecificData(APPLE);
        if (apple != null && apple.length >= 23 && (apple[0] & 0xff) == 0x02 && (apple[1] & 0xff) == 0x15) {
            String uuid = Util.hex(Arrays.copyOfRange(apple, 2, 18));
            int major = ((apple[18] & 0xff) << 8) | (apple[19] & 0xff);
            int minor = ((apple[20] & 0xff) << 8) | (apple[21] & 0xff);
            return new Beacon("ibeacon", uuid, major, minor, apple[22]); // power: signed, measured at 1 m
        }
        Map<ParcelUuid, byte[]> sd = rec.getServiceData();
        if (sd != null) {
            byte[] ed = sd.get(EDDYSTONE);
            if (ed != null && ed.length > 0) {
                int f = ed[0] & 0xff;
                String t = f == 0x00 ? "eddystone-uid" : f == 0x10 ? "eddystone-url"
                        : f == 0x20 ? "eddystone-tlm" : f == 0x30 ? "eddystone-eid" : "eddystone";
                int power = ed.length > 1 ? ed[1] : 0;
                return new Beacon(t, Util.hex(ed), -1, -1, power);
            }
        }
        return null;
    }

    /** Random address sub-type from the two most significant bits (BT Core spec Vol 6, Part B, 1.3). */
    static String randomSubtype(String addr) {
        try {
            int top = Integer.parseInt(addr.substring(0, 2), 16) >> 6;
            return top == 3 ? "static" : top == 1 ? "resolvable" : top == 0 ? "non_resolvable" : "reserved";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
