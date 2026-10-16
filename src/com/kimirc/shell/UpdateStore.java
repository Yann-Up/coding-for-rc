package com.kimirc.shell;

import android.content.Context;
import android.content.SharedPreferences;

// 更新检查的本地状态：自动检查开关、上次检查时间、被忽略的版本。
// 只有主页和设置页会读写它，两者都跑在主进程，所以这里用 SharedPreferences 就够；
// 账号进程不参与更新检查，不需要像 PrivacyStore / ThemeStore 那样落到文件里。
public final class UpdateStore {

    private static final String PREFS_NAME = "update";
    private static final String KEY_AUTO_CHECK = "auto_check";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_SKIPPED_TAG = "skipped_tag";

    // 自动检查的最小间隔：主页每次回到前台都会问一次，靠这个值把它压到一天一次。
    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private UpdateStore() {
    }

    public static boolean autoCheckEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CHECK, true);
    }

    public static void setAutoCheckEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply();
    }

    public static long lastCheck(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0L);
    }

    public static void markChecked(Context context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    // 用户点了「忽略此版本」：只静默同一版本，手动检查仍然照常显示。
    public static boolean isSkipped(Context context, String tag) {
        return tag != null && !tag.isEmpty() && tag.equals(skippedTag(context));
    }

    public static void skip(Context context, String tag) {
        prefs(context).edit().putString(KEY_SKIPPED_TAG, tag == null ? "" : tag).apply();
    }

    private static String skippedTag(Context context) {
        return prefs(context).getString(KEY_SKIPPED_TAG, "");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
