package com.kimirc.shell;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DeviceStore {

    private static final String PREFS_NAME = "devices";
    private static final String KEY_LIST = "list";
    private static final Object LOCK = new Object();

    private DeviceStore() {
    }

    public static class Device {
        public final String name;
        public final String url;

        public Device(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public static List<Device> load(Context context) {
        synchronized (LOCK) {
            String raw = prefs(context).getString(KEY_LIST, "[]");
            List<Device> devices = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String name = item.optString("name");
                    String url = item.optString("url");
                    if (name.isEmpty() || !url.startsWith(WebActivity.REMOTE_URL_PREFIX)) {
                        continue;
                    }
                    devices.add(new Device(name, url));
                }
            } catch (JSONException e) {
                return new ArrayList<>();
            }
            return devices;
        }
    }

    public static void add(Context context, Device device) {
        synchronized (LOCK) {
            List<Device> devices = load(context);
            devices.add(device);
            save(context, devices);
        }
    }

    public static void remove(Context context, Device target) {
        synchronized (LOCK) {
            List<Device> devices = load(context);
            for (int i = devices.size() - 1; i >= 0; i--) {
                if (devices.get(i).url.equals(target.url)) {
                    devices.remove(i);
                }
            }
            save(context, devices);
        }
    }

    private static void save(Context context, List<Device> devices) {
        JSONArray array = new JSONArray();
        try {
            for (Device device : devices) {
                JSONObject item = new JSONObject();
                item.put("name", device.name);
                item.put("url", device.url);
                array.put(item);
            }
        } catch (JSONException e) {
            return;
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
