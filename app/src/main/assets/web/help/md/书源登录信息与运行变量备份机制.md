# 书源登录信息与运行变量备份机制

## 概述

书源登录信息和运行变量是阅读中非常重要的数据，它们用于保存用户的登录状态和书源自定义的运行时变量。默认情况下，这些数据**不会被备份**，需要用户手动开启「完整备份」选项才会包含在备份中。

---

## 一、数据存储位置

这些数据统一存储在 `caches` 数据库表中（Room DB），而非书源本身的配置文件中。

### 1.1 数据类型对照表

| 数据类型 | Key 格式 | 说明 | 加密方式 |
|---------|---------|------|---------|
| 书源登录信息 | `userInfo_${书源URL}` | 保存用户的登录凭证 | AES 加密 |
| 登录头部信息 | `loginHeader_${书源URL}` | 请求时自动添加的 Header | 普通存储 |
| 运行变量 | `sourceVariable_${书源URL}` | 书源自定义的运行时变量 | 普通存储 |
| 其他变量 | `v_${书源URL}_${key}` | 书源通用键值对存储 | 普通存储 |

### 1.2 存储实体

**Cache 表结构：**

```kotlin
@Entity(tableName = "caches", indices = [(Index(value = ["key"], unique = true))])
data class Cache(
    @PrimaryKey
    val key: String = "",
    var value: String? = null,
    var deadline: Long = 0L  // 0 表示永不过期
)
```

### 1.3 相关代码文件

| 文件 | 路径 | 说明 |
|------|------|------|
| BaseSource.kt | `app/src/main/java/io/legado/app/data/entities/BaseSource.kt` | 书源登录信息存取核心类 |
| CacheManager.kt | `app/src/main/java/io/legado/app/help/CacheManager.kt` | 通用缓存管理 |
| CacheDao.kt | `app/src/main/java/io/legado/app/data/dao/CacheDao.kt` | 缓存数据库操作 |
| Backup.kt | `app/src/main/java/io/legado/app/help/storage/Backup.kt` | 备份核心逻辑 |
| Restore.kt | `app/src/main/java/io/legado/app/help/storage/Restore.kt` | 恢复核心逻辑 |

---

## 二、登录信息详解

### 2.1 获取与保存登录信息

书源登录信息采用 AES 加密存储，保护用户敏感数据：

```kotlin
// 获取登录信息（解密）
fun getLoginInfo(): String? {
    val key = AppConst.androidId.encodeToByteArray(0, 16)
    val cache = CacheManager.get("userInfo_${getKey()}") ?: return null
    return AES(key).decryptStr(cache)
}

// 保存登录信息（加密）
fun putLoginInfo(info: String): Boolean {
    val key = (AppConst.androidId).encodeToByteArray(0, 16)
    val encodeStr = SymmetricCryptoAndroid("AES", key).encryptBase64(info)
    CacheManager.put("userInfo_${getKey()}", encodeStr)
    return true
}
```

**加密原理：**
- 使用设备的 `androidId` 生成 16 字节的 AES 密钥
- 登录信息 Base64 加密后存储
- 同一设备备份恢复时可以直接解密

### 2.2 登录头部信息

书源的登录 Header 会在请求时自动添加到请求头中：

```kotlin
// 保存登录头部
fun putLoginHeader(header: String) {
    val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
    val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
    cookie?.let {
        CookieStore.replaceCookie(getKey(), it)  // 同时更新 Cookie 存储
    }
    CacheManager.put("loginHeader_${getKey()}", header)
}

// 获取登录头部
fun getLoginHeaderMap(): Map<String, String>? {
    val cache = getLoginHeader() ?: return null
    return GSON.fromJsonObject<Map<String, String>>(cache).getOrNull()
}
```

### 2.3 登录 UI 与登录信息 Map

登录 UI 用于在书源登录界面展示表单，登录信息 Map 用于管理登录表单的默认值：

```kotlin
fun getLoginInfoMap(): MutableMap<String, String> {
    val json = getLoginInfo() ?: if (loginUi.isNullOrBlank()) {
        return mutableMapOf()
    } else {
        // 从 loginUi 解析默认值
        val loginUiJson = loginUi?.let { ... }
        val longinInfo = GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
            ?.filter { it.type != "button" }
            ?.associate { it.name to (it.default ?: "") }
        return longinInfo?.toMutableMap() ?: mutableMapOf()
    }
}
```

---

## 三、运行变量详解

### 3.1 自定义变量 (sourceVariable)

书源自定义的运行时变量，通过 `setVariable()` 或 `putVariable()` 保存：

```kotlin
// 保存运行变量
fun putVariable(variable: String?) {
    if (variable != null) {
        CacheManager.put("sourceVariable_${getKey()}", variable)
    } else {
        CacheManager.delete("sourceVariable_${getKey()}")
    }
}

// 获取运行变量
fun getVariable(): String {
    return CacheManager.get("sourceVariable_${getKey()}") ?: ""
}
```

### 3.2 通用键值对存储

书源可以使用 `put()` 和 `get()` 方法存储任意键值对：

```kotlin
// 存储数据
fun put(key: String, value: String): String {
    CacheManager.put("v_${getKey()}_${key}", value)
    return value
}

// 获取数据
fun get(key: String): String {
    return CacheManager.get("v_${getKey()}_${key}") ?: ""
}
```

---

## 四、备份机制

### 4.1 备份条件

**默认情况下不备份！** 需要手动开启「完整备份」选项。

```kotlin
// Backup.kt 第 512 行
if (BackupConfig.fullBackup) {
    stageRuntimeSourceCaches(backupPath)
}
```

### 4.2 完整备份配置

```kotlin
// BackupConfig.kt
val fullBackup: Boolean
    get() = appCtx.defaultSharedPreferences.getBoolean("fullBackup", false)
```

- 配置路径：`设置 → 备份与恢复 → 完整备份`
- 默认值：`false`（不开启）

### 4.3 备份数据范围

开启完整备份后，会备份以下运行时源缓存：

```sql
SELECT * FROM caches
WHERE (
    `key` LIKE 'v_%'
    OR `key` LIKE 'userInfo_%'
    OR `key` LIKE 'loginHeader_%'
    OR `key` LIKE 'sourceVariable_%'
    OR `key` LIKE 'infoMap_%'
)
AND (deadline = 0 OR deadline > :now)
```

### 4.4 备份文件

完整备份时会生成额外的文件：

| 文件名 | 内容 |
|--------|------|
| `runtimeSourceCache.json` | 所有运行时源缓存数据（登录信息、变量等） |

### 4.5 备份流程图

```
用户点击备份
       │
       ▼
检查是否选择 "bookSource.json"
       │
       ▼
检查 BackupConfig.fullBackup 是否为 true
       │
       ├── false → 跳过运行时源缓存备份
       │
       └── true  → 导出 runtimeSourceCache.json
                      │
                      ▼
                 打包到 ZIP 文件
```

---

## 五、恢复机制

### 5.1 恢复流程

```kotlin
// Restore.kt 第 770 行
private fun restoreRuntimeSourceCaches(path: String) {
    val runtimeCacheFile = File(path, runtimeSourceCacheFileName)
    if (!runtimeCacheFile.exists()) return

    val caches = fileToListT<Cache>(path, runtimeSourceCacheFileName).orEmpty()
    appDb.cacheDao.deleteAllRuntimeSourceCaches()  // 先清空
    AppCacheManager.clearSourceVariables()         // 清空内存缓存

    if (caches.isNotEmpty()) {
        appDb.cacheDao.insert(*caches.toTypedArray())  // 重新插入
    }
}
```

### 5.2 恢复时清空机制

恢复时会先清空所有运行时源缓存，这是为了避免旧数据残留导致的问题：

```kotlin
@Query("""
    DELETE FROM caches
    WHERE `key` LIKE 'v_%'
    OR `key` LIKE 'userInfo_%'
    OR `key` LIKE 'loginHeader_%'
    OR `key` LIKE 'sourceVariable_%'
    OR `key` LIKE 'infoMap_%'
""")
fun deleteAllRuntimeSourceCaches()
```

---

## 六、如何开启完整备份

### 6.1 操作步骤

1. 打开阅读 App
2. 进入「我的」页面
3. 点击「备份与恢复」
4. 点击右上角设置图标（备份配置）
5. 找到「完整备份」选项
6. 将其开启

### 6.2 配置界面位置

```
Settings
  └── BackupConfigFragment
        └── pref_config_backup.xml
              └── fullBackup (default: false)
```

---

## 七、注意事项

### 7.1 设备相关性

- 登录信息使用设备 `androidId` 作为密钥
- 备份恢复时需要是同一设备，或者接受登录信息丢失
- 换手机后需要重新登录书源

### 7.2 数据时效性

- 带有过期时间的缓存（`deadline > 0`）如果已过期则不会被备份
- 永不过期的数据（`deadline = 0`）会一直保留

### 7.3 内存与磁盘同步

```kotlin
fun put(key: String, value: Any, saveTime: Int = 0) {
    val deadline = if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000
    val valueStr = value.toString()

    // 同时写入内存和磁盘
    putMemory(key, valueStr)                    // 内存缓存
    val cache = Cache(key, valueStr, deadline)
    appDb.cacheDao.insert(cache)                // 磁盘存储
}
```

---

## 八、常见问题

**Q1: 为什么默认不备份登录信息？**

A1: 登录信息与设备绑定（使用设备 ID 加密），换手机后无法使用，开启完整备份可能造成数据不一致。

**Q2: 备份后换手机，登录信息还能用吗？**

A2: 不能。登录信息与原设备的 androidId 绑定，换手机后需要重新登录。

**Q3: 如何只备份书源不备份登录信息？**

A3: 默认行为就是不备份登录信息。只有开启完整备份才会包含。

**Q4: 运行变量和书源配置是什么关系？**

A4: 运行变量存储在独立的 `caches` 表中，与书源配置（`bookSource.json`）分开存储。书源配置可以备份，但运行时数据默认不备份。
