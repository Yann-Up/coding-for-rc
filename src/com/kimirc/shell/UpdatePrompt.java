package com.kimirc.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

// 更新流程的界面部分：检查、提示、下载进度、安装前后的几个弹窗。
// 每个用它的 Activity 建一个实例，并在自己的 onResume / onDestroy 里转发给 onResume / destroy。
//
// 自动检查只在解锁之后由主页触发；检查失败一律静默，手动检查才会把失败摆到用户面前。
final class UpdatePrompt {

    // 手动检查可以多等一会儿；自动检查是后台行为，预算收紧到 4 秒，宁可这次查不到。
    private static final int CHECK_TIMEOUT_MS = 8000;
    private static final int SILENT_TIMEOUT_MS = 4000;
    private static final int CROSS_CHECK_TIMEOUT_MS = 4000;

    // 两个源给出的版本或摘要对不上，属于来源内部错误，不是网络问题，所以单独一个代号。
    private static final int ERR_INCONSISTENT = 100;

    private final Activity activity;
    private volatile boolean checking;
    private Thread worker;
    private UpdateInstaller installer;
    private AlertDialog dialog;
    private AlertDialog progressDialog;
    private TextView progressDetail;
    private ProgressBar progressBar;
    // 下载与安装这一段已经收尾（交出去了，或者报错了），此后关掉进度对话框不能再当成「用户取消」。
    private boolean verified;
    // 等用户去系统设置里授权「安装未知应用」期间挂着的待装版本。
    private UpdateChecker.Info pendingInstall;
    // 手动检查结束时回主线程跑一次，用来把入口上那句「检查中…」还原。
    private Runnable checkFinished;
    private boolean destroyed;

    UpdatePrompt(Activity activity) {
        this.activity = activity;
    }

    // 用户从系统设置授权回来后会走到这里，条件满足就接着装。
    void onResume() {
        if (destroyed || pendingInstall == null) {
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        UpdateChecker.Info info = pendingInstall;
        pendingInstall = null;
        startInstall(info);
    }

    void destroy() {
        destroyed = true;
        pendingInstall = null;
        if (installer != null) {
            installer.cancel();
        }
        if (worker != null) {
            worker.interrupt();
        }
        dismissProgress();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    void checkManually() {
        checkManually(null);
    }

    // onFinished 在本次检查结束时回到主线程跑一次（无论成功失败），用于复原入口上的状态。
    // 正在检查时再次点按直接忽略，不排队。
    void checkManually(final Runnable onFinished) {
        if (checking || destroyed) {
            return;
        }
        checkFinished = onFinished;
        runCheck(true);
    }

    // 主页解锁后调用：受开关与 24 小时间隔约束，只有真的发现新版本才会弹窗。
    void checkSilently() {
        if (destroyed || checking) {
            return;
        }
        if (!UpdateStore.autoCheckEnabled(activity)) {
            return;
        }
        long elapsed = System.currentTimeMillis() - UpdateStore.lastCheck(activity);
        if (elapsed < UpdateStore.AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        runCheck(false);
    }

    private void runCheck(final boolean manual) {
        if (checking || destroyed) {
            return;
        }
        checking = true;
        startWorker(new Runnable() {
            @Override
            public void run() {
                Result result = fetchBest(manual);
                // 失败也记一次时间：否则每次回到前台都会重试一轮。
                UpdateStore.markChecked(activity);
                checking = false;
                postResult(result, manual);
            }
        });
    }

    private static final class Result {
        UpdateChecker.Info info;
        int error = UpdateChecker.OK;
    }

    private Result fetchBest(boolean manual) {
        Result result = new Result();
        int timeout = manual ? CHECK_TIMEOUT_MS : SILENT_TIMEOUT_MS;
        try {
            // CNB 优先：国内网络下它是唯一稳定可达的那个。
            result.info = UpdateChecker.fetch(activity, UpdateChecker.SOURCE_CNB, timeout);
        } catch (UpdateChecker.Failure cnb) {
            result.error = cnb.code;
        }
        if (result.info == null) {
            try {
                result.info = UpdateChecker.fetch(activity, UpdateChecker.SOURCE_GITHUB, timeout);
                result.error = UpdateChecker.OK;
            } catch (UpdateChecker.Failure github) {
                result.error = github.code;
            }
        }
        // 手动检查时顺手问一遍另一个源：单个源被换过内容的话，两侧对不上就能发现。
        // 自动检查不做这一步，省的几秒比这点收益更值。
        if (result.info != null && manual && !destroyed) {
            UpdateChecker.Info other = probeOther(result.info.source);
            if (other != null && !UpdateChecker.consistent(result.info, other)) {
                result.info = null;
                result.error = ERR_INCONSISTENT;
            }
        }
        return result;
    }

    private UpdateChecker.Info probeOther(int source) {
        int other = source == UpdateChecker.SOURCE_CNB
                ? UpdateChecker.SOURCE_GITHUB
                : UpdateChecker.SOURCE_CNB;
        try {
            return UpdateChecker.fetch(activity, other, CROSS_CHECK_TIMEOUT_MS);
        } catch (UpdateChecker.Failure e) {
            return null;
        }
    }

    private void postResult(final Result result, final boolean manual) {
        post(new Runnable() {
            @Override
            public void run() {
                Runnable finished = checkFinished;
                checkFinished = null;
                if (finished != null) {
                    finished.run();
                }
                if (result.error != UpdateChecker.OK) {
                    if (manual) {
                        showFailure(result.error);
                    }
                    return;
                }
                UpdateChecker.Info info = result.info;
                if (info == null) {
                    if (manual) {
                        showFailure(UpdateChecker.ERR_NETWORK);
                    }
                    return;
                }
                if (!UpdateChecker.isNewer(activity, info)) {
                    if (manual) {
                        showUpToDate(info);
                    }
                    return;
                }
                if (!manual && UpdateStore.isSkipped(activity, info.tag())) {
                    return;
                }
                showUpdate(info);
            }
        });
    }

    private void showUpdate(final UpdateChecker.Info info) {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_update, null);
        TextView current = content.findViewById(R.id.update_current);
        TextView latest = content.findViewById(R.id.update_latest);
        TextView notes = content.findViewById(R.id.update_notes);
        TextView source = content.findViewById(R.id.update_source);

        current.setText(activity.getString(R.string.settings_version_value,
                UpdateChecker.installedVersionName(activity),
                UpdateChecker.installedVersionCode(activity)));
        latest.setText(activity.getString(R.string.settings_version_value,
                info.versionName, info.versionCode));
        if (info.notes.isEmpty()) {
            notes.setVisibility(View.GONE);
        } else {
            notes.setText(info.notes);
        }
        source.setText(activity.getString(R.string.update_source_line,
                UpdateChecker.sourceName(activity, info.source)));

        dialog = new SecureAlertDialog.Builder(activity,
                R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(activity.getString(R.string.update_new_title, "v" + info.versionName))
                .setView(content)
                .setPositiveButton(R.string.update_download_install, null)
                .setNeutralButton(R.string.update_skip, null)
                .setNegativeButton(R.string.update_later, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setTextColor(activity.getColor(R.color.text_secondary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        startInstall(info);
                    }
                });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UpdateStore.skip(activity, info.tag());
                        dialog.dismiss();
                    }
                });
    }

    private void showUpToDate(UpdateChecker.Info info) {
        showMessage(R.string.update_dialog_title,
                activity.getString(R.string.update_up_to_date,
                        UpdateChecker.installedVersionName(activity)),
                info.pageUrl);
    }

    private void showFailure(int code) {
        showMessage(R.string.update_dialog_title, activity.getString(errorText(code)),
                UpdateChecker.CNB_RELEASES_PAGE);
    }

    private void showInstallFailure(int code) {
        if (code == UpdateInstaller.ERR_PERMISSION) {
            showPermission();
            return;
        }
        showMessage(R.string.update_dialog_title, activity.getString(installErrorText(code)),
                UpdateChecker.CNB_RELEASES_PAGE);
    }

    private void showMessage(int titleRes, CharSequence message, final String pageUrl) {
        dialog = new SecureAlertDialog.Builder(activity,
                R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(R.string.update_open_release,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                openPage(pageUrl);
                            }
                        })
                .setNegativeButton(R.string.update_close, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
    }

    private void showPermission() {
        dialog = new SecureAlertDialog.Builder(activity,
                R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.update_unknown_source_title)
                .setMessage(R.string.update_unknown_source_message)
                .setPositiveButton(R.string.update_goto_settings,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                openUnknownSourceSettings();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
    }

    private void openUnknownSourceSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.external_error, Toast.LENGTH_SHORT).show();
        }
    }

    // 只有本应用自己认得的域名才会被打开，清单里塞进来的地址要先过白名单。
    private void openPage(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme()) || !UpdateChecker.isAllowedHost(uri.getHost())) {
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.external_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void startInstall(final UpdateChecker.Info info) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = info;
            showPermission();
            return;
        }
        pendingInstall = null;
        verified = false;
        installer = new UpdateInstaller(activity);
        showProgress();
        final UpdateInstaller current = installer;
        startWorker(new Runnable() {
            @Override
            public void run() {
                current.install(info, new UpdateInstaller.Listener() {
                    @Override
                    public void onProgress(final long downloaded, final long total) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                updateProgress(downloaded, total);
                            }
                        });
                    }

                    @Override
                    public void onVerified() {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                verified = true;
                                dismissProgress();
                                Toast.makeText(activity, R.string.update_verified,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onError(final int code) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                verified = true;
                                dismissProgress();
                                showInstallFailure(code);
                            }
                        });
                    }
                });
            }
        });
    }

    private void showProgress() {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_download, null);
        progressDetail = content.findViewById(R.id.download_detail);
        progressBar = content.findViewById(R.id.download_progress);
        progressBar.setProgress(0);
        progressDetail.setText(R.string.update_download_preparing);

        progressDialog = new SecureAlertDialog.Builder(activity,
                R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.update_download_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .create();
        progressDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                // 关掉进度框就等于取消下载，别让它在后台继续跑。
                if (!verified && installer != null) {
                    installer.cancel();
                }
                progressDialog = null;
                progressBar = null;
                progressDetail = null;
            }
        });
        progressDialog.show();
        progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
    }

    private void updateProgress(long downloaded, long total) {
        if (progressBar == null || progressDetail == null) {
            return;
        }
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress((int) (downloaded * 100 / total));
            progressDetail.setText(activity.getString(R.string.update_download_progress,
                    formatSize(downloaded), formatSize(total)));
        } else {
            progressBar.setIndeterminate(true);
            progressDetail.setText(activity.getString(R.string.update_download_progress_unknown,
                    formatSize(downloaded)));
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static int errorText(int code) {
        switch (code) {
            case ERR_INCONSISTENT:
                return R.string.update_err_inconsistent;
            case UpdateChecker.ERR_RATE_LIMIT:
                return R.string.update_err_rate_limit;
            case UpdateChecker.ERR_NOT_FOUND:
                return R.string.update_err_not_found;
            case UpdateChecker.ERR_PARSE:
                return R.string.update_err_parse;
            case UpdateChecker.ERR_NETWORK:
            default:
                return R.string.update_err_network;
        }
    }

    private static int installErrorText(int code) {
        switch (code) {
            case UpdateInstaller.ERR_DOWNLOAD:
                return R.string.update_err_download;
            case UpdateInstaller.ERR_HASH:
                return R.string.update_err_hash;
            case UpdateInstaller.ERR_SIGNATURE:
                return R.string.update_sign_mismatch;
            case UpdateInstaller.ERR_INSTALL:
            default:
                return R.string.update_install_failed;
        }
    }

    private void startWorker(Runnable task) {
        Thread thread = new Thread(task, "kimirc-update");
        worker = thread;
        thread.start();
    }

    private void post(Runnable action) {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(action);
    }
}
