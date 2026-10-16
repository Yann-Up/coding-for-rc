package com.kimirc.shell;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class AccountStore {

    public static final String DEFAULT_ID = "default";

    private static final String PREFS_NAME = "accounts";
    private static final String KEY_LIST = "list";
    private static final String KEY_ACTIVE = "active";
    private static final String WIPE_FILE_PREFIX = "wipe_slot_";
    private static final String NICKNAME_FILE_PREFIX = "nickname_";
    private static final Object LOCK = new Object();

    public static class Account {
        public final String id;
        public final String name;
        public final int slot;

        public Account(String id, String name, int slot) {
            this.id = id;
            this.name = name;
            this.slot = slot;
        }
    }

    private AccountStore() {
    }

    public static List<Account> load(Context context) {
        synchronized (LOCK) {
            List<Account> accounts = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(prefs(context).getString(KEY_LIST, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String id = item.optString("id");
                    String name = item.optString("name");
                    int slot = item.optInt("slot", -1);
                    if (id.isEmpty() || name.isEmpty()
                            || slot < 0 || slot >= WebActivity.SLOT_COUNT) {
                        continue;
                    }
                    accounts.add(new Account(id, name, slot));
                }
            } catch (JSONException e) {
                return new ArrayList<>();
            }
            return accounts;
        }
    }

    public static Account byId(Context context, String id) {
        for (Account account : load(context)) {
            if (account.id.equals(id)) {
                return account;
            }
        }
        return null;
    }

    public static Account active(Context context) {
        synchronized (LOCK) {
            List<Account> accounts = load(context);
            String activeId = prefs(context).getString(KEY_ACTIVE, "");
            for (Account account : accounts) {
                if (account.id.equals(activeId)) {
                    return account;
                }
            }
            if (!accounts.isEmpty()) {
                Account fallback = accounts.get(0);
                setActive(context, fallback.id);
                return fallback;
            }
            String fallbackName = context.getString(R.string.default_account_name);
            Account created = createWithId(context, accounts, DEFAULT_ID, fallbackName);
            if (created != null) {
                setActive(context, created.id);
                return created;
            }
            return new Account(DEFAULT_ID, fallbackName, 0);
        }
    }

    public static void setActive(Context context, String id) {
        synchronized (LOCK) {
            prefs(context).edit().putString(KEY_ACTIVE, id).apply();
        }
    }

    public static Account create(Context context, String name) {
        synchronized (LOCK) {
            List<Account> accounts = load(context);
            return createWithId(context, accounts, nextId(accounts), name);
        }
    }

    // 默认账号固定用 DEFAULT_ID，好让升级前的设备记录（没有账号字段）能对上。
    private static Account createWithId(Context context, List<Account> accounts, String id,
            String name) {
        if (byId(accounts, id) != null) {
            id = nextId(accounts);
        }
        int slot = freeSlot(accounts);
        if (slot < 0) {
            return null;
        }
        Account account = new Account(id, name, slot);
        accounts.add(account);
        save(context, accounts);
        return account;
    }

    public static boolean rename(Context context, String id, String name) {
        synchronized (LOCK) {
            List<Account> accounts = load(context);
            for (int i = 0; i < accounts.size(); i++) {
                Account account = accounts.get(i);
                if (account.id.equals(id)) {
                    accounts.set(i, new Account(account.id, name, account.slot));
                    save(context, accounts);
                    return true;
                }
            }
            return false;
        }
    }

    // 登录昵称由网页进程写入、主进程读取，所以走文件：SharedPreferences 按进程缓存，另一方看不到。
    public static String nickname(Context context, String id) {
        File file = nicknameFile(context, id);
        if (!file.exists()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            return value != null ? value.trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean setNickname(Context context, String id, String nickname) {
        String trimmed = nickname.trim();
        if (trimmed.isEmpty() || trimmed.equals(nickname(context, id))) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(nicknameFile(context, id))) {
            out.write(trimmed.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return false;
        }
        return true;
    }

    public static void remove(Context context, String id) {
        synchronized (LOCK) {
            List<Account> accounts = load(context);
            int removedSlot = -1;
            for (int i = accounts.size() - 1; i >= 0; i--) {
                if (accounts.get(i).id.equals(id)) {
                    removedSlot = accounts.remove(i).slot;
                }
            }
            if (removedSlot < 0) {
                return;
            }
            save(context, accounts);
            markWipePending(context, removedSlot);
            File nicknameFile = nicknameFile(context, id);
            if (nicknameFile.exists()) {
                nicknameFile.delete();
            }
            if (id.equals(prefs(context).getString(KEY_ACTIVE, ""))) {
                if (accounts.isEmpty()) {
                    prefs(context).edit().remove(KEY_ACTIVE).apply();
                } else {
                    setActive(context, accounts.get(0).id);
                }
            }
        }
    }

    public static boolean hasWipePending(Context context, int slot) {
        return wipeFile(context, slot).exists();
    }

    public static void clearWipePending(Context context, int slot) {
        File file = wipeFile(context, slot);
        if (file.exists()) {
            file.delete();
        }
    }

    private static void markWipePending(Context context, int slot) {
        try {
            wipeFile(context, slot).createNewFile();
        } catch (IOException ignored) {
        }
    }

    // 跨进程读取，所以用文件而不是 SharedPreferences：后者按进程缓存，账号进程看不到主进程刚写的值。
    private static File wipeFile(Context context, int slot) {
        return new File(context.getApplicationContext().getFilesDir(), WIPE_FILE_PREFIX + slot);
    }

    private static File nicknameFile(Context context, String id) {
        return new File(context.getApplicationContext().getFilesDir(), NICKNAME_FILE_PREFIX + id);
    }

    private static int freeSlot(List<Account> accounts) {
        for (int slot = 0; slot < WebActivity.SLOT_COUNT; slot++) {
            boolean taken = false;
            for (Account account : accounts) {
                if (account.slot == slot) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return slot;
            }
        }
        return -1;
    }

    private static String nextId(List<Account> accounts) {
        String id;
        do {
            id = "acc" + System.currentTimeMillis() + "-" + accounts.size();
        } while (byId(accounts, id) != null);
        return id;
    }

    private static Account byId(List<Account> accounts, String id) {
        for (Account account : accounts) {
            if (account.id.equals(id)) {
                return account;
            }
        }
        return null;
    }

    private static void save(Context context, List<Account> accounts) {
        JSONArray array = new JSONArray();
        try {
            for (Account account : accounts) {
                JSONObject item = new JSONObject();
                item.put("id", account.id);
                item.put("name", account.name);
                item.put("slot", account.slot);
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
