package com.kimirc.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Window;

// 弹窗跑在自己的窗口里，Activity 上的 FLAG_SECURE 带不过来，所以单独补一次。
// Builder.show() 内部会走 create()，因此放在 create() 里，两种调用方式都能覆盖到。
public final class SecureAlertDialog {

    private SecureAlertDialog() {
    }

    public static class Builder extends AlertDialog.Builder {

        public Builder(Context context, int themeResId) {
            super(context, themeResId);
        }

        @Override
        public AlertDialog create() {
            AlertDialog dialog = super.create();
            Window window = dialog.getWindow();
            if (window != null) {
                PrivacyStore.applyScreenshotPolicy(window, getContext());
            }
            return dialog;
        }
    }
}
