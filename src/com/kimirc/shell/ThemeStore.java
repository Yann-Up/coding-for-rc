package com.kimirc.shell;

import android.content.Context;
import android.content.res.Configuration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ThemeStore {

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private static final String FILE_NAME = "theme_mode";

    private ThemeStore() {
    }

    // 跨进程读取：WebActivity0..3 各跑在自己的进程里，SharedPreferences 按进程缓存，读不到主进程刚写的值。
    public static String mode(Context context) {
        File file = modeFile(context);
        if (!file.exists()) {
            return MODE_SYSTEM;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            if (value != null) {
                value = value.trim();
                if (MODE_LIGHT.equals(value) || MODE_DARK.equals(value)) {
                    return value;
                }
            }
        } catch (IOException e) {
            return MODE_SYSTEM;
        }
        return MODE_SYSTEM;
    }

    public static void setMode(Context context, String mode) {
        File file = modeFile(context);
        if (MODE_SYSTEM.equals(mode)) {
            file.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(mode.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    // 覆盖配置里的 uiMode，让 -night 资源在手动选定的模式下生效：必须在 setContentView 之前完成，
    // 所以由 ThemedActivity.attachBaseContext 调用。mode 由调用方传入——它还要记下这个窗口是按
    // 哪个主题建起来的，好在回到前台时判断要不要重建。
    public static Context wrap(Context base, String mode) {
        if (MODE_SYSTEM.equals(mode)) {
            return base;
        }
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        int nightMode = MODE_DARK.equals(mode)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        configuration.uiMode =
                (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return base.createConfigurationContext(configuration);
    }

    private static File modeFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }
}
