package com.kimirc.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class WebActivity extends Activity {

    public static final String EXTRA_NAME = "com.kimirc.shell.extra.NAME";
    public static final String EXTRA_URL = "com.kimirc.shell.extra.URL";
    public static final String REMOTE_URL_PREFIX = "https://code-rc.kimi.com/";

    private static final String TAG = "KimiRC";
    private static final int REQ_FILE_CHOOSER = 42;
    private static final String ALLOWED_HOST = "kimi.com";
    private static final String REMOTE_HOST = "code-rc.kimi.com";

    private FrameLayout root;
    private WebView webView;
    private WebView popupView;
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        String name = getIntent().getStringExtra(EXTRA_NAME);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || !url.startsWith(REMOTE_URL_PREFIX)) {
            finish();
            return;
        }
        setTitle(name != null && !name.isEmpty() ? name : url);

        root = new FrameLayout(this);
        webView = new WebView(this);
        applySettings(webView);
        webView.setWebViewClient(new ShellWebViewClient());
        webView.setWebChromeClient(new ShellWebChromeClient());
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl(url);
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

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissPopup();
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
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
            root.removeView(popupView);
            popupView.destroy();
            popupView = null;
            Log.i(TAG, "popup dismissed");
        }
    }

    private void showPopup(WebView popup) {
        popupView = popup;
        root.addView(popup, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private class ShellWebViewClient extends WebViewClient {
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
