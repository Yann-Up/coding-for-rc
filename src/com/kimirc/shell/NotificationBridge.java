package com.kimirc.shell;

import android.webkit.JavascriptInterface;

// 网页侧的 window.AndroidNotify。远控网页的通知全部经这里转给原生。
// 只暴露「显示/关闭一条通知」这一个最小面：不接受链接、路径或任何能驱动原生其它能力的参数，
// 入参在 NotifyCenter 里一律当纯文本限长处理。
public final class NotificationBridge {

    private final WebActivity host;

    NotificationBridge(WebActivity host) {
        this.host = host;
    }

    // JS 接口跑在 WebView 的 JavaBridge 线程上，这个方法只读系统权限状态，不碰 UI。
    @JavascriptInterface
    public String permission() {
        return NotifyCenter.permissionState(host);
    }

    @JavascriptInterface
    public void requestPermission(final String callbackId) {
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                host.requestNotifyPermission(callbackId);
            }
        });
    }

    @JavascriptInterface
    public void show(final String tag, final String title, final String body,
            final boolean silent) {
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                NotifyCenter.show(host, tag, title, body, silent);
            }
        });
    }

    @JavascriptInterface
    public void close(final String tag) {
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                NotifyCenter.cancel(host, tag);
            }
        });
    }
}
