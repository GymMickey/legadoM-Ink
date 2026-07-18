---
name: gradle-cross-drive-ksp-fix
description: Gradle 缓存跨盘符（C: vs F:）导致 Glide KSP 异常——根因与修复方案
metadata:
  type: reference
---

## Gradle 缓存跨盘符导致 KSP 异常

### 现象

编译时报 Glide KSP annotation processing 异常，不是代码问题。Clean 后重试能通过。

### 根因

Windows 下 Gradle 默认缓存目录在 `%USERPROFILE%/.gradle`（C 盘），项目在 F 盘。Glide KSP 处理器在生成代码时无法跨盘符解析相对路径。

### 修复

`gradle.properties` 中添加：

```properties
org.gradle.user.home=F\:/Leagdo-mickey/.gradle
```

⚠️ **重要**：加完后必须把 `C:\Users\L\.gradle\gradle.properties`（含签名密码）复制到 `F:\Leagdo-mickey\.gradle\gradle.properties`，否则打包签名时报密码错误。

### 验证

```
gradlew clean assembleDebug
```

### 约束

- 此配置已是项目 `gradle.properties` 的一部分，**不要删除**
- 代码窗口每次修改前应检查此配置是否存在
- 签名密码在 `F:\Leagdo-mickey\.gradle\gradle.properties` 中，不要提交到 Git

### 相关记忆

- [[gradle-property-precedence]] — GRADLE_USER_HOME 优先级陷阱
