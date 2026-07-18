---
name: gradle-property-precedence
description: GRADLE_USER_HOME 属性优先级高于项目级 gradle.properties，可能覆盖签名密码等关键配置
metadata:
  type: reference
---

## Gradle 属性优先级：用户级覆盖项目级

Gradle 属性合并时，`GRADLE_USER_HOME/gradle.properties`（本机为 `F:/Leagdo-mickey/.gradle/gradle.properties`）的优先级**高于**项目根目录的 `gradle.properties`。

### 实际踩坑

- 项目级 `gradle.properties` 配置了 `RELEASE_STORE_PASSWORD=20000422l`
- 用户级 `GRADLE_USER_HOME/gradle.properties` 存着旧密码 `legado2026`
- 打包 release 时 Gradle 使用了用户级的旧密码 → 签名失败，症状表现为 keystore 密码错误

### 解决

同步或删除用户级 `gradle.properties` 中的冲突属性。排查签名/密钥相关问题时，优先检查 `GRADLE_USER_HOME/gradle.properties` 是否覆盖了项目级配置。

**关键文件：**
- 项目级：`gradle.properties`
- 用户级：`F:/Leagdo-mickey/.gradle/gradle.properties`
- Org Gradle 用户主目录配置：`gradle.properties` L13 `org.gradle.user.home=F:/Leagdo-mickey/.gradle`
