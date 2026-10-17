package com.kimirc.shell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public abstract class WebActivity extends ThemedActivity {

    public static final String EXTRA_NAME = "com.kimirc.shell.extra.NAME";
    public static final String EXTRA_URL = "com.kimirc.shell.extra.URL";
    public static final String EXTRA_ACCOUNT = "com.kimirc.shell.extra.ACCOUNT";
    public static final String EXTRA_ACCOUNT_ID = "com.kimirc.shell.extra.ACCOUNT_ID";
    public static final String EXTRA_SLOT = "com.kimirc.shell.extra.SLOT";
    public static final String EXTRA_NOTIFY_TAG = "com.kimirc.shell.extra.NOTIFY_TAG";
    public static final String REMOTE_URL_PREFIX = "https://code-rc.kimi.com/";

    public static final int SLOT_COUNT = 4;

    private static final String TAG = "KimiRC";
    private static final int REQ_FILE_CHOOSER = 42;
    private static final int REQ_NOTIFY_PERMISSION = 43;
    private static final String ALLOWED_HOST = "kimi.com";
    private static final String DATA_DIRECTORY_PREFIX = "acct";
    private static final String NOTIFY_BRIDGE_NAME = "AndroidNotify";

    // 数据目录后缀是进程级的，每个进程只能定一次；槽位与进程一一对应，所以这里只会被赋值一次。
    private static int appliedSlot = -1;

    private static final int MENU_RELOAD = 1;
    private static final int MENU_LOGIN_STATE = 2;

    private FrameLayout container;
    private View webBar;
    private WebView webView;
    private WebView popupView;
    private LoginStateViewer loginStateViewer;
    private ValueCallback<Uri[]> pendingFileCallback;
    private String accountId;
    private String nicknameProbe;
    private NotificationBridge notifyBridge;
    private String notifyShim;
    private String accountName;
    private String deviceName;
    private String remoteUrl;
    // 通知权限只能在应用前台弹窗，页面却在后台才请求；这些状态用来把两边对齐。
    private final ArrayList<String> pendingNotifyPermissions = new ArrayList<String>();
    private boolean notifyPermissionInFlight;
    private String pendingNotifyClick;

    protected abstract int slotIndex();

    public static Class<?> activityForSlot(int slot) {
        switch (slot) {
            case 0:
                return WebActivity0.class;
            case 1:
                return WebActivity1.class;
            case 2:
                return WebActivity2.class;
            case 3:
                return WebActivity3.class;
            default:
                return null;
        }
    }

    public static boolean start(Activity from, int slot, String accountId, String accountName,
            String deviceName, String url) {
        Class<?> target = activityForSlot(slot);
        if (target == null) {
            return false;
        }
        Intent intent = new Intent(from, target);
        intent.putExtra(EXTRA_SLOT, slot);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_ACCOUNT, accountName);
        intent.putExtra(EXTRA_NAME, deviceName);
        intent.putExtra(EXTRA_URL, url);
        from.startActivity(intent);
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int slot = slotIndex();
        if (getIntent().getIntExtra(EXTRA_SLOT, -1) != slot) {
            finish();
            return;
        }
        applyDataDirectory(slot);

        remoteUrl = getIntent().getStringExtra(EXTRA_URL);
        if (remoteUrl == null || !remoteUrl.startsWith(REMOTE_URL_PREFIX)) {
            finish();
            return;
        }
        deviceName = getIntent().getStringExtra(EXTRA_NAME);
        accountName = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (accountName == null) {
            finish();
            return;
        }
        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);

        setContentView(R.layout.activity_web);
        container = findViewById(R.id.web_container);
        webBar = findViewById(R.id.web_bar);
        applyWebBarPolicy();
        TextView title = findViewById(R.id.web_title);
        title.setText(accountName);
        TextView subtitle = findViewById(R.id.web_subtitle);
        if (deviceName != null && !deviceName.isEmpty()) {
            subtitle.setText(deviceName);
        } else {
            subtitle.setVisibility(View.GONE);
        }

        findViewById(R.id.web_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        findViewById(R.id.web_reload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView != null) {
                    webView.reload();
                }
            }
        });
        findViewById(R.id.web_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowMenu(accountName);
            }
        });

        webView = new WebView(this);
        applySettings(webView);
        webView.setWebViewClient(new ShellWebViewClient());
        webView.setWebChromeClient(new ShellWebChromeClient());
        // 桥对象在文档创建时就挂上 window，早于页面任何脚本；注入的 shim 只是用它来定义 window.Notification。
        notifyBridge = new NotificationBridge(this);
        webView.addJavascriptInterface(notifyBridge, NOTIFY_BRIDGE_NAME);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (AccountStore.hasWipePending(this, slot)) {
            wipeSlotStorage(slot);
        }
        applyPendingCacheClear(slot);
        webView.loadUrl(remoteUrl);
    }

    private void showOverflowMenu(final String accountName) {
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.web_more));
        menu.getMenu().add(0, MENU_RELOAD, 0, R.string.web_reload);
        menu.getMenu().add(0, MENU_LOGIN_STATE, 1, R.string.login_state_title);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == MENU_RELOAD) {
                    if (webView != null) {
                        webView.reload();
                    }
                    return true;
                }
                if (item.getItemId() == MENU_LOGIN_STATE) {
                    if (loginStateViewer != null) {
                        loginStateViewer.dismiss();
                    }
                    loginStateViewer = LoginStateViewer.show(WebActivity.this, accountName);
                    return true;
                }
                return false;
            }
        });
        menu.show();
    }

    // 标题栏的显隐由设置页决定，跨进程读文件；隐藏后网页占满整屏，返回手势仍可退出。
    private void applyWebBarPolicy() {
        webBar.setVisibility(WebBarStore.hidden(this) ? View.GONE : View.VISIBLE);
    }

    private static synchronized void applyDataDirectory(int slot) {
        if (appliedSlot == slot) {
            return;
        }
        if (appliedSlot != -1) {
            throw new IllegalStateException(
                    "data directory already set for slot " + appliedSlot);
        }
        WebView.setDataDirectorySuffix(DATA_DIRECTORY_PREFIX + slot);
        appliedSlot = slot;
    }

    // 设置页只能落标记，真正清缓存得由持有这个 profile 的进程来做（见 WebCacheStore）。
    private void applyPendingCacheClear(int slot) {
        if (webView == null || !WebCacheStore.hasClearPending(this, slot)) {
            return;
        }
        // 只清网页缓存，不动 cookie 与站点存储，登录状态因此不受影响。
        webView.clearCache(true);
        WebCacheStore.clearPending(this, slot);
        Log.i(TAG, "slot " + slot + " web cache cleared");
    }

    private void wipeSlotStorage(int slot) {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        WebViewDatabase.getInstance(this).clearFormData();
        webView.clearCache(true);
        webView.clearHistory();
        AccountStore.clearWipePending(this, slot);
        Log.i(TAG, "slot " + slot + " storage wiped");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applySettings(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
    }

    // 用户在设置页改过开关或点过「清理缓存」后，回到远控页要跟着变；账号进程也顺带读到主进程刚写下的新值。
    @Override
    protected void onResume() {
        super.onResume();
        // onCreate 里参数不合法会直接 finish，那种情况下没有标题栏和 WebView 可改。
        if (webBar != null) {
            applyWebBarPolicy();
            applyPendingCacheClear(slotIndex());
        }
        // Activity 不在前台时系统不保证回调权限结果，回到前台就把「请求进行中」复位，别让它卡住后续请求。
        notifyPermissionInFlight = false;
        askNotificationPermission();
        // 点通知进来时应用可能还没回到前台，等回到前台再把它交给页面。
        flushPendingNotifyClick();
    }

    // 通知点击时这个账号的远控页还活着：系统把 Intent 投回同一实例，由这里转交给页面。
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            pendingNotifyClick = intent.getStringExtra(EXTRA_NOTIFY_TAG);
            flushPendingNotifyClick();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFY_PERMISSION) {
            return;
        }
        notifyPermissionInFlight = false;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, granted ? "notify permission granted" : "notify permission denied");
        if (!granted) {
            NotifyCenter.rememberDenied();
        }
        for (int i = 0; i < pendingNotifyPermissions.size(); i++) {
            replyNotifyPermission(pendingNotifyPermissions.get(i), granted ? "granted" : "denied");
        }
        pendingNotifyPermissions.clear();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (loginStateViewer != null) {
            loginStateViewer.dismiss();
            loginStateViewer = null;
        }
    }

    @Override
    protected void onDestroy() {
        dismissPopup();
        destroyWebView();
        super.onDestroy();
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.destroy();
        webView = null;
    }

    // 网页侧 Notification.requestPermission() 走到这里：结果通过 __kimircNotifyPermission 回灌给页面的 Promise。
    void requestNotifyPermission(String callbackId) {
        if ("granted".equals(NotifyCenter.permissionState(this))) {
            replyNotifyPermission(callbackId, "granted");
            return;
        }
        if (callbackId != null) {
            pendingNotifyPermissions.add(callbackId);
        }
        if (notifyPermissionInFlight) {
            return;
        }
        if (isFinishing() || isDestroyed()) {
            replyNotifyPermission(callbackId, "denied");
            return;
        }
        notifyPermissionInFlight = true;
        Log.i(TAG, "page requested notify permission");
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_NOTIFY_PERMISSION);
    }

    // 页面在后台请求权限时系统不会弹窗，所以进入远控页先主动问一次；问过就不再打扰（见 NotifyCenter）。
    private void askNotificationPermission() {
        if (webView == null || notifyPermissionInFlight || !NotifyCenter.shouldAsk(this)) {
            return;
        }
        notifyPermissionInFlight = true;
        NotifyCenter.markAsked(this);
        // 先把通知渠道建出来，用户在系统设置里能立刻看到「远控提醒」这一项。
        NotifyCenter.ensureChannels(this);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_NOTIFY_PERMISSION);
    }

    private void replyNotifyPermission(String callbackId, String state) {
        if (webView == null || callbackId == null) {
            return;
        }
        // callbackId 来自页面，只能当数据拼进去。
        webView.evaluateJavascript("window.__kimircNotifyPermission("
                + JSONObject.quote(callbackId) + "," + JSONObject.quote(state) + ")", null);
    }

    // 页面自己的 onclick 会切到对应会话，所以这里只负责把点击事件送回去。
    private void flushPendingNotifyClick() {
        if (pendingNotifyClick == null || webView == null) {
            return;
        }
        String tag = pendingNotifyClick;
        pendingNotifyClick = null;
        Log.i(TAG, "notify click delivered in slot " + slotIndex());
        webView.evaluateJavascript("window.__kimircNotifyClick(" + JSONObject.quote(tag) + ")", null);
    }

    // 通知点击要能把被系统回收后的远控页重新拉起来，所以进入本页所需的参数原样带回。
    Intent notificationIntent(String tag) {
        Intent intent = new Intent(this, getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_SLOT, slotIndex());
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_ACCOUNT, accountName);
        intent.putExtra(EXTRA_NAME, deviceName);
        intent.putExtra(EXTRA_URL, remoteUrl);
        intent.putExtra(EXTRA_NOTIFY_TAG, tag);
        return intent;
    }

    // WebView 渲染进程崩溃时如果不在客户端接管，系统会直接杀掉整个应用进程。
    private void onRendererGone() {
        Log.w(TAG, "renderer process gone in slot " + slotIndex());
        Toast.makeText(this, R.string.render_gone, Toast.LENGTH_LONG).show();
        dismissPopup();
        destroyWebView();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (popupView != null) {
            dismissPopup();
            reloadMainIfRemote();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && pendingFileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
        }
    }

    private static boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return host != null
                && (host.equals(ALLOWED_HOST) || host.endsWith("." + ALLOWED_HOST));
    }

    private void openExternally(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.external_error, Toast.LENGTH_LONG).show();
        }
    }

    private void dismissPopup() {
        if (popupView != null) {
            container.removeView(popupView);
            popupView.destroy();
            popupView = null;
            Log.i(TAG, "popup dismissed");
        }
    }

    private void showPopup(WebView popup) {
        popupView = popup;
        container.addView(popup, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void captureNickname(WebView view, String url) {
        if (accountId == null || url == null || !url.startsWith(REMOTE_URL_PREFIX)) {
            return;
        }
        String script = probeScript();
        if (script == null) {
            return;
        }
        view.evaluateJavascript(script, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                applyNickname(value);
            }
        });
    }

    // evaluateJavascript 回传的是 JS 字面量，先按 JSON 字符串解一层再当对象读。
    private void applyNickname(String rawResult) {
        if (rawResult == null || rawResult.isEmpty() || "null".equals(rawResult)) {
            return;
        }
        try {
            Object decoded = new JSONTokener(rawResult).nextValue();
            if (!(decoded instanceof String)) {
                return;
            }
            JSONObject payload = new JSONObject((String) decoded);
            Log.d(TAG, "nickname probe: source=" + payload.optString("source")
                    + " title=" + payload.optString("title")
                    + " keys=" + payload.optJSONArray("keys"));
            String nickname = payload.optString("nickname").trim();
            if (nickname.isEmpty()) {
                return;
            }
            if (nickname.length() > 40) {
                nickname = nickname.substring(0, 40);
            }
            AccountStore.setNickname(this, accountId, nickname);
        } catch (JSONException e) {
            Log.d(TAG, "nickname probe returned unexpected payload");
        }
    }

    private String probeScript() {
        if (nicknameProbe == null) {
            nicknameProbe = readRawScript(R.raw.nickname_probe, "nickname probe");
        }
        return nicknameProbe;
    }

    // 远控网页用 Web Notifications API 发提醒，而 WebView 里没有这个 API（typeof Notification 是 "undefined"），
    // 页面自己的守卫会把整条发送路径跳过；这里补一份最小实现，把通知交回原生（见 res/raw/notification_shim.js）。
    private void injectNotifyShim(WebView view) {
        if (notifyShim == null) {
            notifyShim = readRawScript(R.raw.notification_shim, "notification shim");
        }
        if (notifyShim != null) {
            view.evaluateJavascript(notifyShim, null);
            // 排查通知问题时看这一行：没有它说明脚本没注入，有它而页面毫无反应则问题在网页侧。
            Log.i(TAG, "notify shim injected");
        }
    }

    private String readRawScript(int resId, String label) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = getResources().openRawResource(resId)) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            Log.w(TAG, label + " script unavailable", e);
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private class ShellWebViewClient extends WebViewClient {
        // shim 要抢在页面脚本之前落地：加载开始时注入一次，加载完成后再补一次（注入可能早于新文档建立）。
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            injectNotifyShim(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectNotifyShim(view);
            captureNickname(view, view.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        private boolean handleUri(Uri uri) {
            if (isAllowed(uri)) {
                return false;
            }
            Log.i(TAG, "main: external " + safeHost(uri));
            openExternally(uri);
            return true;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            Toast.makeText(WebActivity.this, R.string.ssl_error, Toast.LENGTH_LONG).show();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            onRendererGone();
            return true;
        }
    }

    private class ShellWebChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.choose_file)),
                        REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                android.os.Message resultMsg) {
            if (popupView != null) {
                return false;
            }
            Log.i(TAG, "popup open");
            WebView popup = new WebView(WebActivity.this);
            applySettings(popup);
            popup.setWebViewClient(new PopupWebViewClient());
            popup.setWebChromeClient(new PopupChromeClient());
            // 弹窗里可能也会跑远控页，同样给它挂桥，通知才会走同一条路。
            popup.addJavascriptInterface(notifyBridge, NOTIFY_BRIDGE_NAME);
            showPopup(popup);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            dismissPopup();
            reloadMainIfRemote();
        }
    }

    private class PopupWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            injectNotifyShim(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectNotifyShim(view);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handlePopupUri(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handlePopupUri(Uri.parse(url));
        }

        private boolean handlePopupUri(Uri uri) {
            if (!isAllowed(uri)) {
                Log.i(TAG, "popup: external " + safeHost(uri));
                openExternally(uri);
                return true;
            }
            return false;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            Toast.makeText(WebActivity.this, R.string.ssl_error, Toast.LENGTH_LONG).show();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            onRendererGone();
            return true;
        }
    }

    private class PopupChromeClient extends WebChromeClient {
        @Override
        public void onCloseWindow(WebView window) {
            dismissPopup();
            reloadMainIfRemote();
        }
    }

    private void reloadMainIfRemote() {
        if (webView == null) {
            return;
        }
        String url = webView.getUrl();
        if (url != null && url.startsWith(REMOTE_URL_PREFIX)) {
            Log.i(TAG, "popup closed, reloading main view");
            webView.reload();
        }
    }

    private static String safeHost(Uri uri) {
        String host = uri.getHost();
        return host != null ? host : String.valueOf(uri.getScheme());
    }
}
