package com.kimirc.shell;

import android.content.Context;

import java.io.File;
import java.io.IOException;

// 「清理缓存」的请求标记：网页缓存属于各账号进程自己的 WebView profile，主进程直接删文件会踩到
// 正在运行的 WebView，所以主进程只落标记，由持有该 profile 的账号进程在进入/回到远控页时自己清。
// 同样是跨进程，只能用文件，不能用 SharedPreferences。
public final class WebCacheStore {

    private static final String FILE_PREFIX = "clear_cache_slot_";

    private WebCacheStore() {
    }

    public static void requestClear(Context context, int slot) {
        try {
            pendingFile(context, slot).createNewFile();
        } catch (IOException ignored) {
        }
    }

    public static boolean hasClearPending(Context context, int slot) {
        return pendingFile(context, slot).exists();
    }

    public static void clearPending(Context context, int slot) {
        File file = pendingFile(context, slot);
        if (file.exists()) {
            file.delete();
        }
    }

    private static File pendingFile(Context context, int slot) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_PREFIX + slot);
    }
}
