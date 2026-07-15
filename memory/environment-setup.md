---
name: environment-setup
description: JDK/Android SDK/Gradle 环境配置 + APK 构建安装要点
metadata:
  type: reference
---

# 环境配置

## 当前机器（L）

| 组件 | 路径 |
|------|------|
| 用户目录 | `C:\Users\L` |
| Android Studio | ❌ 尚未安装 |
| 项目根目录 | `f:\Leagdo-mickey\legado` |
| local.properties | `f:\Leagdo-mickey\legado\local.properties`，SDK 路径待填入 |

安装 Android Studio 后预期路径：
- Studio: `C:\Program Files\Android\Android Studio`
- JBR (JDK): `C:\Program Files\Android\Android Studio\jbr`
- SDK: `C:\Users\L\AppData\Local\Android\Sdk`

## ~~旧机器（Administrator）~~ 已过期，仅供参考

| 组件 | 路径 |
|------|------|
| Android Studio | ~~`D:\Android Studio`~~ |
| JDK (jbr) | ~~`D:\Android Studio\jbr`~~ |
| Android SDK | ~~`C:\Users\Administrator\AppData\Local\Android\Sdk`~~ |

## 环境变量（安装后，每次新终端执行）

```bash
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$JAVA_HOME/bin:$PATH"
```

## local.properties（安装后写入）

```
sdk.dir=C\:\\Users\\L\\AppData\\Local\\Android\\Sdk
```

## Build

```bash
# ⚠️ 必须设 GRADLE_USER_HOME，否则 KSP 跨驱动器报错（项目在 F:，Gradle 缓存默认在 C:）
export GRADLE_USER_HOME=F:/Leagdo-mickey/.gradle

./gradlew assembleAppMaxDebug       # 仅编译 Debug APK
./gradlew :app:installAppMaxDebug   # 编译 + 安装到设备/模拟器（推荐，一步到位）
```

## 启动 App

```bash
# ⚠️ 入口是 WelcomeActivity，不是 MainActivity（Manifest 中 WelcomeActivity 才是 LAUNCHER）
adb shell am start -n io.legado.app.yuedu.debug/.ui.welcome.WelcomeActivity
```

## APK 构建关键信息（避免安装失败）

| 要点 | 详情 |
|------|------|
| Debug 包名 | `io.legado.app.yuedu.debug`（不是 `io.legado.app.yuedu`），`build.gradle:142` 加了 `.debug` 后缀 |
| APK 文件名含空格 | `阅读_测试版_3.26-beta 35_release.apk`，手动 adb install 必须加引号 |
| SDK 版本 | compileSdk 36 / minSdk 21 / targetSdk 36 |
| 原生库 | 含全部 4 个 ABI（arm64-v8a, armeabi-v7a, x86, x86_64） |
| Cronet 原生库 | ❌ 不打包进 APK，运行时从 Google CDN 下载（`CronetLoader.kt:118-169`） |
| 安装优先用真机 | 跳过模拟器系统镜像下载和启动等待，真机 USB 调试即插即用 |
| 签名冲突 | 先 `adb uninstall io.legado.app.yuedu.debug` 再安装。详见下方签名诊断 |
| KSP 跨驱动器 | 项目在 `F:` 盘必须设 `GRADLE_USER_HOME=F:/Leagdo-mickey/.gradle`，否则 KSP 报错 |
| 启动入口 | `WelcomeActivity`（LAUNCHER），非 `MainActivity` |

## 签名冲突诊断（INSTALL_FAILED_UPDATE_INCOMPATIBLE）

**现象**：模拟器能装，真机装不了。日志中只有 4 行关键信息藏在系统日志里。

**精确诊断命令**：

```bash
# 1. 提取 APK 签名指纹（无需 Java，openssl 足够）
unzip -p "app/build/outputs/apk/appMax/debug/app-max-debug.apk" META-INF/CERT.RSA | openssl pkcs7 -inform DER -text -print_certs -noout 2>/dev/null | grep -A2 "Serial Number\|Not Before\|Subject\|SHA1"

# 2. 安装时精确抓取决策日志（与 1 并行执行）
adb logcat -c && adb install -r "app/build/outputs/apk/appMax/debug/app-max-debug.apk" 2>&1 | tee /dev/stderr &
sleep 3 && adb logcat -d | grep -iE "PackageManager|PackageInstaller|INSTALL_FAILED"
```

**根因**：每个开发者的 `~/.android/debug.keystore` 不同，Debug 证书的 SHA1 也不同。Android 不允许不同签名的包覆盖安装。模拟器无旧版本所以不受影响。

**解决**：`adb uninstall io.legado.app.yuedu.debug` 后重装。长期方案：团队统一 `debug.keystore` 或用 Release 签名。

**Why:** 真机签名冲突是第三次踩坑。openssl 提取证书 + 精确 logcat 过滤可快速定位。
**How to apply:** 先 uninstall 再 install；团队协作时共用同一个 debug.keystore。
