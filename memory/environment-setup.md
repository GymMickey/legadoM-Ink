---
name: environment-setup
description: JDK, Android SDK, Git paths and build commands
metadata:
  type: reference
---

# 环境配置

## 关键路径

| 组件 | 路径 |
|------|------|
| Android Studio | `D:\Android Studio` |
| JDK (jbr) | `D:\Android Studio\jbr` |
| Java 版本 | OpenJDK 21.0.10 |
| Android SDK | `C:\Users\Administrator\AppData\Local\Android\Sdk` |
| SDK Platforms | android-36.1 |
| Build-tools | 36.1.0, 37.0.0 |
| Git | `C:\Program Files\Git\bin\git.exe` (2.55.0) |
| 项目根目录 | `d:\Legado\Legado_Max-main` |

## 环境变量（每次新终端需设置）

```powershell
$env:JAVA_HOME = "D:\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\build-tools\36.1.0;C:\Program Files\Git\bin;$env:PATH"
```

## local.properties

```
sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk
```

## Build

```bash
./gradlew assembleAppMaxDebug   # Debug build
```

**Why:** 每个新会话窗口都需要设置这些环境变量才能编译。
**How to apply:** 新窗口启动后先跑这段环境设置。
