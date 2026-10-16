package com.kimirc.shell;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends ThemedActivity {

    // 顺序与主题对话框的单选项一一对应。
    private static final String[] THEME_MODES = {
            ThemeStore.MODE_SYSTEM, ThemeStore.MODE_LIGHT, ThemeStore.MODE_DARK
    };

    private TextView themeValue;
    private TextView appLockValue;
    private TextView appLockHint;
    private TextView versionValue;
    private UpdatePrompt updatePrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        themeValue = findViewById(R.id.theme_value);
        themeValue.setText(themeLabel(ThemeStore.mode(this)));

        appLockValue = findViewById(R.id.app_lock_value);
        appLockHint = findViewById(R.id.app_lock_hint);

        versionValue = findViewById(R.id.version_value);
        versionValue.setText(versionLabel());

        updatePrompt = new UpdatePrompt(this);
        findViewById(R.id.version_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });

        Switch autoUpdateSwitch = findViewById(R.id.auto_update_switch);
        autoUpdateSwitch.setChecked(UpdateStore.autoCheckEnabled(this));
        autoUpdateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                UpdateStore.setAutoCheckEnabled(SettingsActivity.this, isChecked);
            }
        });

        Switch screenshotSwitch = findViewById(R.id.screenshot_switch);
        screenshotSwitch.setChecked(!PrivacyStore.allowScreenshots(this));
        screenshotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrivacyStore.setAllowScreenshots(SettingsActivity.this, !isChecked);
                // 开关立即生效，不必重进页面。
                PrivacyStore.applyScreenshotPolicy(SettingsActivity.this);
            }
        });

        findViewById(R.id.theme_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemeDialog();
            }
        });

        findViewById(R.id.about_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAboutDialog();
            }
        });
    }

    // 用户可能刚去系统设置里加了锁屏，所以每次回到页面都重新判断一次。
    @Override
    protected void onResume() {
        super.onResume();
        refreshAppLock();
        // 刚去系统设置里授权过「安装未知应用」的话，这里接着把更新装完。
        updatePrompt.onResume();
    }

    @Override
    protected void onDestroy() {
        updatePrompt.destroy();
        super.onDestroy();
    }

    // 手动检查：先在本行右侧给出「检查中…」，检查结束无论成败都还原成本机版本号。
    private void checkUpdate() {
        versionValue.setText(R.string.settings_update_checking);
        updatePrompt.checkManually(new Runnable() {
            @Override
            public void run() {
                versionValue.setText(versionLabel());
            }
        });
    }

    private void showThemeDialog() {
        final String current = ThemeStore.mode(this);
        String[] labels = {
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
        };
        new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.theme_dialog_title)
                .setSingleChoiceItems(labels, themeIndex(current), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        String mode = THEME_MODES[which];
                        if (!mode.equals(current)) {
                            ThemeStore.setMode(SettingsActivity.this, mode);
                            recreate();
                        }
                    }
                })
                .show();
    }

    private String themeLabel(String mode) {
        if (ThemeStore.MODE_DARK.equals(mode)) {
            return getString(R.string.theme_dark);
        }
        if (ThemeStore.MODE_LIGHT.equals(mode)) {
            return getString(R.string.theme_light);
        }
        return getString(R.string.theme_system);
    }

    private static int themeIndex(String mode) {
        for (int i = 0; i < THEME_MODES.length; i++) {
            if (THEME_MODES[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    private void refreshAppLock() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean secure = keyguard != null && keyguard.isDeviceSecure();
        appLockValue.setText(secure
                ? R.string.settings_app_lock_on
                : R.string.settings_app_lock_off);
        appLockHint.setText(secure
                ? R.string.settings_app_lock_hint_on
                : R.string.settings_app_lock_hint_off);
        appLockValue.setTextColor(getColor(secure ? R.color.text_secondary : R.color.danger));
    }

    // 版本号由 build.sh 在链接时写入，manifest 里同名的两个属性会被它覆盖，所以这里读到的就是实际打进包的值。
    private String versionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return getString(R.string.settings_version_value, info.versionName, info.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.settings_version_unknown);
        }
    }

    private void showAboutDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_about, null);
        new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.settings_about)
                .setView(content)
                .setPositiveButton(R.string.about_close, null)
                .show();
    }
}
