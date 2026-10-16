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

    // download() 的返回状态：把「这个源根本没下成」与「整包下完了但大小/摘要对不上」分开，
    // 后者要一路报到 ERR_HASH，不能让用户只看到一句含糊的「下载失败」。
    private static final int SOURCE_FAILED = 0;
    private static final int SOURCE_OK = 1;
    private static final int SOURCE_MISMATCH = 2;

    // commit() 的内部信号：校验通过后、会话提交前用户点了取消。不对外暴露，install() 见到它静默收尾。
    private static final int COMMIT_CANCELLED = -1;

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
        // 只要有一个源把整包下完却对不上，就记下来：这跟「所有源都下不下来」不是一回事，
        // 前者说明包被动过或传输坏了，要报校验失败，不能含糊成「下载失败」。
        boolean mismatch = false;
        String[] urls = info.downloadUrls();
        for (int i = 0; i < urls.length && !cancelled; i++) {
            if (urls[i] == null || urls[i].isEmpty()) {
                continue;
            }
            int status = download(urls[i], apk, info, listener);
            if (status == SOURCE_OK) {
                downloaded = true;
                break;
            }
            if (status == SOURCE_MISMATCH) {
                mismatch = true;
            }
        }
        if (cancelled) {
            apk.delete();
            return;
        }
        if (!downloaded) {
            apk.delete();
            listener.onError(mismatch ? ERR_HASH : ERR_DOWNLOAD);
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
        if (error == COMMIT_CANCELLED) {
            // 用户在收尾阶段点了取消：会话已 abandon，什么都别提交，也不要弹错误——只当这次更新没发生过。
            return;
        }
        if (error != OK) {
            listener.onError(error);
            return;
        }
        listener.onVerified();
    }

    // 边下边算 SHA-256，文件长度与摘要都要对上才算这个源可用；对不上就清掉换下一个源。
    // 返回 SOURCE_*：调用方要靠它区分「源不可用」与「下完了但校验不符」，后者要报 ERR_HASH。
    private int download(String url, File target, UpdateChecker.Info info, Listener listener) {
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
            // 清单里给了大小就以它为准（再放宽 1 MiB 容忍差异），拿不到则用一个宽松的硬上限。
            // 这挡的是「失控或被顶替的源把缓存目录写到爆满」——正常包约 1 MiB。
            long sizeLimit = info.size > 0 ? info.size + (1L << 20) : 64L * 1024 * 1024;
            listener.onProgress(0L, total);
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled) {
                        return SOURCE_FAILED;
                    }
                    if (downloaded + read > sizeLimit) {
                        return SOURCE_FAILED;
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

            // 整包都读完了，只是长度或摘要对不上：本源的数据坏了/被换过，报 SOURCE_MISMATCH，
            // 让 install() 能一路报到 ERR_HASH，而不是混进「下载失败」。
            if (info.size > 0 && downloaded != info.size) {
                return SOURCE_MISMATCH;
            }
            if (!info.sha256.isEmpty() && !info.sha256.equals(hex(digest.digest()))) {
                return SOURCE_MISMATCH;
            }
            return SOURCE_OK;
        } catch (IOException e) {
            return SOURCE_FAILED;
        } catch (UpdateChecker.Failure e) {
            // 这个源不可用（404、限流、跳转跑到白名单外…），当作本源失败，换下一个源。
            return SOURCE_FAILED;
        } catch (NoSuchAlgorithmException e) {
            return SOURCE_FAILED;
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

    // 签名比对：下载包的**整组**签名证书必须与当前安装的包完全一致。
    // 这一步挡的是「有人换掉了服务器上的包」，因为私有密钥拿不到。
    // 多签名包的身份是完整的签名集合，只比第一张会给多签名包留出漏网的空子。
    private boolean verifySignature(File apk) {
        byte[][] remote = signerCerts(apk.getAbsolutePath(), true);
        byte[][] local = signerCerts(context.getPackageName(), false);
        if (remote == null || local == null) {
            return false;
        }
        byte[][] remoteDigests = sortedDigests(remote);
        byte[][] localDigests = sortedDigests(local);
        // 摘要算法拿不到时 sortedDigests() 返回 null，绝不能当成「一致」。
        if (remoteDigests == null || localDigests == null) {
            return false;
        }
        // 张数不同就是不同的签名集合，直接判不一致。
        if (remoteDigests.length != localDigests.length) {
            return false;
        }
        for (int i = 0; i < remoteDigests.length; i++) {
            if (!MessageDigest.isEqual(remoteDigests[i], localDigests[i])) {
                return false;
            }
        }
        return true;
    }

    private byte[][] signerCerts(String pathOrPackage, boolean fromArchive) {
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
            byte[][] certs = new byte[signers.length][];
            for (int i = 0; i < signers.length; i++) {
                if (signers[i] == null) {
                    return null;
                }
                certs[i] = signers[i].toByteArray();
            }
            return certs;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // 每张证书各自取 SHA-256 摘要，再按字节序排序：集合身份与顺序无关，排好序才能逐个比对。
    private static byte[][] sortedDigests(byte[][] certs) {
        byte[][] digests = new byte[certs.length][];
        for (int i = 0; i < certs.length; i++) {
            byte[] digest = sha256(certs[i]);
            // 摘要算法拿不到时 sha256() 返回空数组，两个空数组相等会造成误判，这里先挡掉。
            if (digest.length == 0) {
                return null;
            }
            digests[i] = digest;
        }
        for (int i = 1; i < digests.length; i++) {
            byte[] key = digests[i];
            int j = i - 1;
            while (j >= 0 && compareBytes(digests[j], key) > 0) {
                digests[j + 1] = digests[j];
                j--;
            }
            digests[j + 1] = key;
        }
        return digests;
    }

    private static int compareBytes(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = left[i] & 0xff;
            int b = right[i] & 0xff;
            if (a != b) {
                return a - b;
            }
        }
        return left.length - right.length;
    }

    private int commit(File apk) {
        PackageManager manager = context.getPackageManager();
        PackageInstaller installer = manager.getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId = -1;
        boolean committed = false;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            params.setSize(apk.length());
            sessionId = installer.createSession(params);
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
            // 校验通过之后、真正提交之前用户还可能点取消：这里最后确认一次。
            // 取消是尽力而为，但「绝不提交用户没点头的安装」是硬要求，所以宁可白写一遍 staging。
            if (cancelled) {
                return COMMIT_CANCELLED;
            }
            session.commit(resultIntent());
            committed = true;
            return OK;
        } catch (IOException e) {
            return ERR_INSTALL;
        } catch (SecurityException e) {
            return ERR_INSTALL;
        } finally {
            if (session != null) {
                if (committed) {
                    // 已经交给系统的会话才 close()：它此后归系统管。
                    try {
                        session.close();
                    } catch (Exception ignored) {
                    }
                } else {
                    // 没提交成的会话必须 abandon() 而不是 close()：close() 只释放这个对象，
                    // 会话和已经写进去的 staging 数据会留在系统里，反复失败会白占存储。
                    try {
                        session.abandon();
                    } catch (Exception ignored) {
                    }
                }
            } else if (sessionId >= 0) {
                // 会话建出来了但没打开成功（session 仍为 null），按 id 丢掉，别留个空会话占地方。
                try {
                    installer.abandonSession(sessionId);
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
