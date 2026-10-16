package com.kimirc.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

// 安装结果由 PackageInstaller 通过 PendingIntent 回投。
// receiver 不导出：PendingIntent 是本应用自己创建的，发送时以创建者身份投递，所以不导出也收得到；
// 不导出顺带挡掉别的应用伪造「安装成功」广播。
public class InstallResultReceiver extends BroadcastReceiver {

    static final String ACTION_RESULT = "com.kimirc.shell.action.INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // 覆盖安装完成后本进程随即被替换掉，通常走不到这里；走到了也不必再打扰用户。
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 大多数机型会自己弹出确认框，少数不会，这里补一次。
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                // 拿不到确认页就无法继续安装，不能静默留给用户一个悬着的状态。
                toast(context, R.string.update_install_no_confirm);
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (RuntimeException e) {
                // 少数机型不允许从广播直接拉起安装确认页，同样要告知用户。
                toast(context, R.string.update_install_no_confirm);
            }
            return;
        }
        toast(context, messageFor(status));
    }

    private static void toast(Context context, int resId) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_LONG).show();
    }

    private static int messageFor(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return R.string.update_install_blocked;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return R.string.update_install_aborted;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return R.string.update_install_invalid;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return R.string.update_install_conflict;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return R.string.update_install_storage;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                // 该状态表示 SDK 版本、ABI 或设备能力不兼容；签名不符由应用内校验负责提示，这里不重复。
                return R.string.update_install_incompatible;
            default:
                return R.string.update_install_failed;
        }
    }
}
