# MetroVerse 正式版 APK 签名、构建与发布指南

本文说明如何生成可安装、可持续升级的 MetroVerse 正式版 APK。正式版签名是不可逆的身份凭据，请完整阅读后再操作。

## 1. Debug 与 Release 的区别

`fossDebug`：

- App 名称为 `MetroVerse Debug`。
- 包名为 `com.rizklee.metroverse.debug`。
- 使用 Android 默认 debug key。
- 适合开发、Logcat 和日常测试。
- 不能作为可信正式发布包。

`fossRelease`：

- App 名称为 `MetroVerse`。
- 包名为 `com.rizklee.metroverse`。
- 开启 R8 压缩和资源缩减。
- 必须使用你自己长期保存的 release keystore 签名。
- 后续覆盖安装必须继续使用同一把 keystore。

## 2. 当前本机签名状态

本仓库提供脚本：

```text
scripts/generate-release-keystore.ps1
```

脚本会生成两个本机文件：

```text
app/keystore/metroverse-release.keystore
keystore.properties
```

这两个文件已被 `.gitignore` 排除，不会上传到 GitHub。当前工作区已经运行过一次脚本并生成了本机签名文件。

立即把这两个文件备份到安全的离线位置或密码管理器附件中。不要只保存在当前硬盘。丢失 keystore 后，已经安装或发布的 MetroVerse 正式版将无法由新 APK 覆盖升级。

## 3. 在另一台电脑生成签名文件

要求 PowerShell 7 和完整 JDK 21。项目根目录运行：

```powershell
cd E:\Code\music-podcast-app\Metrolist
pwsh.exe -NoLogo -NoProfile -File .\scripts\generate-release-keystore.ps1
```

如果目标文件已经存在，脚本会停止，不会覆盖旧签名。

`keystore.properties` 中包含以下字段：

```properties
storeFile=app/keystore/metroverse-release.keystore
storePassword=随机生成的密码
keyAlias=metroverse
keyPassword=随机生成的密码
```

不要截图、提交或发送这些值。

## 4. 命令行构建正式 APK

确认 `JAVA_HOME` 指向完整 JDK 21，然后执行：

```powershell
cd E:\Code\music-podcast-app\Metrolist
.\gradlew.bat :app:assembleFossRelease
```

正式 APK 典型路径：

```text
app\build\outputs\apk\foss\release\app-foss-release.apk
```

如果没有 `keystore.properties`，Gradle 仍可能生成 unsigned APK。unsigned APK 不能作为正式交付结果。必须继续完成签名验证。

## 5. 验证 APK 签名

使用 Android SDK Build Tools 中的 `apksigner`：

```powershell
$apksigner = 'D:\Android\AndroidSDK\build-tools\37.0.0\apksigner.bat'
$apk = '.\app\build\outputs\apk\foss\release\app-foss-release.apk'
& $apksigner verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
```

应至少看到：

```text
Verifies
Verified using v2 scheme ... true
Number of signers: 1
```

同时检查包名和版本：

```powershell
$aapt = 'D:\Android\AndroidSDK\build-tools\37.0.0\aapt.exe'
& $aapt dump badging $apk | Select-String "package:|sdkVersion:|targetSdkVersion:|application-label:"
```

预期：

```text
package name='com.rizklee.metroverse'
versionCode='2'
versionName='0.2.0'
application-label='MetroVerse'
```

MetroVerse 0.2.0 及后续兼容升级必须保持以下签名身份：

```text
Signature: APK Signature Scheme v2, one signer, RSA 4096
Certificate SHA-256: e2450731e5e35b3ccd61a9fe1a12b86ca7fb3e11073ca27f3e5ff6eb6cdd7250
```

APK 哈希取决于源码提交和构建环境，不写死在被标记的源码中。每个 GitHub Release 应附带当次生成的 `SHA256SUMS`；公开下载后应再次计算 APK SHA-256 并逐字比较。证书 SHA-256 必须在所有后续升级中保持不变。

## 6. Android Studio 图形界面构建

推荐先让 Gradle 使用 `keystore.properties`，然后：

1. 用 Android Studio 打开项目根目录。
2. 在 Gradle 设置中选择完整 JDK 21。
3. 打开 **Build > Select Build Variant**。
4. 将 `app` 选择为 `fossRelease`。
5. 选择 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
6. 构建完成后点击通知中的 **locate**。
7. 使用上一节的 `apksigner` 再验证一次，不要只相信文件名。

也可以使用 **Build > Generate Signed Bundle / APK**，选择 APK、`app` 模块和现有 keystore。此时 keystore 路径、alias 和密码来自本机 `keystore.properties`。不要创建第二把正式签名 key。

## 7. 安装正式版

连接已启用 USB 调试的手机：

```powershell
$adb = 'D:\Android\AndroidSDK\platform-tools\adb.exe'
$apk = '.\app\build\outputs\apk\foss\release\app-foss-release.apk'
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }
```

`fossDebug` 和 `fossRelease` 包名不同，可以同时安装。正式版不能直接继承旧 Metrolist 或早期调试包的数据，因为 MetroVerse 使用独立包名。

## 8. 版本升级规则

每次发布前修改 `app/build.gradle.kts`：

```kotlin
versionCode = 3
versionName = "0.2.1"
```

规则：

- `versionCode` 每次正式发布必须递增，不能重复或降低。
- `versionName` 使用对用户可读的语义版本。
- 始终使用同一 release keystore。
- 在 `changelog.md` 中记录变化。
- 构建前运行测试和 lint。
- 构建后验证签名、包名、版本和安装升级。

## 9. 推荐发布前命令

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
.\gradlew.bat :app:testFossDebugUnitTest --tests "com.metrolist.music.podcast.PodcastParsingTest"
.\gradlew.bat :app:lintFossDebug
.\gradlew.bat :app:assembleFossDebug
.\gradlew.bat :app:assembleFossRelease
```

然后在真实手机上完成：

- 音乐搜索和播放。
- Apple Podcasts 两个不同地区的搜索。
- RSS 添加、订阅和刷新。
- 单集封面、背景、分享和详情。
- 播放速度、10 秒跳转、睡眠计时器和断点续播。
- 收藏、下载、断网播放和删除下载。
- App 进程被终止后的队列恢复。
- 英文和简体中文界面。

## 10. GitHub Actions 正式发布

`.github/workflows/release.yml` 只能手动运行，并要求以下 GitHub Actions Secrets：

```text
KEYSTORE
KEY_ALIAS
KEYSTORE_PASSWORD
KEY_PASSWORD
LASTFM_API_KEY        可选
LASTFM_SECRET         可选
```

其中 `KEYSTORE` 通常是 keystore 文件的 Base64 内容。设置 Secrets 后，从 GitHub 的 **Actions > Build signed MetroVerse release > Run workflow** 手动输入 tag，例如 `v0.2.0`。

不要在尚未配置签名 Secrets 时运行发布工作流。普通 `Build MetroVerse` 工作流只生成 Debug APK，不需要签名 Secrets。

## 11. Last.fm 与播客

Last.fm API Key 对播客不是必需的。没有 Key 时，音乐的 Last.fm 相关集成功能不可用，但 RSS、Apple Podcasts 发现和播客播放不受影响。

本机 `lastfm-api-key.txt` 不会被构建脚本自动读取，也不应提交。需要时把值写入未提交的 `local.properties`：

```properties
LASTFM_API_KEY=你的Key
LASTFM_SECRET=你的Secret
```

## 12. 不能丢失或上传的文件

必须私下备份、绝不能提交：

```text
app/keystore/metroverse-release.keystore
keystore.properties
local.properties
lastfm-api-key.txt
```

发布前运行：

```powershell
git status --short --ignored
```

确认 keystore 和 `keystore.properties` 显示为 ignored，而不是 staged。
