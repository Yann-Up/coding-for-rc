# AGENTS.md

KimiRC —— Kimi Code Remote Control 的**非官方**安卓 WebView 壳应用。原生 WebView 加载官方远控网页 `https://code-rc.kimi.com/`，壳外只做本地增强：多账号登录态隔离、设备书签、启动级应用锁、域名白名单。

## 构建

**没有 Gradle、没有 Android Studio 工程、没有任何依赖管理文件。** `build.sh` 是唯一构建入口，它是权威来源：

```bash
bash build.sh     # 产物 dist/KimiRC-<versionName>.apk（默认 debug 签名）
```

脚本自己跑完整链路：`aapt2 compile` → `aapt2 link`（顺便生成 `build/gen/…/R.java`）→ `javac` → `d8` → zip 打包 → `zipalign` → `apksigner`。`build/` 目录每次构建前被 `rm -rf` 清空，`build/`、`dist/` 均在 `.gitignore` 中，不要手工编辑或提交其中内容。

环境要求（脚本会自行校验，不满足直接报错退出）：

- JDK 21+，`javac` 在 PATH
- Android SDK：`ANDROID_HOME` / `ANDROID_SDK_ROOT`，或默认安装路径
- `platforms;android-34`、`build-tools;35.0.0`（**硬性下限 35.0.0**：34.0.0 的 d8 解析 JDK 21 生成的空名 `MethodParameters` 属性会 NPE；Windows 下脚本自动探测 `android-*` 与 build-tools 最高版本）
- `python3`/`python`，或 `zip`（打包用；`make-icons.py` 另需 Pillow）

### 验证

项目**没有单元测试、没有 lint 配置**，不要新建测试脚手架。改动的验证方式固定为两条：

1. `bash build.sh` 必须成功，且无 `javac` 报错（脚本用 `-nowarn`，编译警告不显示）。
2. 装到真机/模拟器上跑通实际路径——WebView 加载、多进程账号、应用锁这些行为都无法靠编译验证。

## 代码布局

```
build.sh                                   构建 + 版本号 + ZXing 校验（权威）
.cnb.yml                                   CNB tag_push 流水线：创建同名 Release，从 GitHub 拉 APK 挂为附件
AndroidManifest.xml                        组件、权限、进程声明、版本号；含 REQUEST_INSTALL_PACKAGES 与不导出的 InstallResultReceiver
src/com/kimirc/shell/                      全部 Java 源码，包名 com.kimirc.shell
  MainActivity.java                        主页：设备列表、启动图、应用锁、账号切换
  SettingsActivity.java                    设置页：截屏保护开关、外观与更新检查入口
  WebActivity.java                         抽象基类：WebView 壳、白名单、Cookie、弹窗、文件选择
  WebActivity0..3.java                     仅重写 slotIndex()，各绑定一个进程
  AddDeviceActivity.java / ScanActivity.java  添加设备：剪贴板/扫码
  LoginStateViewer.java                    「登录状态」面板（读 cookie 用于显示）
  ViewfinderView.java                      扫码取景框自绘
  AccountStore.java / DeviceStore.java     本地存储（账号、设备）
  PrivacyStore.java                        截屏保护开关，跨进程用文件存
  WebBarStore.java                         远控页顶部标题栏显隐开关，跨进程用文件存
  WebCacheStore.java                       「清理缓存」请求标记，按槽位落文件，由账号进程自己执行
  SecureAlertDialog.java                   弹窗窗口补 FLAG_SECURE 的 Builder
  ThemeStore.java / ThemedActivity.java    明暗模式覆盖，跨进程用文件存
  UpdateChecker.java                       读双源版本元数据、解析并规范化摘要、比对版本、两源交叉核对；维护请求白名单与手动跳转跟随
  UpdateStore.java                         自动检查开关、上次检查时间、被忽略版本（主进程 SharedPreferences）
  UpdateInstaller.java                     下载、校验 SHA-256 与签名，交给系统 PackageInstaller 安装
  InstallResultReceiver.java               接收系统回投的安装结果广播（不导出）
  UpdatePrompt.java                        检查/提示/进度/安装前后的弹窗与流程编排
res/                                       aapt2 直接编译整个目录，新增文件自动生效
  layout/ values/ values-night/ values-v27/ drawable*/ mipmap-*/ raw/nickname_probe.js
  layout/dialog_update.xml / dialog_download.xml  更新提示与下载进度弹窗
assets/logo.png                           图标与启动图源图
docs/screenshots/                         README 截图（主页 / 远控页 / 设置页，已压到 600px 宽并清掉 EXIF）
tools/make-icons.py                       由 assets/logo.png 派生各密度位图
tools/release-notes.sh                    取 README 更新记录里某个 tag 的摘要（release 工作流用）
libs/zxing-core-3.5.3.jar                 唯一依赖，SHA-256 在 build.sh 中固定校验
.github/workflows/build.yml               push/PR 出包
.github/workflows/release.yml             v* 标签走 Secrets 签名并发布 Release
.github/workflows/sync-cnb.yml            v* 标签触发，等 Release 附件就绪后实测 APK SHA-256，推送 tag 与 updater/latest.json 到 CNB
```

## 语言与风格

- **注释、`strings.xml` 文案、README 全部用中文**，保持一致；错误信息与日志（`Log.*`）用英文短句。
- Java 为 **source/target 8**，且 `-bootclasspath android.jar`：**只能用平台 API，minSdk 28 起步，没有 AndroidX / Support Library / GMS**。新增能力前先确认 `android.jar` 里有对应类。
- 不写 lambda、方法引用或 `var`——现有代码统一用匿名内部类（`new View.OnClickListener() {…}`），照此写。事件绑定用 `findViewById(...).setOnClickListener(...)`，本项目**没有** ViewBinding、DataBinding、Fragment、RecyclerView、Jetpack 任何组件。
- 缩进 4 空格；包名 `com.kimirc.shell`，`R` 无需 import；资源 ID 全部走 `findViewById` / `R.xxx`。
- 所有面向用户的文案进 `res/values/strings.xml`（含格式化串 `%1$s`），不要在 Java 里硬编码中文。
- 主题色/尺寸进 `res/values/colors.xml`、`dimens.xml`；夜间值进 `values-night/`。手动明暗模式靠 `ThemeStore.wrap()` 在 `attachBaseContext` 覆盖 `uiMode`，新 Activity 应继承 `ThemedActivity`。

## 多进程约定（易踩坑）

`WebActivity0..3` 各跑在 `:acct0..:acct3` 独立进程，主进程与账号进程之间**不共享内存**，因此：

- **跨进程状态一律用文件，不要用 SharedPreferences**：后者按进程缓存，另一方读不到刚写的值。参见 `AccountStore.nickname()`、`AccountStore.wipeFile()`、`PrivacyStore.allowScreenshots()`、`ThemeStore.mode()`、`WebBarStore.hidden()`，注释里已写明原因。`SharedPreferences` 只用于主进程自己读写的账号/设备列表与 `UpdateStore`。`UpdateStore` 记录自动检查开关、上次检查时间、被忽略的版本号；它使用 SharedPreferences 是**有意的**——更新检查只发生在主进程（主页与设置页都在主进程），没有账号进程会读它，所以不需要像 `PrivacyStore` / `ThemeStore` 那样落文件。
- `WebView.setDataDirectorySuffix()` 是**进程级、只能调用一次**（`WebActivity.applyDataDirectory` 会重复调用即抛异常）。槽位与进程一一对应，所以每加一个账号槽必须先加一个 `WebActivity<N>` 子类 + manifest 中带 `android:process=":acct<N>"` 的 `<activity>`，并同步改 `SLOT_COUNT`、`activityForSlot()`。上限 4 个。
- 账号存储隔离依赖「独立进程 + 独立数据目录」，删除账号只释放槽位，槽位复用前靠 `wipe_slot_<N>` 标记文件触发清空。
- 每个账号的 WebView profile（含网页缓存）只属于它自己的进程：主进程**不要**直接删这些目录下的文件，那会踩到可能正在运行的 WebView。需要清理时照 `wipe_slot_<N>` 的做法落标记，由账号进程执行——「清理缓存」就是这么做的：主进程写 `clear_cache_slot_<N>`（`WebCacheStore`），账号进程在 `onCreate` / `onResume` 里 `WebActivity.applyPendingCacheClear` 清掉，只清缓存、不动 cookie 与站点存储。

## 安全红线

远控链接等价于整台电脑的控制权，以下约束是设计前提，**不要为了方便而放宽**：

- 域名白名单：仅 HTTPS 且 host 为 `kimi.com` 或其子域（`WebActivity.isAllowed`）；其余一律交给系统浏览器。
- 绝不允许绕过 SSL 错误（`onReceivedSslError` 固定 `handler.cancel()`）；`usesCleartextTraffic="false"` 不得改动。
- Cookie 只在用户主动打开「登录状态」面板时读取用于显示，默认掩码、不落盘、不进日志/Intent/Bundle；面板必须运行在账号自己的进程里。
- 截屏保护：`ThemedActivity.onCreate` 按 `PrivacyStore` 决定是否加 `FLAG_SECURE`（默认加，用户可在「设置」页关掉）。**不要在子类里自己加 `FLAG_SECURE`**——加在了本地，用户关掉开关也清不掉。弹窗是独立窗口、Activity 的标志带不过去，所以设置类弹窗统一用 `SecureAlertDialog.Builder`（在 `create()` 里补标志）。`LoginStateViewer` 显示凭证，无论开关如何都强制加。`allowBackup="false"`（防书签被系统备份上云）不得改动。
- 应用锁是「手机丢失 → 设备被接管」的核心防线，切换账号需二次验证。改动 `MainActivity` 的解锁状态机（`unlocked` / `onUserLeaveHint` / `internalNavigation`）时格外小心。
- 更新校验：签名证书是信任锚，SHA-256 只负责传输完整性。更新源的域名白名单不得放宽，只允许这些主机**及其子域**：`cnb.cool`、`api.github.com`、`github.com`、`release-assets.githubusercontent.com`、`objects.githubusercontent.com`（`UpdateChecker.isAllowedHost` 对每个白名单条目都放行 `host.equals(条目)` 与 `host.endsWith("." + 条目)`，没有精确匹配例外）；跳转每一跳都要重新校验，不得覆盖任何 TLS 校验。SHA-256 或签名校验失败一律不安装，不得增加「跳过校验」开关；`CNB_TOKEN` 只给 CI，不得进客户端；不得为更新流程引入任何依赖，只用 `android.jar` 里的 `HttpURLConnection` + `org.json` + `MessageDigest`。
- 密码学材料不入库：`release.keystore`、`release-secrets.txt`、`release-keystore.b64` 已在 `.gitignore`，不要提交、不要打印其内容；日常与 CI 构建用固定路径的 `~/.android/debug.keystore`。

## 版本号与发布

版本号在**两处**必须同步（`build.sh` 无自动读取 manifest 的逻辑）：

- `build.sh` 顶部 `VERSION_NAME` / `VERSION_CODE`
- `AndroidManifest.xml` 的 `android:versionName` / `android:versionCode`

`VERSION_CODE` 同时被 `aapt2 link --version-code` 覆盖传入，改漏一处会出现版本不一致的包。产物名 `dist/KimiRC-<version>.apk` 也随版本走。

**发版顺序**：先把版本号两处改好、在 README「更新记录」顶部补上 `- **vX.Y.Z**：...` 条目、推上 `master`，再打 `v*` 标签推送。release 工作流第一步跑 `tools/release-notes.sh <tag>`，它要求该条目存在、且标签与 `build.sh` 的 `VERSION_NAME` 一致，不满足就在构建前失败——宁可失败，也不要把版本号对不上的包、或没有说明的 Release 发出去。正式签名靠四个 GitHub Secrets 注入（`RELEASE_KEYSTORE_B64` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`），工作流会校验 base64 长度与 keystore 可读性。Release 正文 = 免责声明 + 该条目 + APK 的 SHA-256 与证书指纹；README 中记录的证书 SHA-256 指纹用于核对，换密钥时必须更新。

Release 发布后进入 CNB 同步：`.github/workflows/sync-cnb.yml` 由 `push` 的 `v*` 标签（或手动指定 tag）触发，**不用** `release: published`——`release.yml` 用 Actions 自带的 `GITHUB_TOKEN` 创建 Release，而 GitHub 规定由 `GITHUB_TOKEN` 触发的事件不会再触发新的 workflow 运行，那条触发对自动流程从未生效过（v0.1.6 就漏过一次）。因为改成 tag push，本工作流与 `release.yml` 同时起跑，所以下载 APK 前会带时限地等待该 Release 的 APK 附件出现（超时即失败，不带着拿不到的包继续）。之后下载本 Release 的 APK 对字节实测 SHA-256（不从正文抓字符串），生成 CNB 版清单 `latest.json`，把该 tag 推到 CNB 仓，并将清单提交到只含 `latest.json` 的 `updater` 分支。被 tag 的提交没有 `.cnb.yml` 时，工作流直接失败，避免发出指向死链的清单。除了 `release.yml` 使用的四个签名 Secrets，同步还需要 GitHub Secret `CNB_TOKEN`（CNB 访问令牌，含 `repo-contents` 读写，Git 推送用户名固定 `cnb`），只供 CI 使用，不得进入客户端。推 tag 之后、提交清单之前，工作流会先实测清单要指向的那条 CNB 附件直链（下载整包比对 SHA-256，最多等 8 分钟），到时限仍拿不到或对不上只发 `::warning::` 而**不失败**——客户端拿不到 CNB 会回退 GitHub，在这里硬失败反而会让 CNB 侧连清单都没有。写入 `updater` 分支时清单**只允许升不允许降**：分支上已有清单的 `versionCode` 大于本次版本就直接报错退出，避免用手动补同步旧 tag 把清单回滚到更旧的版本（现有清单缺失或读不出 `versionCode` 时跳过这道守卫）。

仓库根目录的 `.cnb.yml` 是 CNB 侧流水线：`tag_push` 触发后用内置任务 `git:release` 创建同名 Release，再把 APK 从 GitHub Release 拉到 CNB 挂成 Release 附件。

注意**标签事件用的是被 tag 的那个提交里的工作流与脚本**：改 `release.yml`、`tools/release-notes.sh`、`sync-cnb.yml` 或 CNB 侧的 `.cnb.yml` 本身，都要先合进 `master` 再打标签才会生效；`sync-cnb.yml` 已从 `release: published` 改成 tag push 触发，因此和 `.cnb.yml` 是同一条纪律——对已推过的旧 tag 重跑，用的仍是当时那份配置。

已知的待验证项与风险：

- CNB 构建节点出网访问 github.com 已经实测可用：v0.1.6 首次同步时 APK 成功从 GitHub 拉到 CNB 并挂成附件。
- CNB 仓当前含有 `v0.1.6` 的 Release（附件里有 `KimiRC-0.1.6.apk`）、该 tag，以及只放 `latest.json` 的 `updater` 分支；CNB 把 `HEAD` 指向了 `updater`，因此该仓没有源码分支（`sync-cnb.yml` 里重置该分支用的是 `git checkout -B`，就是因为克隆时本地已经存在同名分支）。
- `.cnb.yml` 的 `git:release` 设了 `overlying: true`（叠加模式，重跑时不先删旧 Release）。官方文档只把它描述为「仅编辑/更新」，而任务本身的定位是「生成或更新」、且那句「先删后建」的警告限定在「Release 已存在时」，所以「Release 尚不存在时 true 是否仍会创建」没有明文——需要在下一次打 tag 时确认首个 Release 能否正常建出；万一没建出来，删掉那一行即可恢复。
- 客户端在 CNB raw 通道失效时会尝试第二种 CNB 形态（固定 tag 的 Release 附件），但 CI 目前还没有发布那一份；这只是 raw 通道万一变动时的兜底预留。

### 图标

位图全部由 `assets/logo.png` 派生，换图后重新生成（需 Pillow）：

```bash
python tools/make-icons.py     # 输出 res/mipmap-*/ic_launcher_foreground.png 与 res/drawable-*/logo.png
```

不要手工编辑这些 PNG；背景层 `res/drawable/ic_launcher_background.xml` 是纯白。

## 第三方

只允许 ZXing Core（Apache-2.0，纯 Java 二维码解码）。`build.sh` 固定版本号 + 下载地址 + SHA-256，校验失败即退出。新增依赖需同时更新 `build.sh` 的校验与 `NOTICE`；**不要引入任何原生库或 GMS 服务**（README 已就此作出承诺）。
