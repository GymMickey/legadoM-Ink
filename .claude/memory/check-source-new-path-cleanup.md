## 删除书源校验（新界面），保留旧路径「校验所选」

### 背景

书源管理有两个校验入口，共用同一后端（`CheckSource` → `CheckSourceService`）：

| 菜单项 | 实现 | 
|--------|------|
| `menu_check_source` — "校验所选" | AlertDialog → BookSourceActivity 内嵌进度 |
| `menu_check_source_compose` — "校验书源(新界面)" | 独立 Compose Activity（3 文件，~2200 行） |

二者功能等价。删除 Compose 新界面可减少代码量，旧路径同样可用（已修复 startForeground 崩溃）。

### 保留（不碰）

以下共享代码新旧路径共用，**不动**：
- `model/CheckSource.kt` — 校验配置 + 启动
- `model/CheckSourceResultEvent.kt` — 结果事件
- `model/Debug.kt` — debugMessageMap
- `service/CheckSourceService.kt` — 前台 Service 执行校验（刚修完崩溃）
- `ui/config/CheckSourceConfig.kt` — 校验设置弹窗

---

### 删除内容

#### 1. 删除 3 个 Kotlin 文件

直接删除：
```
app/src/main/java/io/legado/app/ui/book/source/check/CheckSourceActivity.kt
app/src/main/java/io/legado/app/ui/book/source/check/CheckSourceScreen.kt
app/src/main/java/io/legado/app/ui/book/source/check/CheckSourceViewModel.kt
```

如果 `check/` 目录变空，删除目录。

#### 2. BookSourceActivity.kt — 删除跳转方法

文件：`app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt`

**2a.** 删除 `onMenuItemClick` 中的 case（约 535 行）：
```kotlin
            R.id.menu_check_source_compose -> checkSourceCompose()
```

**2b.** 删除 `checkSourceCompose()` 方法（约 602-604 行）：
```kotlin
    private fun checkSourceCompose() {
        startActivity<io.legado.app.ui.book.source.check.CheckSourceActivity>()
    }
```

#### 3. book_source_sel.xml — 删除菜单项

文件：`app/src/main/res/menu/book_source_sel.xml`

删除以下 5 行（约 61-65 行）：
```xml
    <item
        android:id="@+id/menu_check_source_compose"
        android:icon="@drawable/ic_check_source"
        android:title="@string/check_source_compose"
        app:showAsAction="never" />
```

#### 4. AndroidManifest.xml — 删除 Activity 声明

文件：`app/src/main/AndroidManifest.xml`

搜索并删除（约 350 行）：
```xml
        <activity
            android:name=".ui.book.source.check.CheckSourceActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

#### 5. 删除字符串键（4 个语言文件）

搜索并删除 `check_source_compose` 对应的行：

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
- `app/src/main/res/values-zh-rHK/strings.xml`

示例：
```
<string name="check_source_compose">校验书源(新界面)</string>
<string name="check_source_compose">校驗書源</string>
<string name="check_source_compose">Check source (New UI)</string>
```

---

### 约束

- 上到下按顺序执行
- 每步编译通过再继续
- 不 commit，不 push
