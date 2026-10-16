package com.kimirc.shell;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

// 下载并安装新版本。整条链路的信任锚是「签名必须与已安装包一致」：
// 下载源（CNB / GitHub）都只是搬运工，任何一个源给出被换过的包都会在签名这步被拦下；
// SHA-256 负责拦住传输损坏，两者都过不了就不安装、只提示去发布页手动下载。
//
// 安装走 PackageInstaller 会话，不需要 FileProvider（本项目没有 AndroidX），也不把
// 临时包通过 Intent 递给别人。临时包只落在应用私有缓存目录，用完即删。
final class UpdateInstaller {

    static final int OK = 0;
    static final int ERR_PERMISSION = 1;   // 未授权「安装未知应用」
    static final int ERR_DOWNLOAD = 2;     // 所有源都下不下来
    static final int ERR_HASH = 3;         // 与清单里的 SHA-256 不符
    static final int ERR_SIGNATURE = 4;    // 签名与已安装包不一致
    static final int ERR_INSTALL = 5;      // 会话创建或提交失败

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_STEP = 64 * 1024;

    interface Listener {
        void onProgress(long downloaded, long total);

        // 校验通过、已交给系统安装，接下来由系统弹确认框。
        void onVerified();

        void onError(int code);
    }

    private final Context context;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;

    UpdateInstaller(Context context) {
        this.context = context.getApplicationContext();
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            // 关掉连接让阻塞中的 read 立刻抛错退出，比等超时快得多。
            conn.disconnect();
        }
    }

    // 阻塞式，调用方放到工作线程上跑。
    void install(UpdateChecker.Info info, Listener listener) {
        PackageManager manager = context.getPackageManager();
        if (!manager.canRequestPackageInstalls()) {
            listener.onError(ERR_PERMISSION);
            return;
        }
        // 清单里没有摘要就不装：签名能挡住换包，但拦不住传输损坏。两处都拿不到摘要时宁可放弃这次更新。
        if (info.sha256.isEmpty()) {
            listener.onError(ERR_HASH);
            return;
        }

        File apk = new File(updateDir(), info.apkName);
        boolean downloaded = false;
        String[] urls = info.downloadUrls();
        for (int i = 0; i < urls.length && !cancelled; i++) {
            if (urls[i] == null || urls[i].isEmpty()) {
                continue;
            }
            if (download(urls[i], apk, info, listener)) {
                downloaded = true;
                break;
            }
        }
        if (cancelled) {
            apk.delete();
            return;
        }
        if (!downloaded) {
            apk.delete();
            listener.onError(ERR_DOWNLOAD);
            return;
        }

        if (!verifySignature(apk)) {
            apk.delete();
            listener.onError(ERR_SIGNATURE);
            return;
        }
        if (cancelled) {
            apk.delete();
            return;
        }

        // 先交给系统，成了再报「已交给系统安装」：反过来的话，提交失败时用户已经先听到一句乐观的话。
        int error = commit(apk);
        apk.delete();
        if (error != OK) {
            listener.onError(error);
            return;
        }
        listener.onVerified();
    }

    // 边下边算 SHA-256，文件长度与摘要都要对上才算这个源可用；对不上就清掉换下一个源。
    private boolean download(String url, File target, UpdateChecker.Info info, Listener listener) {
        FileOutputStream fileOut = null;
        HttpURLConnection conn = null;
        try {
            conn = UpdateChecker.open(url, context, 10000);
            activeConnection = conn;
            long total = conn.getContentLength() > 0 ? conn.getContentLength() : info.size;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fileOut = new FileOutputStream(target);
            long downloaded = 0L;
            long lastReported = 0L;
            listener.onProgress(0L, total);
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled) {
                        return false;
                    }
                    fileOut.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    if (downloaded - lastReported >= PROGRESS_STEP) {
                        lastReported = downloaded;
                        listener.onProgress(downloaded, total);
                    }
                }
            }
            listener.onProgress(downloaded, total);
            fileOut.close();
            fileOut = null;

            if (info.size > 0 && downloaded != info.size) {
                return false;
            }
            if (!info.sha256.isEmpty() && !info.sha256.equals(hex(digest.digest()))) {
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        } catch (UpdateChecker.Failure e) {
            // 这个源不可用（404、限流、跳转跑到白名单外…），当作本源失败，换下一个源。
            return false;
        } catch (NoSuchAlgorithmException e) {
            return false;
        } finally {
            activeConnection = null;
            if (fileOut != null) {
                try {
                    fileOut.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
            if (!target.exists() || target.length() == 0) {
                target.delete();
            }
        }
    }

    // 签名比对：下载包的签名证书必须与当前安装的包完全一致。
    // 这一步挡的是「有人换掉了服务器上的包」，因为私有密钥拿不到。
    private boolean verifySignature(File apk) {
        byte[] remote = signerCert(apk.getAbsolutePath(), true);
        byte[] local = signerCert(context.getPackageName(), false);
        if (remote == null || local == null) {
            return false;
        }
        byte[] remoteHash = sha256(remote);
        // 摘要算法拿不到时 sha256() 返回空数组，两个空数组相等会造成误判，这里先挡掉。
        if (remoteHash.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(remoteHash, sha256(local));
    }

    private byte[] signerCert(String pathOrPackage, boolean fromArchive) {
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo info = fromArchive
                    ? manager.getPackageArchiveInfo(pathOrPackage,
                            PackageManager.GET_SIGNING_CERTIFICATES)
                    : manager.getPackageInfo(pathOrPackage,
                            PackageManager.GET_SIGNING_CERTIFICATES);
            if (info == null || info.signingInfo == null) {
                return null;
            }
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) {
                return null;
            }
            return signers[0].toByteArray();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private int commit(File apk) {
        PackageManager manager = context.getPackageManager();
        PackageInstaller installer = manager.getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            params.setSize(apk.length());
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            session.commit(resultIntent());
            return OK;
        } catch (IOException e) {
            return ERR_INSTALL;
        } catch (SecurityException e) {
            return ERR_INSTALL;
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // FLAG_MUTABLE 是 API 31 才有的常量，但它是编译期常量，在更低的系统上只是一个被忽略的位；
    // 不加它，API 31+ 的系统就没法往 Intent 里写安装结果。
    private IntentSender resultIntent() {
        Intent intent = new Intent(context, InstallResultReceiver.class);
        intent.setAction(InstallResultReceiver.ACTION_RESULT);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE).getIntentSender();
    }

    private File updateDir() {
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            return new byte[0];
        }
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte b : data) {
            text.append(String.format(Locale.US, "%02x", b));
        }
        return text.toString();
    }
}
