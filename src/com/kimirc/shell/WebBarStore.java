package com.kimirc.shell;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class WebBarStore {

    private static final String FILE_NAME = "hide_web_bar";
    private static final String HIDDEN = "1";

    private WebBarStore() {
    }

    // 跨进程读取：WebActivity0..3 各跑在自己的进程里，SharedPreferences 按进程缓存，读不到主进程刚写的值。
    public static boolean hidden(Context context) {
        File file = flagFile(context);
        if (!file.exists()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            return value != null && HIDDEN.equals(value.trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static void setHidden(Context context, boolean hidden) {
        File file = flagFile(context);
        if (!hidden) {
            // 默认即显示标题栏，关掉开关就直接删掉标记文件。
            file.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(HIDDEN.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static File flagFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }
}
