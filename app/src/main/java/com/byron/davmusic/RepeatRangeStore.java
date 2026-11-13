package com.byron.davmusic;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local-only A/B interval persistence, scoped to server + relative path. */
public final class RepeatRangeStore {
    private static final String PREFS = "ab_repeat";
    private final SharedPreferences prefs;
    public RepeatRangeStore(Context context) { prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static String key(String serverId, String relativePath) {
        return RepeatRange.trackKey(serverId, relativePath);
    }
    public List<RepeatRange> load(String trackKey) {
        List<RepeatRange> out = new ArrayList<>();
        if (trackKey == null) return out;
        try {
            JSONArray values = new JSONArray(prefs.getString(trackKey, "[]"));
            for (int i = 0; i < values.length(); i++) {
                JSONObject obj = values.optJSONObject(i);
                if (obj == null) continue;
                RepeatRange range = RepeatRange.create(obj.optInt("a", -1), obj.optInt("b", -1), Integer.MAX_VALUE);
                if (range != null) out.add(range);
            }
        } catch (Exception ignored) {}
        return out;
    }
    public void save(String trackKey, List<RepeatRange> ranges) {
        if (trackKey == null) return;
        JSONArray values = new JSONArray();
        if (ranges != null) for (RepeatRange r : ranges) if (r != null) {
            JSONObject obj = new JSONObject();
            try { obj.put("a", r.getStartMs()); obj.put("b", r.getEndMs()); values.put(obj); } catch (Exception ignored) {}
        }
        prefs.edit().putString(trackKey, values.toString()).apply();
    }
}
