# KimiRC

<img src="assets/logo.png" width="96" alt="KimiRC" align="right">

**Kimi Code Remote Control 的非官方开源安卓外壳应用（WebView Shell）。**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/Yann-Up/coding-for-rc?sort=semver)](https://github.com/Yann-Up/coding-for-rc/releases/latest)
[![Build](https://github.com/Yann-Up/coding-for-rc/actions/workflows/build.yml/badge.svg)](https://github.com/Yann-Up/coding-for-rc/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2028)-3DDC84?logo=android&logoColor=white)](#技术栈)

在电脑终端运行 `kimi rc` 会生成一个 `code-rc.kimi.com` 远控链接，手机浏览器打开并用同一 Kimi 账号登录，即可远程接管这条会话。KimiRC 用原生 WebView 加载官方远控网页，只在网页之外叠加本地增强——设备书签、多账号登录态隔离、启动级应用锁、域名白名单——提供一个干净、安全的移动端入口。

一句话：**让「手机控制电脑上的 Kimi Code」这件事，在手机上有该有的样子。**

- 网页里的能力、鉴权与协议，一律来自 Kimi 官方；本应用只负责加载、隔离与保护。
- 无 Gradle、无 AndroidX、无 GMS、无原生库，唯一第三方依赖是 ZXing Core。
- 代码以 MIT 协议开源。

## 目录

- [下载与安装](#下载与安装)
- [快速开始](#快速开始)
- [截图](#截图)
- [功能](#功能)
- [边界：不做什么](#边界不做什么)
- [安全设计](#安全设计)
- [构建](#构建)
- [发布](#发布)
- [版本校验](#版本校验)
- [技术栈](#技术栈)
- [许可与致谢](#许可与致谢)
- [更新记录](#更新记录)
- [免责声明](#免责声明)

## 下载与安装

| 渠道 | 地址 | 说明 |
| --- | --- | --- |
| GitHub Releases | <https://github.com/Yann-Up/coding-for-rc/releases/latest> | 主要分发渠道 |
| CNB | <https://cnb.cool/Yann-Up/coding-for-rc/-/releases> | 国内加速镜像 |

两处产物**同名、同 SHA-256、同一签名**，应用内更新也会在 CNB 与 GitHub 之间自动回退。请只从上述官方渠道下载安装包——第三方渠道有可能夹带篡改包。安装要求 **Android 8.0（API 28）及以上**。

## 快速开始

1. 在电脑上运行 `kimi rc`，得到远控链接。
2. 安装 KimiRC，打开后按提示通过生物识别 / 锁屏密码解锁。
3. 在主页「添加设备」中粘贴链接（支持剪贴板自动粘贴）或扫描二维码，保存为设备书签。
4. 点按设备书签进入远控页，在 Kimi 官方页面内用你的 Kimi 账号登录，之后登录态长期保持。
5. 需要多个账号时，从主页头部「账号」入口进入账号列表逐个登录，各账号的登录态互相隔离、可同时保持。

## 截图

| 主页 | 远控页 | 设置 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/01-home.jpg" width="240" alt="主页：远程设备列表"> | <img src="docs/screenshots/02-web.jpg" width="240" alt="远控页：Kimi Code 官方网页"> | <img src="docs/screenshots/03-settings.jpg" width="240" alt="设置页"> |
| 设备书签列表，每个账号只显示自己的设备；右上角是账号与设置入口 | 壳内加载 Kimi 官方远控网页，顶部标题栏可隐藏，⋮ 菜单里有「刷新」与「登录状态」 | 截屏保护、隐藏标题栏、应用锁状态、外观、版本与清理缓存 |

截图为作者的实机界面，其中的设备名与账号昵称仅作示例。

## 功能

- **多账号隔离**：每个账号对应一个独立的 WebView 进程与独立数据目录，cookie、localStorage、IndexedDB、缓存全部隔离，互不串号；最多 4 个账号。主页只显示当前账号的设备，切换账号即切换登录态，各账号可同时保持登录。
- **设备书签**：本地列表管理远控链接（名称 + 链接），一键直达；添加时支持剪贴板自动粘贴或相机扫描二维码，结果自动回填；长按删除。设备归属于账号。
- **登录一次、长期保持**：Cookie 持久化，切后台主动 flush 落盘，杀进程不丢登录态。
- **账号管理**：主页头部「账号」入口进入账号列表，点按切换、长按重命名或删除；该弹窗只管账号切换、重命名与删除，不承载任何设置项。在远控页顶栏 ⋮ 菜单打开「登录状态」面板，查看当前账号的登录状态；仅此时通过 WebView 读取固定远控地址前缀当前的 Cookie 用于显示，不展示完整设备链接。凭证值默认掩码，须手动点击才显示。
- **启动级应用锁**：生物识别 / 锁屏密码验证通过后主界面才可见；离开应用（回桌面或切到其它应用）再回来需重新验证，应用内 Activity 跳转不触发；进入应用时验证失败或取消直接退出；成功解锁后切换账号会再验证一次，取消只是不切换、不会退出。API 28+ 走 BiometricPrompt。
- **域名白名单**：壳内只允许 `kimi.com` 系域名且仅 HTTPS；其余链接（含网页内 `window.open`）一律交给系统浏览器。
- **传输安全**：不允许任何 SSL 错误绕过；禁止明文 HTTP。
- **外部链接直达**：系统内点击 `code-rc.kimi.com` 链接可弹窗选择用 KimiRC 打开。
- **文件选择器**：WebView 文件上传桥接到系统文件选择器，支持多选。
- **截屏保护开关**：默认开启，禁止截屏与最近任务预览；关闭后即可正常截图。开关位于主页头部「设置」入口打开的独立设置页内，关闭仅影响本地显示行为，不影响远控连接本身。
- **设置页**：主页头部「设置」入口进入独立设置页面，集中「截屏保护」开关、「隐藏远控页标题栏」开关（只作用于远控页的顶部标题栏，主页与设置页不受影响；隐藏后返回手势仍可用）、「外观」主题（跟随系统 / 月之亮面 / 月之暗面，切换后界面即时重建）、应用锁状态（系统未设锁屏时高亮提示）、版本信息（版本名 + 版本号）、「清理缓存」（清除各账号远控页的网页缓存，**不会**影响登录状态、站点数据与设备列表；缓存由各账号进程在下次打开远控页时清理）与「关于」（许可与免责声明）。
- **检查更新**：设置页「版本」行可点按手动检查，检查期间右侧显示「检查中…」；主页在应用锁解锁后静默自动检查一次，自动检查默认开启、24 小时最多一次，可在设置页关闭，只拉取元数据，绝不自动下载或安装。元数据优先读取 CNB，失败回退 GitHub；安装包先走本次元数据来源自己的直链（CNB 元数据即 CNB 附件直链，GitHub 元数据即 GitHub Release 附件），失败再回退另一个源。自动检查已是最新或失败时均完全静默，仅在发现未被「忽略此版本」的新版本时弹窗，可选择「下载并安装」、「稍后」或「忽略此版本」；安装前校验 SHA-256 与签名，安装完成后应用会重启，进行中的远控会话会断开。

## 边界：不做什么

明确划出边界，比堆功能更重要：

- 不实现任何鉴权与远程协议逻辑——登录、会话同步、设备绑定全部由 Kimi 官方网页与服务完成，应用只负责加载网页、保住登录态、管理本地链接列表。
- 不逆向、不代理任何 Kimi 接口；不接触账号密码与 OAuth 流程，登录始终在 Kimi 官方页面内完成。
- 多账号只是本地的登录态分区：应用不提供任何账号体系，也不解析账号数据，只负责把不同账号的网页存储隔离开。
- 不上架应用商店，官方分发渠道为 GitHub Releases 与 CNB（国内加速），两处产物同名、同 SHA-256、同一签名；请勿从其它渠道安装。

## 安全设计

**远控链接等价于整台电脑的控制权**，因此安全是首要设计约束。应用锁是防止「手机丢失 → 设备被接管」的核心防线，请务必同时设置系统锁屏。

- **多一条链接 = 多一台电脑的控制权。** 多账号会放大「一次解锁」能触达的范围，因此成功解锁后切换账号会再验证一次身份；验证取消则不切换，也不会退出应用。
- **账号间存储隔离**：每个账号一个独立进程 + 独立 WebView 数据目录（`WebView.setDataDirectorySuffix`），cookie 与站点存储不跨账号；删除账号只释放槽位，该槽位下次被占用前会清空其存储。
- **账号密码与 OAuth 零接触**：登录流程全部发生在 Kimi 官方页面内；Cookie 由系统 WebView 管理。用户主动打开「登录状态」面板时，应用会读取固定远控地址前缀当前的 Cookie 用于显示（面板必须运行在账号自己的进程里才能读到该账号的 cookie，因此入口放在远控页 ⋮ 菜单而非主页）。
- **Cookie 按会话凭证保护**：面板值默认掩码，用户主动点击才显示；面板读取结果不另行落盘/存储，不记录，不通过日志、Intent 或 Bundle 传递，不提供复制、导出或清除 Cookie。刷新仅通过 `getCookie` 重新读取，不触发 flush；弹窗显式设置 `FLAG_SECURE`，关闭或切后台时清理内存条目与视图。
- **`FLAG_SECURE`** 防截屏与最近任务缩略图泄露，默认在所有界面开启，用户可在「设置」页关闭（见「截屏保护开关」）；「登录状态」面板显示凭证，无论开关如何都强制开启。`allowBackup=false` 防书签被系统备份上云。
- **签名密钥不入库**：正式 release 经 CI Secrets 注入；本地与 CI 日常构建使用独立的 debug 密钥，该密钥固定存放于 `~/.android/debug.keystore`（已存在则直接复用），保证同一台机器上 debug 包签名稳定、可覆盖安装。
- **更新以签名证书为信任锚**：安装前比对下载包与当前已安装包的签名证书，不一致即拒绝安装，用于防止没有私钥者替换服务器上的安装包。SHA-256 只负责发现传输损坏；只能连接 CNB 时哈希也来自 CNB，不能单独防范 CNB 内容被篡改，篡改仍由签名校验拦截。两个源的产物同名、同 SHA-256、同一签名；手动检查会在**两个源都能取到元数据时**交叉核对版本号与摘要（SHA-256 只在两边都非空时比对），不一致就拒绝本次更新并提示手动核对；另一个源不可达或解析失败时这一步会被跳过。无论是否交叉核对，安装前都仍会校验下载包的 SHA-256 与签名。校验不通过一律不安装，只提供「打开 Release 页面」兜底，没有「跳过校验继续安装」的选项。`REQUEST_INSTALL_PACKAGES` 权限只用于安装从 GitHub Releases 或 CNB 下载、且 SHA-256 与签名都核对通过的包。
- **依赖面极小**：唯一直接依赖 ZXing Core（Apache-2.0，纯 Java 二维码解码，见 `NOTICE`）随源码固定版本并提供 SHA-256 校验，不引入任何原生库或 GMS 服务。

## 构建

无 Gradle、无 Android Studio 工程、无依赖管理文件，`build.sh` 是唯一构建入口。需要：

| 依赖 | 要求 |
| --- | --- |
| JDK | 21+（`javac` 须在 PATH 中；低于 21 直接报错退出） |
| Android SDK | 含 `platforms;android-34` 与 `build-tools;35.0.0`，通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 指定，或装在默认目录 |
| 打包工具 | `python3`（或 `zip`）；重新生成图标时另需 Pillow |

```bash
bash build.sh        # 默认 debug 签名，产物 dist/KimiRC-<版本>.apk
```

脚本自行跑完整链路：`aapt2 compile` → `aapt2 link`（顺便生成 `R.java`）→ `javac` → `d8` → 打包 → `zipalign` → `apksigner`。

JDK 与 build-tools 的版本是绑定的：34.0.0 自带的 d8（R8 8.2.2）在解析 JDK 21 为内部类构造函数生成的空名 `MethodParameters` 属性时会抛 NPE，必须用 35.0.0 及以上（R8 8.6.2 已修复）。字节码目标仍为 Java 8，以保留 `-bootclasspath android.jar` 对平台 API 面的约束。

CI（GitHub Actions）在 push 与 PR 上自动出包并上传 artifact。

### 图标

启动图标与启动图 logo 的位图均由 `assets/logo.png` 派生，换图后重新生成（需 Pillow）：

```bash
python tools/make-icons.py
```

脚本按 `res/` 的密度分档输出 `res/mipmap-*/ic_launcher_foreground.png`（自适应图标前景，108dp 画布 / 66dp 内容）与 `res/drawable-*/logo.png`（启动图用，144dp）。画布底色按图片边框泛洪扣除，图案内部的白（角色的眼睛）保留。图标背景层 `res/drawable/ic_launcher_background.xml` 为纯白。这些 PNG 不要手工编辑。

## 发布

打 `v*` 标签即触发 release 工作流：用 Secrets 中的正式密钥签名，创建 GitHub Release 并附 APK、文件 SHA-256、证书指纹与更新摘要。Release 正文里的更新摘要取自本文件「更新记录」中与标签同名的那条，因此**打标签前必须先在 README 补上 `- **vX.Y.Z**：...` 条目**；工作流会先用 `tools/release-notes.sh` 检查该条目存在、且标签与 `build.sh` 的 `VERSION_NAME` 一致，任一不满足都在构建前直接失败。仓库需配置以下 Secrets：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | `base64 -w0 release.keystore` 的输出（keystore 文件本身不入库） |
| `RELEASE_STORE_PASSWORD` | keystore 口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名（当前为 `kimirc`） |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |
| `CNB_TOKEN` | CNB 访问令牌（含 `repo-contents` 读写；Git 推送用户名固定 `cnb`，仅供 CI，绝不进入客户端） |

自行生成新密钥：`keytool -genkeypair -keystore release.keystore -alias kimirc -keyalg RSA -keysize 2048 -validity 10000`，注意更新上表与下方指纹。

Release 发布后，`.github/workflows/sync-cnb.yml` 会由推送 `v*` 标签触发，也可手动指定 tag：它与 release 工作流同时起跑，因此下载 APK 前会带时限地等待该 Release 的 APK 附件就绪；随后下载本 Release 的 APK 并对字节实测 SHA-256（不从正文抓取字符串），生成 CNB 版清单 `latest.json`，把该 tag 推到 CNB 仓，再把清单提交到 CNB 仓的 `updater` 分支（该分支只有 `latest.json`）。工作流会检查被 tag 的提交中是否存在 `.cnb.yml`，不存在就直接失败，避免发出指向死链的清单。这里**不用** `release: published` 触发——release 工作流用 Actions 自带的 `GITHUB_TOKEN` 创建 Release，而 GitHub 规定由 `GITHUB_TOKEN` 触发的事件不会再触发新的 workflow 运行，那条触发对自动流程从未生效过。推 tag 之后、提交清单之前，工作流还会实测一次清单要指向的 CNB 附件直链（下载整包比对 SHA-256，最多等 8 分钟），到时限仍拿不到或对不上只告警、不失败；清单写入 `updater` 分支时只允许升不允许降，用手动补同步旧 tag 不会把清单回滚到更旧的版本。

仓库根目录的 `.cnb.yml` 是 CNB 侧流水线，由 `tag_push` 触发，用内置任务 `git:release` 创建同名 Release，再把 APK 从 GitHub Release 拉到 CNB 挂成 Release 附件。CNB 的 `tag_push` 使用被 tag 的那个提交里的 `.cnb.yml`，所以修改 `.cnb.yml` 必须先合入 `master` 再打新标签才生效；`sync-cnb.yml` 同样取自被 tag 的那个提交，改它也**必须先合入 `master` 再打新标签**。

### 已知限制与待验证项

- CNB 构建节点出网访问 github.com 已实测可用：v0.1.6 首次同步时 APK 成功从 GitHub 拉到 CNB 并挂成附件。
- 「清单已发布、CNB 附件还没上传完」存在几分钟窗口，期间 CNB 直链会短暂 404；客户端会自动回退 GitHub，不影响使用。
- CNB 仓当前含有 `v0.1.6` 的 Release（附件里有 `KimiRC-0.1.6.apk`）、该 tag，以及只放 `latest.json` 的 `updater` 分支；CNB 把 `HEAD` 指向了 `updater`，因此该仓没有源码分支。
- `.cnb.yml` 的 `git:release` 设了 `overlying: true`（叠加模式，重跑时不先删旧 Release）。官方文档只把它描述为「仅编辑/更新」，而任务本身的定位是「生成或更新」，所以「Release 尚不存在时 true 是否仍会创建」没有明文，需要在下一次打 tag 时确认首个 Release 能否正常建出；万一没建出来，删掉那一行即可恢复。
- 客户端在 CNB raw 通道失效时会尝试第二种 CNB 形态（固定 tag 的 Release 附件），但 CI 目前还没有发布那一份；这只是 raw 通道万一变动时的兜底预留。

## 版本校验

release 签名证书 SHA-256 指纹（可用 `apksigner verify --print-certs KimiRC-x.y.z.apk` 核对）：

```
D2CC3CE9BD943333D27CBBF810026645D3F9AE131E0D4C27D3637249911046FF
```

debug 构建使用另一把密钥，仅用于本地开发与 CI artifact，勿用于分发。GitHub Releases 与 CNB 两个渠道的产物同名、同 SHA-256、同一签名；客户端通过比对下载包与当前已安装包的签名证书核对更新，上方证书指纹供人工核对。请只从这两个官方渠道下载安装包，以防第三方渠道夹带篡改包。

v0.1.0 起换用了上述新密钥：与更早的构建（v1.1.0 及以前）签名不兼容，安装前需先卸载旧包，无法直接覆盖升级。

## 技术栈

| 类别 | 内容 |
| --- | --- |
| 语言与目标 | Java 8（source/target），minSdk 28 / targetSdk 34 |
| 界面 | 原生 View，无 AndroidX / Support Library / ViewBinding / Fragment / RecyclerView |
| WebView | 多进程，每账号一进程 + 独立数据目录（`:acct0`…`:acct3`） |
| 扫码 | Camera2 + ZXing Core（Apache-2.0） |
| 存储 | SharedPreferences + JSON；跨进程状态一律落文件 |
| 构建 | 脚本化：aapt2 + javac + d8 + zipalign + apksigner（无 Gradle） |

## 许可与致谢

- 本项目以 **MIT** 协议开源，全文见 [LICENSE](LICENSE)。
- 内置的 **ZXing Core**（纯 Java 二维码解码）遵循 **Apache License 2.0**，版权归 ZXing authors 所有，详见 [NOTICE](NOTICE)；jar 为 `libs/zxing-core-3.5.3.jar`，SHA-256 固定校验在 `build.sh` 中。
- 除 ZXing Core 外不引入任何第三方依赖，承诺不使用原生库与 GMS 服务。
- 「Kimi」名称与商标归其权利人所有，本项目与 Moonshot AI 无隶属或授权关系，详见文末免责声明。

欢迎提交 Issue 与 PR。贡献前请先阅读 [AGENTS.md](AGENTS.md)，其中记录了本项目的代码风格、多进程约定、安全红线与发版流程；**请勿改动安全相关的默认行为**（域名白名单、SSL 错误处理、截屏保护、更新校验）。

## 更新记录

- **v0.1.10**：移除 v0.1.9 加入的系统通知。该功能依赖网页在「回合结束」时主动发通知，而手机远控时回合是在电脑上执行的、网页侧不会走到那条路径（Kimi 也已确认手机连接的远控不提供通知），所以通知实际发不出来，用户能看到的只有首次进入远控页时的一次通知权限申请。这一版把功能整体收回：不再申请 `POST_NOTIFICATIONS` 权限，由 `assets/logo.png` 派生的两张通知图标与相关代码一并删除，其余行为与 v0.1.8 一致。
- **v0.1.9**：远控网页的「回合完成 / 待回答 / 待审批」提醒现在会在手机上弹系统通知。网页本来用浏览器的通知 API 发这类提醒，而 WebView 不提供这个 API、网页自己会判定「浏览器不支持」并整条跳过，所以壳内补出一份最小实现，把通知交给系统通知栏：跟随网页的通知提示音开关（有声 / 静音两个通知渠道），同一会话的重复提醒按网页给的标识覆盖、不堆叠，点通知回到对应账号的远控页（该账号的远控页仍在时会同时切到该会话），Android 13+ 首次进入远控页时申请一次通知权限。状态栏小图标与展开后的大图标都由 `assets/logo.png` 派生：前者是系统会统一染色的单色剪影，后者保留彩色 logo。通知正文含会话标题与提问内容，截屏保护开启时（默认）只显示状态文案。（此功能在手机远控场景下无法生效，已由 v0.1.10 移除。）
- **v0.1.8**：设置页新增「隐藏远控页标题栏」开关（只作用于远控页的顶部标题栏，主页与设置页不受影响；隐藏后返回手势仍可用，「刷新」与「登录状态」入口一并收起）与「清理缓存」入口（清除各账号远控页的网页缓存，不动 cookie 与站点数据，登录状态与设备列表不受影响；网页缓存属于各账号进程自己的 WebView profile，主进程只落标记，由对应账号进程在下次打开或回到远控页时完成清理）。主页头部「设置」入口改用齿轮图标，原来的图标形似亮度开关。
- **v0.1.7**：修复应用内更新的一批问题。发布链路：CNB 同步改由标签推送触发——原先挂在 `release: published` 上，而该事件由 CI 的 `GITHUB_TOKEN` 触发时不会再启动新工作流，v0.1.6 的 CNB 同步实为手工补做；同步现在会先核验 CNB 附件可下载且哈希一致（超时只告警），拒绝用补同步把清单回滚到更旧版本，重跑也不会先删掉已发布的 Release。客户端：签名不匹配时不再建议用户卸载（那会把用户从签名信任锚上引开）；去系统设置授权「安装未知应用」后返回可自动继续；多签名安装包按完整签名集合比对；取消下载或安装后不再提交；安装失败的提示改用「安装更新」标题并指向该版本的发布页；设置页在内容超出屏幕时可滚动；GitHub 渠道也能取到版本代码。
- **v0.1.6**：新增应用内检查更新：设置页「版本」行可点按手动检查，主页在应用锁解锁后静默自动检查（默认开启、24 小时最多一次、只拉取元数据）；发现新版本可应用内下载并安装，安装包优先走与元数据同源的直链、失败自动切换到另一个源（国内即 CNB 镜像加速）；安装前校验 SHA-256 与签名（签名须与已安装包一致），校验不通过一律不安装。发布链路新增 CNB 同步：Release 后自动推送 tag 与更新清单到 CNB，并把 APK 挂为 CNB 附件。
- **v0.1.5**：新增独立设置页（主页头部「设置」入口），集中截屏保护开关（默认开启，关闭后可正常截屏）、应用锁状态提示、外观主题（跟随系统 / 月之亮面 / 月之暗面，切换即时生效）、版本信息与「关于」；截屏保护改由一处统一控制，并覆盖弹窗窗口，窗口回到前台时自动校正；「账号管理」弹窗回归纯账号管理，主页头部拆成账号与设置两个入口。同期并入在做的暗色模式与账号昵称展示。
- **v0.1.0**：GitHub 正式发布，版本号自此重新起算（此前 gitee 上的 1.0.0 / 1.1.0 为早期内部构建）；启用新的发布签名密钥。功能见上文。
- **v1.1.0**：外部链接直达（intent-filter）、文件选择器、相机扫码添加设备（Camera2 + ZXing）、应用锁覆盖 API 26–27、启动图与浅色状态栏适配、正式签名发布通道。
- **v1.0.0**：设备书签、WebView 壳、Cookie 持久化、域名白名单、生物识别应用锁、剪贴板自动填充。

## 免责声明

本项目为社区非营利性质，与 Moonshot AI 无任何隶属或授权关系，「Kimi」名称与商标归其权利人所有。用户通过本应用访问的登录与远控服务均由 Kimi 官方提供，相关数据处理遵守 Kimi 官方隐私政策。代码以 MIT 协议开源，内置 ZXing Core 遵循 Apache-2.0。使用者自行承担使用风险。若官方提出异议，项目将立即下架并归档；官方移动端支持 Remote Control 后，本项目转为归档模式并指路。
