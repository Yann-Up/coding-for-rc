# KimiRC

Kimi Code Remote Control 的**非官方**开源安卓壳应用（WebView Shell）。

在电脑终端运行 `kimi rc` 会生成 `code-rc.kimi.com` 远控链接，手机浏览器打开并用同一 Kimi 账号登录即可远程接管本会话。KimiRC 用原生 WebView 加载官方远控网页，并在网页之外叠加本地增强：设备书签、登录态持久化、启动级应用锁与外链隔离，提供一个干净、安全的移动端入口。

## 边界（不做什么）

- 不实现任何鉴权与远程协议逻辑——登录、会话同步、设备绑定全部由 Kimi 官方网页与服务完成，应用只负责加载网页、保住登录态、管理本地链接列表。
- 不逆向、不代理任何 Kimi 接口；不接触账号密码与 OAuth 流程，登录始终在 Kimi 官方页面内完成。
- 不上架应用商店，GitHub Releases 为唯一分发渠道。

## 功能

- 设备书签：本地列表管理远控链接（名称 + 链接），一键直达；添加时支持剪贴板自动粘贴或相机扫描二维码，结果自动回填；长按删除。
- 登录一次、长期保持：Cookie 持久化，切后台主动 flush 落盘，杀进程不丢登录态。
- Cookie 查看：用户可从主界面主动打开面板；仅此时通过 WebView 读取固定远控地址前缀当前的 Cookie 用于显示，不展示完整设备链接。Cookie 值默认掩码，须手动点击才显示。
- 启动级应用锁：生物识别 / 锁屏密码验证通过后主界面才可见；切到桌面或其它应用再回来需重新验证（应用内跳转不触发）；验证失败或取消直接退出。API 28+ 走 BiometricPrompt，API 26–27 走系统设备凭据确认。
- 域名白名单：壳内只允许 `kimi.com` 系域名且仅 HTTPS；其余链接（含网页内 `window.open`）一律交给系统浏览器。
- 传输安全：不允许任何 SSL 错误绕过；禁止明文 HTTP。
- 外部链接直达：系统内点击 `code-rc.kimi.com` 链接可弹窗选择用 KimiRC 打开。
- 文件选择器：WebView 文件上传桥接到系统文件选择器，支持多选。

## 安全设计

- **远控链接等价于整台电脑的控制权**，因此安全是首要设计约束。应用锁是防止"手机丢失 → 设备被接管"的核心防线，请务必同时设置系统锁屏。
- 账号密码与 OAuth 零接触：登录流程全部发生在 Kimi 官方页面内；Cookie 由系统 WebView 管理。用户主动打开 Cookie 面板时，应用会读取固定远控地址前缀当前的 Cookie 用于显示。
- Cookie 按会话凭证保护：面板值默认掩码，用户主动点击才显示；面板读取结果不另行落盘/存储，不记录，不通过日志、Intent 或 Bundle 传递，不提供复制、导出或清除 Cookie。刷新仅通过 `getCookie` 重新读取，不触发 flush；弹窗显式设置 `FLAG_SECURE`，关闭或切后台时清理内存条目与视图。
- `FLAG_SECURE` 防截屏与最近任务缩略图泄露；`allowBackup=false` 防书签被系统备份上云。
- 签名密钥不入库：正式 release 经 CI Secrets 注入；本地与 CI 日常构建使用独立的 debug 密钥。
- 唯一直接依赖 ZXing Core（Apache-2.0，纯 Java 二维码解码，见 NOTICE）随源码固定版本并提供 SHA-256 校验，不引入任何原生库或 GMS 服务。

## 构建

无 Gradle、无 Android Studio 依赖，仅需：

- JDK 8+（`javac` 在 PATH 中）
- Android SDK（含 `platforms;android-34` 与 `build-tools;34.0.0`），通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 指定，或直接装在默认目录
- python3（或 zip），用于打包

```bash
bash build.sh        # 默认 debug 签名，产物 dist/KimiRC-<版本>.apk
```

CI（GitHub Actions）在 push 与 PR 上自动出包并上传 artifact。

## 发布

打 `v*` 标签即触发 release 工作流：用 Secrets 中的正式密钥签名，创建 GitHub Release 并附 APK、文件 SHA-256 与证书指纹。仓库需配置以下 Secrets：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_B64` | `base64 -w0 release.keystore` 的输出（keystore 文件本身不入库） |
| `RELEASE_STORE_PASSWORD` | keystore 口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名（当前为 `kimirc`） |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

自行生成新密钥：`keytool -genkeypair -keystore release.keystore -alias kimirc -keyalg RSA -keysize 2048 -validity 10000`，注意更新上表与下方指纹。

## 版本校验

release 签名证书 SHA-256 指纹（可用 `apksigner verify --print-certs KimiRC-x.y.z.apk` 核对）：

```
412F096ABD3BA347954D1AA0C7649817C32899F29A7277777DCCA59EEFBB01D3
```

debug 构建使用另一把密钥，仅用于本地开发与 CI artifact，勿用于分发。请只从本仓库的 GitHub Releases 下载安装包，以防第三方渠道夹带篡改包。

## 技术栈

Java · 原生 View + WebView · Camera2 · ZXing Core（Apache-2.0）· aapt2 + javac + d8 + zipalign + apksigner 脚本化构建 · SharedPreferences + JSON · minSdk 26 / targetSdk 34

## 更新记录

- **v1.1.0**：外部链接直达（intent-filter）、文件选择器、相机扫码添加设备（Camera2 + ZXing）、应用锁覆盖 API 26–27、启动图与浅色状态栏适配、正式签名发布通道。
- **v1.0.0**：设备书签、WebView 壳、Cookie 持久化、域名白名单、生物识别应用锁、剪贴板自动填充。

## 免责声明

本项目为社区非营利性质，与 Moonshot AI 无任何隶属或授权关系，"Kimi" 名称与商标归其权利人所有。用户通过本应用访问的登录与远控服务均由 Kimi 官方提供，相关数据处理遵守 Kimi 官方隐私政策。代码以 MIT 协议开源，内置 ZXing Core 遵循 Apache-2.0。使用者自行承担使用风险。若官方提出异议，项目将立即下架并归档；官方移动端支持 Remote Control 后，本项目转为归档模式并指路。
