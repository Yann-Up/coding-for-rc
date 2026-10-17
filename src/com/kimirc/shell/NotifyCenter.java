package com.kimirc.shell;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 远控网页的提醒（回合完成 / 待回答 / 待审批）落到系统通知栏：网页经 NotificationShim 与
// NotificationBridge 把标题、正文、去重 tag 交过来，这里负责权限、渠道与点击回灌。
public final class NotifyCenter {

    private static final String TAG = "KimiRC";
    private static final String CHANNEL_SOUND = "rc_alerts";
    private static final String CHANNEL_SILENT = "rc_alerts_silent";
    private static final String ASKED_FILE = "notify_permission_asked";
    private static final int TITLE_LIMIT = 60;
    private static final int BODY_LIMIT = 160;

    // 本进程内用户拒绝过一次系统权限：系统不会再弹窗，页面也就没必要反复请求。
    private static volatile boolean deniedInProcess;

    private NotifyCenter() {
    }

    // 页面每次发送前都会读这个值，语义必须是「系统当下是否授权」：
    // 未授权时报 default，页面才会走 requestPermission；报 denied 会让它直接放弃发送。
    static String permissionState(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 13 以下没有通知运行时权限，装上了就能发。
            return "granted";
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return "granted";
        }
        return deniedInProcess ? "denied" : "default";
    }

    static void rememberDenied() {
        deniedInProcess = true;
    }

    // 通知权限只能在应用处于前台时弹窗，而页面的请求都发生在后台（回合完成的那一刻），
    // 所以进入远控页时先主动问一次；问过就不再打扰，用户之后仍可在系统设置里改。
    static boolean shouldAsk(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        return "default".equals(permissionState(context)) && !askedFile(context).exists();
    }

    // 账号进程各写一次就够了：只有「问过没」这一位，重复写是幂等的。
    static void markAsked(Context context) {
        try (FileOutputStream out = new FileOutputStream(askedFile(context))) {
            out.write("1".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    static void show(WebActivity host, String tag, String title, String body, boolean silent) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        if (!"granted".equals(permissionState(host))) {
            Log.i(TAG, "notify dropped: permission not granted");
            return;
        }
        String safeTitle = clean(title, TITLE_LIMIT);
        if (safeTitle.isEmpty()) {
            return;
        }
        // 正文带会话标题、提问预览或工具名，锁屏上会暴露出去；截屏保护开着时只给状态文案。
        String safeBody = PrivacyStore.allowScreenshots(host)
                ? clean(body, BODY_LIMIT)
                : host.getString(R.string.notify_masked_body);

        ensureChannels(host);
        NotificationManager manager =
                (NotificationManager) host.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Notification.Builder builder = new Notification.Builder(host,
                silent ? CHANNEL_SILENT : CHANNEL_SOUND)
                // 状态栏只取 alpha 当遮罩，所以这张图是从 logo 派生的单色剪影（见 tools/make-icons.py）。
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(new Notification.BigTextStyle().bigText(safeBody))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(PendingIntent.getActivity(host, tag.hashCode(),
                        host.notificationIntent(tag),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // 展开后的大图标不走遮罩也不染色，这里用彩色 logo；解不出来就只留小图标。
        Bitmap largeIcon = BitmapFactory.decodeResource(host.getResources(),
                R.drawable.ic_notify_large);
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon);
        }
        // tag 即通知 ID：网页用同一 tag 重发时自动替换，不会堆叠。
        manager.notify(tag.hashCode(), builder.build());
        // 标题与正文属于远控内容，日志里只留结构信息。
        Log.i(TAG, "notify shown, silent=" + silent);
    }

    static void cancel(Context context, String tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(tag.hashCode());
        }
    }

    // 渠道在建应用通知设置里就能看到，所以在申请权限之前先建好；只建一次。
    static void ensureChannels(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (manager.getNotificationChannel(CHANNEL_SOUND) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_SOUND,
                    context.getString(R.string.notify_channel_sound),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.notify_channel_sound_desc));
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(CHANNEL_SILENT) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_SILENT,
                    context.getString(R.string.notify_channel_silent),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.notify_channel_silent_desc));
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    // 网页给的标题与正文是不可信输入：控制字符一律抹平、空白折叠、超长截断。
    private static String clean(String value, int limit) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), limit));
        boolean pendingSpace = false;
        for (int i = 0; i < value.length() && builder.length() < limit; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                c = ' ';
            }
            if (c == ' ') {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.append(c);
        }
        return builder.toString().trim();
    }

    private static File askedFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), ASKED_FILE);
    }
}
