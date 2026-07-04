# Activity + Screen 架构模式
这是 Compose 推荐的模式，也是现代 Android 开发的最佳实践。你的项目已经采用了这个模式，结构很清晰！
## 概述

Activity + Screen 是 Jetpack Compose 推荐的界面架构模式，将传统的 Activity 职责拆分为两层：

- **Activity**：作为容器，只处理 Android 系统层面的工作
- **Screen**：作为 UI 层，处理所有界面相关的事情

---

## 传统 View 系统的问题

### 传统写法

```kotlin
class RegexTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegexTestBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegexTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 初始化视图
        binding.etPattern.setText(pattern)
        binding.btnTest.setOnClickListener { performTest() }
        // ... 大量 UI 逻辑
    }
    
    private fun performTest() {
        // 业务逻辑也写在这里
    }
}
```

### 存在的问题

| 问题 | 说明 |
|------|------|
| 职责过重 | Activity 既要管生命周期，又要管 UI，还要管业务逻辑 |
| 难以测试 | UI 逻辑和 Android 生命周期耦合，无法单独测试 |
| 难以复用 | 界面代码写死在 Activity 中，无法嵌入其他页面 |
| 代码膨胀 | 一个 Activity 文件动辄几百上千行 |

---

## Activity + Screen 模式

### 基本结构

```kotlin
// Activity 只做容器
class RegexTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RegexTestScreen(onBackClick = { finish() })
        }
    }
}

// Screen 负责 UI
@Composable
fun RegexTestScreen(onBackClick: () -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    // ... 所有 UI 逻辑
}
```

### 职责划分

```
┌─────────────────────────────────────────────────────────────┐
│                     Activity (容器层)                        │
├─────────────────────────────────────────────────────────────┤
│  ✓ 生命周期管理 (onCreate, onDestroy 等)                    │
│  ✓ 系统栏配置（状态栏、导航栏）                              │
│  ✓ 主题初始化                                               │
│  ✓ 背景图片加载                                             │
│  ✓ setContent { Screen() } 托管 Compose                     │
│  ✓ 处理 Intent 参数                                         │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Screen (UI 层)                          │
├─────────────────────────────────────────────────────────────┤
│  ✓ UI 布局和组件                                            │
│  ✓ 状态管理 (remember, mutableStateOf)                      │
│  ✓ 用户交互处理                                             │
│  ✓ 简单业务逻辑                                             │
│  ✓ 接收回调参数 (onBackClick 等)                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 实际案例

### Activity 实现

```kotlin
class RegexTestActivity : AppCompatActivity() {

    private var bgDrawable: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        initTheme()           // 主题初始化
        super.onCreate(savedInstanceState)
        setupSystemBar()      // 系统栏设置
        loadBackgroundImage() // 背景加载
        enableEdgeToEdge()    // 边到边模式
        
        setContent {
            RegexTestContent(
                bgDrawable = bgDrawable,
                onBackClick = { finish() }
            )
        }
    }

    private fun initTheme() {
        val theme = ThemeConfig.getTheme()
        when (theme) {
            Theme.Dark -> setTheme(R.style.AppTheme_Dark)
            Theme.Light -> setTheme(R.style.AppTheme_Light)
            else -> {
                if (ColorUtils.isColorLight(primaryColor)) {
                    setTheme(R.style.AppTheme_Light)
                } else {
                    setTheme(R.style.AppTheme_Dark)
                }
            }
        }
    }

    private fun setupSystemBar() {
        fullScreen()
        val statusBarColor = ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, AppConfig.isTransparentStatusBar, true)
        setLightStatusBar(ColorUtils.isColorLight(backgroundColor))
        // ...
    }

    private fun loadBackgroundImage() {
        bgDrawable = ThemeConfig.getBgImage(this, metrics)
    }
}
```

### Screen 实现

```kotlin
@Composable
fun RegexTestScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    
    // 状态管理
    var pattern by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(false) }
    var multiline by remember { mutableStateOf(false) }
    var dotAll by remember { mutableStateOf(false) }

    // 页面骨架
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正则测试") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 正则输入框
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("正则表达式") }
            )
            
            // 匹配选项
            Row {
                Checkbox(checked = ignoreCase, onCheckedChange = { ignoreCase = it })
                Text("忽略大小写")
            }
            
            // 测试按钮
            Button(onClick = { /* 执行匹配 */ }) {
                Text("测试")
            }
            
            // 结果显示
            Text(text = result)
        }
    }
}
```

---

## 模式优势

### 1. 可测试性

Screen 是纯 Composable 函数，可以脱离 Activity 进行测试：

```kotlin
@Test
fun testRegexScreen() {
    composeTestRule.setContent {
        RegexTestScreen(onBackClick = {})
    }
    
    composeTestRule.onNodeWithText("正则表达式").assertExists()
}
```

### 2. 可复用性

Screen 可以嵌入到不同的容器中：

```kotlin
// 作为独立页面
class RegexTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContent { RegexTestScreen(onBackClick = { finish() }) }
    }
}

// 作为弹窗内容
@Composable
fun RegexTestBottomSheet(onDismiss: () -> Unit) {
    BottomSheet {
        RegexTestScreen(onBackClick = onDismiss)
    }
}

// 作为 Tab 内容
@Composable
fun DebugToolsTab() {
    TabRow {
        Tab(text = { Text("正则测试") }) {
            RegexTestScreen(onBackClick = { /* 切换 Tab */ })
        }
    }
}
```

### 3. 关注点分离

| 层级 | 职责 | 依赖 |
|------|------|------|
| Activity | Android 系统交互 | Android SDK, Compose |
| Screen | UI 展示和交互 | Compose, 业务逻辑 |
| ViewModel (可选) | 状态管理和业务逻辑 | Kotlin, Coroutines |

### 4. 预览支持

可以直接在 Android Studio 中预览 Screen，无需运行 Activity：

```kotlin
@Preview(showBackground = true)
@Composable
fun RegexTestScreenPreview() {
    AppTheme {
        RegexTestScreen(onBackClick = {})
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RegexTestScreenDarkPreview() {
    AppTheme(darkTheme = true) {
        RegexTestScreen(onBackClick = {})
    }
}
```

---

## 进阶：三层架构

当业务逻辑复杂时，可以加入 ViewModel 层：

```
┌─────────────────────────────────────────────────────────────┐
│                     Activity (容器层)                        │
│  - 生命周期管理                                              │
│  - 系统配置                                                  │
│  - 获取 ViewModel                                            │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Screen (UI 层)                          │
│  - UI 布局                                                   │
│  - 观察 ViewModel 状态                                       │
│  - 发送用户事件给 ViewModel                                  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                    ViewModel (逻辑层)                         │
│  - 状态持有 (StateFlow)                                     │
│  - 业务逻辑处理                                              │
│  - 数据请求和缓存                                            │
└─────────────────────────────────────────────────────────────┘
```

### 示例代码

```kotlin
// ViewModel
class RegexTestViewModel : ViewModel() {
    private val _pattern = MutableStateFlow("")
    val pattern: StateFlow<String> = _pattern.asStateFlow()
    
    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result.asStateFlow()
    
    fun updatePattern(newPattern: String) {
        _pattern.value = newPattern
    }
    
    fun performTest(input: String, options: RegexOptions) {
        viewModelScope.launch {
            // 执行匹配逻辑
            val regex = Regex(_pattern.value, options)
            val matches = regex.findAll(input).toList()
            _result.value = formatResult(matches)
        }
    }
}

// Activity
class RegexTestActivity : AppCompatActivity() {
    private val viewModel: RegexTestViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RegexTestScreen(
                viewModel = viewModel,
                onBackClick = { finish() }
            )
        }
    }
}

// Screen
@Composable
fun RegexTestScreen(
    viewModel: RegexTestViewModel,
    onBackClick: () -> Unit
) {
    val pattern by viewModel.pattern.collectAsState()
    val result by viewModel.result.collectAsState()
    
    Scaffold(
        topBar = { /* ... */ }
    ) {
        Column {
            OutlinedTextField(
                value = pattern,
                onValueChange = { viewModel.updatePattern(it) }
            )
            // ...
        }
    }
}
```

---

## 何时使用 ViewModel

| 场景 | 推荐架构 |
|------|----------|
| 简单工具页面（调试工具、设置页） | Activity + Screen |
| 需要屏幕旋转保持状态 | Activity + ViewModel + Screen |
| 复杂业务逻辑 | Activity + ViewModel + Screen |
| 需要数据持久化 | Activity + ViewModel + Screen + Repository |
| 需要网络请求 | Activity + ViewModel + Screen + Repository |

---

## 项目中的实际应用

Legado Plus 项目的调试工具模块已采用此模式：

```
debug/
├── DebugToolsActivity.kt      + DebugToolsScreen.kt      (入口)
├── EncodeToolsActivity.kt     + EncodeToolsScreen.kt     (编码工具)
├── HttpDebugActivity.kt       + HttpDebugScreen.kt       (HTTP调试)
├── RegexTestActivity.kt       + RegexTestScreen.kt       (正则测试)
├── TimestampConvertActivity.kt + TimestampConvertScreen.kt (时间戳转换)
```

这种结构清晰、一致，便于维护和扩展。

---

## 总结

**Activity + Screen 模式的核心思想**：

> Activity 做最小化的容器工作，Screen 做所有 UI 事情

这是 Jetpack Compose 推荐的标准模式，具有以下优势：

1. **职责清晰**：容器与 UI 分离
2. **易于测试**：Screen 可独立测试
3. **高度复用**：Screen 可嵌入任意容器
4. **开发效率**：支持实时预览

对于复杂场景，可以扩展为 **Activity + ViewModel + Screen** 三层架构。
