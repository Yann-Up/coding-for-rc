package com.kimirc.shell;

import android.app.Activity;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class PrivacyStore {

    private static final String FILE_NAME = "allow_screenshots";
    private static final String ALLOWED = "1";

    private PrivacyStore() {
    }

    // 跨进程读取：WebActivity0..3 各跑在自己的进程里，SharedPreferences 按进程缓存，读不到主进程刚写的值。
    public static boolean allowScreenshots(Context context) {
        File file = flagFile(context);
        if (!file.exists()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            return value != null && ALLOWED.equals(value.trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static void setAllowScreenshots(Context context, boolean allow) {
        File file = flagFile(context);
        if (!allow) {
            // 默认即保护，关掉开关就直接删掉标记文件。
            file.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ALLOWED.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    // 截屏保护默认开启：只有用户在设置里显式关掉，才会清掉 FLAG_SECURE。
    public static void applyScreenshotPolicy(Activity activity) {
        Window window = activity.getWindow();
        if (window != null) {
            applyScreenshotPolicy(window, activity);
        }
    }

    // 弹窗是独立窗口，Activity 上的标志不会带过去，所以这些窗口要单独套用一次。
    // 已经是对的状态就别再动窗口：addFlags/clearFlags 每次都会触发一次 relayout。
    public static void applyScreenshotPolicy(Window window, Context context) {
        boolean protect = !allowScreenshots(context);
        boolean hasFlag =
                (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        if (protect == hasFlag) {
            return;
        }
        if (protect) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private static File flagFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }
}
