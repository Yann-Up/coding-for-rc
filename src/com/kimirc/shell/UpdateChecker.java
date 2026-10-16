package com.kimirc.shell;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// 更新检查：只读元数据，不碰安装包。
//
// 两个源各有各的形态，解析成同一个 Info 后上层不再区分：
//   CNB    我们自己发的 latest.json（固定 raw 链接，国内可达），下载地址指向 CNB 附件直链；
//   GitHub releases/latest 接口（海外直连时最权威），正文里带文件 SHA-256 与证书指纹。
// 任一源失败都换下一个源重试，全部失败才向上报错。
//
// 这里刻意不引入任何依赖：HttpURLConnection + org.json 都在 android.jar 里。
final class UpdateChecker {

    static final String REPO = "Yann-Up/coding-for-rc";

    // CNB 的原始文件通道。注意 /-/raw/ 与 /-/blob/ 都是网页路由、拿不到文件内容，
    // 只有 /-/git/raw/ 会直接吐字节，这个形式是实测确认过的。
    static final String CNB_MANIFEST = "https://cnb.cool/" + REPO + "/-/git/raw/updater/latest.json";
    // 备用形态：同一个清单另发一份在 CNB 固定 tag 的 Release 附件上。目前 CI 还没有发这一份，
    // 失败会很快返回，留着它是为了 raw 路由将来变动时不必再发一个 App 版本。
    static final String CNB_MANIFEST_MIRROR = "https://cnb.cool/" + REPO + "/-/releases/download/latest/latest.json";
    static final String GITHUB_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    static final String GITHUB_RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    static final String CNB_RELEASES_PAGE = "https://cnb.cool/" + REPO + "/-/releases";

    static final int SOURCE_CNB = 0;
    static final int SOURCE_GITHUB = 1;

    // 失败原因。面向用户的文字全部在 strings.xml 里，这里只传代号。
    static final int OK = 0;
    static final int ERR_NETWORK = 1;
    static final int ERR_RATE_LIMIT = 2;
    static final int ERR_NOT_FOUND = 3;
    static final int ERR_PARSE = 4;

    // 原生请求不受 WebView 那套白名单约束，所以这里单独维护一份：只走这几个域名，
    // 跳转每一跳都重新校验，避免被重定向带到白名单之外。
    private static final String[] ALLOWED_HOSTS = {
            "cnb.cool",
            "api.github.com",
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
    };

    private static final int MAX_REDIRECTS = 4;

    private UpdateChecker() {
    }

    static final class Failure extends Exception {
        final int code;

        Failure(int code) {
            super("update check failure " + code);
            this.code = code;
        }
    }

    // 远端某个版本的元数据。apkUrl 指向该源自己的下载地址；另一个源的地址由版本号推出来。
    static final class Info {
        int source;
        String versionName = "";
        int versionCode;
        String notes = "";
        String pageUrl = "";
        String apkUrl = "";
        String apkName = "";
        long size;
        String sha256 = "";
        String certSha256 = "";

        String tag() {
            return versionName.isEmpty() ? "" : "v" + versionName;
        }

        // 下载时按顺序试的地址：当前源的直链，然后是另一个源的地址。
        String[] downloadUrls() {
            String primary = apkUrl;
            String secondary = source == SOURCE_CNB
                    ? githubApkUrl(versionName)
                    : cnbApkUrl(versionName);
            if (primary == null || primary.isEmpty()) {
                return new String[]{secondary};
            }
            return new String[]{primary, secondary};
        }
    }

    interface Callback {
        void onResult(Info info, int error);
    }

    // 阻塞式取元数据，调用方负责放到工作线程上。失败抛 Failure，由调用方决定换不换源。
    static Info fetch(Context context, int source, int timeoutMs) throws Failure {
        String body;
        if (source == SOURCE_CNB) {
            try {
                body = readText(open(CNB_MANIFEST, context, timeoutMs));
            } catch (Failure primary) {
                // raw 通道不可用时退到 Release 附件形态，两种都失败才认输。
                body = readText(open(CNB_MANIFEST_MIRROR, context, timeoutMs));
            }
            return parseCnbManifest(body);
        }
        body = readText(open(GITHUB_API, context, timeoutMs));
        return parseGithubRelease(body);
    }

    static String sourceName(Context context, int source) {
        return context.getString(source == SOURCE_CNB
                ? R.string.update_source_cnb
                : R.string.update_source_github);
    }

    // 两源都拿到时交叉比对：单个源被篡改的话，这里能发现。
    static boolean consistent(Info a, Info b) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.sha256.isEmpty() && !b.sha256.isEmpty() && !a.sha256.equals(b.sha256)) {
            return false;
        }
        if (!a.versionName.isEmpty() && !b.versionName.isEmpty()
                && !a.versionName.equals(b.versionName)) {
            return false;
        }
        return true;
    }

    // 是否比本机新。版本号（versionCode）权威，缺失时退回按版本名逐段比数值。
    // 两个解析器都要求版本名是「数字与点」的形状，远端那一侧因此总能解析出来；
    // 这条兜底分支现在通常只在 versionCode 缺失（如没有「版本代码」行的旧 Release）
    // 而本机版本名又不可解析时才生效，保留它只为不改变旧行为。
    // 两边都比不出来就当作没有更新：宁可漏报一次，也不要凭不可解析的版本名提示升级。
    static boolean isNewer(Context context, Info info) {
        if (info == null) {
            return false;
        }
        long localCode = installedVersionCode(context);
        if (info.versionCode > 0 && localCode > 0) {
            return info.versionCode > localCode;
        }
        int[] remote = parseVersion(info.versionName);
        int[] local = parseVersion(installedVersionName(context));
        if (remote == null || local == null) {
            return false;
        }
        return compareNumbers(remote, local) > 0;
    }

    static String installedVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static long installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }

    static String apkName(String versionName) {
        return "KimiRC-" + versionName + ".apk";
    }

    private static String cnbApkUrl(String versionName) {
        if (versionName.isEmpty()) {
            return "";
        }
        return "https://cnb.cool/" + REPO + "/-/releases/download/v" + versionName + "/"
                + apkName(versionName);
    }

    private static String githubApkUrl(String versionName) {
        if (versionName.isEmpty()) {
            return "";
        }
        return "https://github.com/" + REPO + "/releases/download/v" + versionName + "/"
                + apkName(versionName);
    }

    static boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    // 手动跟随跳转：每一跳都重新过白名单。CNB 的附件地址会跳到 asset.cnb.cool（腾讯云 CDN），
    // GitHub 的会跳到 release-assets.githubusercontent.com，两者都在上面的白名单里。
    // 不覆盖任何 TLS 校验，证书异常一律当作网络失败。
    // 元数据与安装包下载共用这里，调用方读完记得 disconnect。
    static HttpURLConnection open(String urlString, Context context, int timeoutMs)
            throws Failure {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new Failure(ERR_NOT_FOUND);
        }
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            if (!"https".equals(url.getProtocol()) || !isAllowedHost(url.getHost())) {
                throw new Failure(ERR_NOT_FOUND);
            }
            HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("User-Agent", userAgent(context));
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) {
                        throw new Failure(ERR_NOT_FOUND);
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code == 403) {
                    boolean throttled = "0".equals(conn.getHeaderField("X-RateLimit-Remaining"));
                    conn.disconnect();
                    throw new Failure(throttled ? ERR_RATE_LIMIT : ERR_NETWORK);
                }
                if (code == 404) {
                    conn.disconnect();
                    throw new Failure(ERR_NOT_FOUND);
                }
                if (code != 200) {
                    conn.disconnect();
                    throw new Failure(ERR_NETWORK);
                }
                return conn;
            } catch (IOException e) {
                throw new Failure(ERR_NETWORK);
            }
        }
        throw new Failure(ERR_NETWORK);
    }

    private static String readText(HttpURLConnection conn) throws Failure {
        try {
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    // 元数据本身只有几 KB（512 KiB 是个很宽的上限）：挡住「失控或被顶替的源把整个响应
                    // 灌进内存」，宁可当作解析失败换下一个源。
                    if (buffer.size() + read > 512 * 1024) {
                        throw new Failure(ERR_PARSE);
                    }
                    buffer.write(chunk, 0, read);
                }
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new Failure(ERR_NETWORK);
        } finally {
            conn.disconnect();
        }
    }

    private static String userAgent(Context context) {
        String version = installedVersionName(context);
        return "KimiRC/" + (version.isEmpty() ? "unknown" : version)
                + " (Android " + Build.VERSION.RELEASE + ")";
    }

    // CNB 侧是我们自己发的清单，字段固定，缺 version 就当解析失败。
    private static Info parseCnbManifest(String body) throws Failure {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            throw new Failure(ERR_PARSE);
        }
        Info info = new Info();
        info.source = SOURCE_CNB;
        info.versionName = text(root, "version");
        info.versionCode = root.optInt("versionCode", 0);
        info.notes = notesText(text(root, "notes"));
        // 发布页只有固定两个，用本类常量拼，不采信清单里的页面地址：
        // 那个值会被「打开发布页面」按钮使用，若被篡改可以指向白名单内的钓鱼地址。
        info.pageUrl = CNB_RELEASES_PAGE;
        JSONObject android = root.optJSONObject("android");
        if (android != null) {
            info.apkUrl = text(android, "url");
            info.size = android.optLong("size", 0L);
            info.sha256 = normalizeHex(text(android, "sha256"));
            info.certSha256 = normalizeHex(text(android, "certSha256"));
        }
        if (!isValidVersionName(info.versionName)) {
            throw new Failure(ERR_PARSE);
        }
        if (info.apkUrl.isEmpty()) {
            info.apkUrl = cnbApkUrl(info.versionName);
        }
        info.apkName = apkName(info.versionName);
        return info;
    }

    // GitHub 侧只有接口给的结构，更新摘要、SHA-256、证书指纹都要从 Release 正文里抠。
    private static Info parseGithubRelease(String body) throws Failure {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            throw new Failure(ERR_PARSE);
        }
        String tag = text(root, "tag_name");
        String versionName = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!isValidVersionName(versionName)) {
            throw new Failure(ERR_PARSE);
        }
        String notes = text(root, "body");
        Info info = new Info();
        info.source = SOURCE_GITHUB;
        info.versionName = versionName;
        // release.yml 的正文里带一行「- 版本代码: <数字>」，读不到就保持 0、退回按版本名比较。
        info.versionCode = versionCodeLine(notes);
        info.notes = notesText(notes);
        info.pageUrl = GITHUB_RELEASES_PAGE + "/tag/" + tag;
        info.apkName = apkName(versionName);
        info.sha256 = normalizeHex(lineSha256(notes, "文件 SHA-256"));
        info.certSha256 = normalizeHex(lineSha256(notes, "certificate SHA-256 digest"));

        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = text(asset, "name");
                if (!name.endsWith(".apk")) {
                    continue;
                }
                info.apkName = name;
                info.size = asset.optLong("size", 0L);
                info.apkUrl = text(asset, "browser_download_url");
                String digest = normalizeHex(text(asset, "digest"));
                if (!digest.isEmpty()) {
                    info.sha256 = digest;
                }
                break;
            }
        }
        if (info.apkUrl.isEmpty()) {
            info.apkUrl = githubApkUrl(versionName);
        }
        return info;
    }

    // Release 正文里那条更新摘要（"- **vX.Y.Z**：…"），取不到就留空，界面少显示一段而已。
    private static String changelogLine(String body) {
        if (body == null) {
            return "";
        }
        String[] lines = body.replace("\r", "").split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- **v")) {
                return trimmed.substring(2).replace("**", "").trim();
            }
        }
        return "";
    }

    // 两个来源的更新摘要统一走这里：优先用 changelogLine 清理过的（去掉 "- " 与 "**"），
    // 清理不到就回退到原文（去掉首尾空白），保证 CNB 与 GitHub 两条路径的展示一致。
    private static String notesText(String body) {
        if (body == null) {
            return "";
        }
        String cleaned = changelogLine(body);
        return cleaned.isEmpty() ? body.trim() : cleaned;
    }

    // release.yml 在 Release 正文里写的一行「- 版本代码: <数字>」。
    // 读不到、或那一行没有数字，都返回 0（不因此判为解析失败），上层会退回按版本名比较。
    private static int versionCodeLine(String body) {
        if (body == null) {
            return 0;
        }
        String[] lines = body.replace("\r", "").split("\n");
        for (String line : lines) {
            if (!line.contains("版本代码")) {
                continue;
            }
            String digits = firstDigits(line);
            if (digits.isEmpty()) {
                continue;
            }
            try {
                long code = Long.parseLong(digits);
                if (code > 0 && code <= Integer.MAX_VALUE) {
                    return (int) code;
                }
            } catch (NumberFormatException e) {
                // 位数过多溢出：当作这一行没有版本代码，继续往下找。
            }
        }
        return 0;
    }

    // 取第一段连续数字，找不到返回空串。
    private static String firstDigits(String text) {
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                current.append(c);
            } else if (current.length() > 0) {
                break;
            }
        }
        return current.toString();
    }

    // 版本名会被拿去拼下载地址（apkName / cnbApkUrl / githubApkUrl），只接受「数字与点」的形状
    // （如 0.1.6）。主机名本来受白名单约束、最终还有哈希与签名兜底，但没必要让带路径片段的
    // 输入进入 URL 拼接。
    private static boolean isValidVersionName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    // 正文里有两处 SHA-256（文件与签名证书），必须认准 marker 那一行再取值。
    private static String lineSha256(String body, String marker) {
        if (body == null) {
            return "";
        }
        String[] lines = body.replace("\r", "").split("\n");
        for (String line : lines) {
            if (!line.contains(marker)) {
                continue;
            }
            String hex = firstHex64(line);
            if (!hex.isEmpty()) {
                return hex;
            }
        }
        return "";
    }

    private static String firstHex64(String text) {
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (hex) {
                current.append(c);
                if (current.length() == 64) {
                    return current.toString();
                }
            } else {
                current.setLength(0);
            }
        }
        return "";
    }

    private static String normalizeHex(String value) {
        String hex = value == null ? "" : value.trim().toLowerCase();
        if (hex.startsWith("sha256:")) {
            hex = hex.substring("sha256:".length());
        }
        if (hex.length() != 64) {
            return "";
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return "";
            }
        }
        return hex;
    }

    // org.json 对 JSON null 会给出字符串 "null"，这里统一收敛成空串。
    private static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        if (value == null || "null".equals(value)) {
            return "";
        }
        return value.trim();
    }

    private static int[] parseVersion(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String[] parts = name.trim().split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return numbers.length == 0 ? null : numbers;
    }

    private static int compareNumbers(int[] a, int[] b) {
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) {
                return left < right ? -1 : 1;
            }
        }
        return 0;
    }
}
