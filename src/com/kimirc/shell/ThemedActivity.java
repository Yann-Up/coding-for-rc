package com.kimirc.shell;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

// 明暗模式要在 setContentView 之前作用到资源上，所以统一在 attachBaseContext 里包一层配置。
public abstract class ThemedActivity extends Activity {

    // 本实例是按哪个主题建起来的：设置页换主题时只重建了它自己，别的窗口靠这个值判断自己是否过期。
    private String appliedTheme;
    private boolean themeReloadPending;

    @Override
    protected void attachBaseContext(Context base) {
        appliedTheme = ThemeStore.mode(base);
        super.attachBaseContext(ThemeStore.wrap(base, appliedTheme));
    }

    // 截屏保护在这里统一生效，子类不要再自己加 FLAG_SECURE：加在了本地，用户关掉开关也清不掉。
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PrivacyStore.applyScreenshotPolicy(this);
    }

    // 开关和主题都在设置页上，别的窗口不会自己更新；回到前台的窗口在这里补一次，
    // 账号进程也顺带读到主进程刚写下的新值。
    @Override
    protected void onResume() {
        super.onResume();
        PrivacyStore.applyScreenshotPolicy(this);
        if (!themeReloadPending && !appliedTheme.equals(ThemeStore.mode(this))) {
            // uiMode 只在 attachBaseContext 里覆盖一次，主题换过就得重建，否则这个窗口一直用旧主题。
            themeReloadPending = true;
            recreate();
        }
    }
}
