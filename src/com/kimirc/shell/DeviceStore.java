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
        public final String accountId;
        public final String name;
        public final String url;

        public Device(String accountId, String name, String url) {
            this.accountId = accountId;
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
                    String accountId = item.optString("account");
                    String name = item.optString("name");
                    String url = item.optString("url");
                    if (name.isEmpty() || !url.startsWith(WebActivity.REMOTE_URL_PREFIX)) {
                        continue;
                    }
                    if (accountId.isEmpty()) {
                        accountId = AccountStore.DEFAULT_ID;
                    }
                    devices.add(new Device(accountId, name, url));
                }
            } catch (JSONException e) {
                return new ArrayList<>();
            }
            return devices;
        }
    }

    public static List<Device> load(Context context, String accountId) {
        List<Device> devices = new ArrayList<>();
        for (Device device : load(context)) {
            if (device.accountId.equals(accountId)) {
                devices.add(device);
            }
        }
        return devices;
    }

    public static int count(Context context, String accountId) {
        return load(context, accountId).size();
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
                Device device = devices.get(i);
                if (device.url.equals(target.url) && device.accountId.equals(target.accountId)) {
                    devices.remove(i);
                }
            }
            save(context, devices);
        }
    }

    public static void removeAll(Context context, String accountId) {
        synchronized (LOCK) {
            List<Device> devices = load(context);
            for (int i = devices.size() - 1; i >= 0; i--) {
                if (devices.get(i).accountId.equals(accountId)) {
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
                item.put("account", device.accountId);
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
