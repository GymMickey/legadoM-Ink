---
name: github-release-workflow
description: GitHub Release 发布流程——Token 认证、编译 APK、创建 Release 并上传
metadata:
  type: reference
---

## GitHub Release 发布流程

### 前提

- 仓库：`github.com/GymMickey/legado`
- GitHub 主站（443）被墙，但 `api.github.com` 可达
- 使用 Fine-grained token + `gh auth login --with-token` 认证

### Token 准备（一次性，已缓存可跳过）

1. 浏览器打开 https://github.com/settings/tokens → Fine-grained token
2. Repository access: **Only select repositories** → `GymMickey/legado`
3. Permissions: `Contents: Read & Write`（Metadata 自动带）
4. 生成后粘贴给终端：`echo "<token>" | gh auth login --with-token`
5. Token 自动缓存在系统 keyring，后续无需重复

### 发布步骤

1. **确保 Gradle 没有改动**（签名密码仅在用户级 `GRADLE_USER_HOME/gradle.properties`）
2. **打 Release APK**：`./gradlew clean assembleRelease`
3. **获取版本号**：`cat build_number.txt`
4. **获取 changelog**:

```bash
git log <上次 tag>..HEAD --oneline --no-merges
```

5. **创建 Release**：

```bash
gh release create "v3.26-beta.<buildNumber>" \
  --title "v3.26-beta.<buildNumber>" \
  --notes "<changelog>" \
  "<APK路径>"
```

APK 路径格式：`app/build/outputs/apk/appMax/release/阅读_测试版_3.26-beta <buildNumber>_release.apk`

### 注意事项

- 先 commit + push 代码，再创建 Release（tag 会指向最新的 commit）
- 如 tag 已存在，加 `--draft` 草稿发布
- Token 过期时重走一次 Token 准备步骤

### 历史发布

| 版本 | 日期 | 主要内容 |
|------|------|---------|
| [v3.26-beta.66](https://github.com/GymMickey/legado/releases/tag/v3.26-beta.66) | 2026-07-16 | 首页直达阅读、WiFi传书完善、本地书籍管理、自动扫描 |
| v3.26-beta51 | - | 之前的发布 |

### 相关记忆

- [[gradle-property-precedence]] — 签名密码必须放用户级，避免进入 Git
