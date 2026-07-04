# Jetpack Compose 入门指南

> 本文档基于URL访问记录功能的Compose实现，总结Compose的核心概念和使用方法。

## 文档内容概览
章节 内容 一、Compose是什么 与传统XML对比，优势说明 二、核心概念 @Composable、State、remember、Modifier 三、常用布局 Column、Row、Box、LazyColumn 四、状态管理 本地状态、ViewModel状态 五、条件渲染 if、when、AnimatedVisibility 六、常用组件 Scaffold、TopAppBar、AlertDialog、DropdownMenu、TextField 七、副作用 LaunchedEffect、rememberCoroutineScope、DisposableEffect 八、主题和样式 MaterialTheme、颜色系统 九、性能优化 key、remember、derivedStateOf 十、对比总结 View系统 vs Compose 对照表 十一、学习建议 学习路径建议 十二、参考资源 官方文档链接


## 一、Compose是什么？

**简单理解**：Compose是Google推出的新一代UI框架，用**Kotlin代码**写界面，而不是用**XML布局文件**。
用的是Material3组件 - Google提供了丰富的现成组件，如Scaffold、TopAppBar、AlertDialog、DropdownMenu、TextField等。
### 传统方式 vs Compose

```
传统方式（XML）:
┌─────────────────────────────────────┐
│  activity_url_record.xml            │
│  ┌─────────────────────────────────┐│
│  │ <LinearLayout>                  ││
│  │   <TextView text="标题" />      ││
│  │   <RecyclerView ... />          ││
│  │ </LinearLayout>                 ││
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘

Compose方式（Kotlin代码）:
┌─────────────────────────────────────┐
│  UrlRecordScreen.kt                 │
│  ┌─────────────────────────────────┐│
│  │ Column {                        ││
│  │   Text("标题")                  ││
│  │   LazyColumn { ... }            ││
│  │ }                               ││
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

### Compose的优势

| 优势 | 说明 |
|------|------|
| **代码简洁** | 不需要XML文件，减少模板代码 |
| **响应式UI** | 状态变化时UI自动更新 |
| **易于复用** | 组件就是函数，可以轻松复用 |
| **类型安全** | 编译时检查，减少运行时错误 |
| **预览功能** | 可以在IDE中实时预览UI |

---

## 二、核心概念

### 1. @Composable - 可组合函数

`@Composable` 注解标记的函数就是UI组件。

```kotlin
// 这就是一个可组合函数，类似于自定义View
@Composable
fun MyButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(text)
    }
}

// 使用这个组件
@Composable
fun MyScreen() {
    MyButton(
        text = "点击我",
        onClick = { /* 处理点击 */ }
    )
}
```

**要点**：
- 可组合函数可以像搭积木一样组合
- 函数名首字母大写（PascalCase）
- 没有返回值（返回Unit）
- 可以接收其他可组合函数作为参数

### 2. State - 状态

State是Compose的核心，当状态变化时，UI会自动更新（这叫"重组"）。

```kotlin
@Composable
fun Counter() {
    // ❌ 错误：普通变量，改变后UI不会更新
    // var count = 0
    
    // ✅ 正确：状态变量，改变后UI自动更新
    var count by mutableStateOf(0)
    
    Column {
        Text("点击次数: $count")
        Button(onClick = { count++ }) {
            Text("增加")
        }
    }
}
```

**理解**：
- `mutableStateOf(0)` 创建一个可变状态
- `by` 是属性委托，让我们可以直接用 `count` 而不是 `count.value`
- 当 `count` 变化时，使用它的 `Text` 会自动刷新

### 3. remember - 记住状态

Compose会频繁"重组"（重新执行函数），`remember` 让状态在重组时保持不变。

```kotlin
@Composable
fun MyScreen() {
    // ✅ remember 保存状态，在重组时不会丢失
    var showSearch by remember { mutableStateOf(false) }
    
    // ❌ 如果不用remember，每次重组都会重置为false
    // var showSearch = mutableStateOf(false)
}
```

**对比**：

| 方式 | 重组时 | 说明 |
|------|--------|------|
| `var x = mutableStateOf(0)` | 重置为初始值 | ❌ 不推荐 |
| `var x by remember { mutableStateOf(0) }` | 保持当前值 | ✅ 推荐 |

### 4. Modifier - 修饰符

Modifier用于设置组件的大小、位置、样式等属性。

```kotlin
Text(
    text = "Hello",
    modifier = Modifier
        .fillMaxWidth()         // 填满宽度
        .padding(16.dp)         // 内边距
        .background(Color.Blue) // 背景色
        .clickable { }          // 可点击
)
```

**常用Modifier**：

| Modifier | 作用 | 示例 |
|----------|------|------|
| `fillMaxWidth()` | 填满宽度 | `Modifier.fillMaxWidth()` |
| `fillMaxSize()` | 填满父容器 | `Modifier.fillMaxSize()` |
| `padding()` | 内边距 | `Modifier.padding(16.dp)` |
| `size()` | 固定大小 | `Modifier.size(64.dp)` |
| `background()` | 背景色 | `Modifier.background(Color.Red)` |
| `clickable()` | 可点击 | `Modifier.clickable { }` |
| `weight()` | 权重 | `Modifier.weight(1f)` |

**注意**：Modifier的调用顺序很重要！

```kotlin
// 先padding再background：背景不包含padding区域
Modifier.padding(16.dp).background(Color.Blue)

// 先background再padding：背景包含padding区域
Modifier.background(Color.Blue).padding(16.dp)
```

---

## 三、常用布局

### 1. Column - 垂直布局

子元素从上到下排列，类似 `LinearLayout` 的 `vertical`。

```kotlin
Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,  // 水平居中
    verticalArrangement = Arrangement.Center             // 垂直居中
) {
    Text("第一行")
    Text("第二行")
    Text("第三行")
}
```

**效果**：
```
┌─────────────┐
│   第一行    │
│   第二行    │
│   第三行    │
└─────────────┘
```

### 2. Row - 水平布局

子元素从左到右排列，类似 `LinearLayout` 的 `horizontal`。

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically  // 垂直居中
) {
    Text("左")
    Text("中")
    Text("右")
}
```

**效果**：
```
┌─────────────┐
│  左  中  右 │
└─────────────┘
```

### 3. Box - 叠加布局

子元素可以叠加显示，类似 `FrameLayout`。

```kotlin
Box(modifier = Modifier.size(200.dp)) {
    Image(                      // 底层
        painter = ...,
        contentDescription = null
    )
    Text(                       // 上层，覆盖在图片上
        text = "水印",
        modifier = Modifier.align(Alignment.BottomEnd)
    )
}
```

### 4. LazyColumn - 高效列表

类似 `RecyclerView`，只渲染可见项。

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp)  // 列表内边距
) {
    // 单个静态项
    item {
        Text("标题", style = MaterialTheme.typography.titleLarge)
    }
    
    // 动态列表项
    items(records, key = { it.id }) { record ->
        RecordItem(record)
    }
    
    // 多种类型的项
    items(headers) { header ->
        HeaderItem(header)
    }
    items(contents) { content ->
        ContentItem(content)
    }
}
```

**key参数的作用**：
- 唯一标识列表项
- 提高列表更新效率（避免不必要的重组）
- 类似 `RecyclerView` 的 `stableId`

---

## 四、状态管理

### 1. 本地状态

使用 `remember` + `mutableStateOf`，只在当前组件内使用。

```kotlin
@Composable
fun MyScreen() {
    var count by remember { mutableStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }
    
    if (showSearch) {
        SearchBar()
    }
    
    Button(onClick = { count++ }) {
        Text("点击: $count")
    }
}
```

### 2. ViewModel状态

使用 `collectAsState()` 将 `StateFlow` 转换为 Compose 的 `State`。

```kotlin
@Composable
fun UrlRecordScreen(
    viewModel: UrlRecordViewModel = viewModel()
) {
    // 从ViewModel获取状态
    val uiState by viewModel.uiState.collectAsState()
    val domains by viewModel.domains.collectAsState()
    val recordCount by viewModel.recordCount.collectAsState()
    
    // 状态变化时，UI自动更新
    when (val state = uiState) {
        is Loading -> CircularProgressIndicator()
        is Success -> Text(state.data)
        is Error -> Text("错误: ${state.message}")
    }
}
```

**ViewModel中的状态定义**：

```kotlin
class UrlRecordViewModel : BaseViewModel() {
    // 使用StateFlow暴露状态
    private val _uiState = MutableStateFlow<UIState>(UIState.Loading)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()
    
    // 更新状态
    fun updateState(newState: UIState) {
        _uiState.value = newState
    }
}
```

---

## 五、条件渲染

### 1. if 条件渲染

```kotlin
if (isLoading) {
    CircularProgressIndicator()  // 显示加载动画
} else {
    Text(data)  // 显示数据
}
```

### 2. when 条件渲染

```kotlin
when (val state = uiState) {
    is UrlRecordUIState.Loading -> {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
    is UrlRecordUIState.Empty -> {
        Text("暂无数据")
    }
    is UrlRecordUIState.Error -> {
        Text("错误: ${state.message}")
    }
    is UrlRecordUIState.Success -> {
        LazyColumn {
            items(state.records) { record ->
                RecordItem(record)
            }
        }
    }
}
```

### 3. AnimatedVisibility - 动画显示/隐藏

```kotlin
var showSearch by remember { mutableStateOf(false) }

Column {
    Button(onClick = { showSearch = !showSearch }) {
        Text("切换搜索框")
    }
    
    AnimatedVisibility(visible = showSearch) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索...") }
        )
    }
}
```

---

## 六、常用组件

### 1. Scaffold - 页面骨架

Material3 的基础页面模板，提供了 `topBar`、`bottomBar`、`floatingActionButton` 等预定义区域。

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text("标题") },
            navigationIcon = {
                IconButton(onClick = { }) {
                    Icon(Icons.Default.ArrowBack, "返回")
                }
            },
            actions = {
                IconButton(onClick = { }) {
                    Icon(Icons.Default.Search, "搜索")
                }
            }
        )
    },
    bottomBar = {
        NavigationBar {
            // 底部导航栏
        }
    },
    floatingActionButton = {
        FloatingActionButton(onClick = { }) {
            Icon(Icons.Default.Add, "添加")
        }
    }
) { paddingValues ->
    // 主内容区域
    // paddingValues 避免内容被topBar/bottomBar遮挡
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // 内容...
    }
}
```

### 2. TopAppBar - 标题栏

```kotlin
TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.White,
        titleContentColor = Color.Black
    ),
    title = {
        Column {
            Text("标题", style = MaterialTheme.typography.titleLarge)
            Text("副标题", style = MaterialTheme.typography.labelMedium)
        }
    },
    navigationIcon = {
        IconButton(onClick = onBackClick) {
            Icon(Icons.Default.ArrowBack, "返回")
        }
    },
    actions = {
        IconButton(onClick = { }) {
            Icon(Icons.Default.Search, "搜索")
        }
        IconButton(onClick = { }) {
            Icon(Icons.Default.MoreVert, "更多")
        }
    }
)
```

### 3. AlertDialog - 对话框

```kotlin
var showDialog by remember { mutableStateOf(false) }

if (showDialog) {
    AlertDialog(
        onDismissRequest = { showDialog = false },  // 点击外部关闭
        title = { Text("确认删除") },
        text = { Text("确定要删除这条记录吗？") },
        confirmButton = {
            TextButton(onClick = {
                // 确认操作
                showDialog = false
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) {
                Text("取消")
            }
        }
    )
}
```

### 4. DropdownMenu - 下拉菜单

```kotlin
var showMenu by remember { mutableStateOf(false) }

Box {
    IconButton(onClick = { showMenu = true }) {
        Icon(Icons.Default.MoreVert, "更多")
    }
    
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("编辑") },
            onClick = {
                // 处理点击
                showMenu = false
            },
            leadingIcon = {
                Icon(Icons.Default.Edit, null)
            }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("删除") },
            onClick = {
                // 处理点击
                showMenu = false
            }
        )
    }
}
```

### 5. OutlinedTextField - 输入框

```kotlin
var text by remember { mutableStateOf("") }

OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    label = { Text("请输入") },
    placeholder = { Text("搜索...") },
    leadingIcon = {
        Icon(Icons.Default.Search, null)
    },
    trailingIcon = {
        if (text.isNotEmpty()) {
            IconButton(onClick = { text = "" }) {
                Icon(Icons.Default.Clear, "清除")
            }
        }
    },
    singleLine = true,
    colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Blue,
        unfocusedBorderColor = Color.Gray
    )
)
```

---

## 七、副作用

### 1. LaunchedEffect - 响应式副作用

当key变化时执行，常用于数据加载。

```kotlin
// 当userId变化时，重新加载用户数据
LaunchedEffect(userId) {
    viewModel.loadUser(userId)
}

// 多个key
LaunchedEffect(searchQuery, currentDomain) {
    viewModel.search(searchQuery, currentDomain)
}
```

### 2. rememberCoroutineScope - 协程作用域

用于在事件处理中调用suspend函数。

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel = viewModel()) {
    val coroutineScope = rememberCoroutineScope()
    
    Button(onClick = {
        // 在协程中调用suspend函数
        coroutineScope.launch {
            viewModel.deleteAll()
        }
    }) {
        Text("删除全部")
    }
}
```

### 3. DisposableEffect - 带清理的副作用

```kotlin
DisposableEffect(lifecycle) {
    // 注册监听
    val observer = MyLifecycleObserver()
    lifecycle.addObserver(observer)
    
    // 组件销毁时清理
    onDispose {
        lifecycle.removeObserver(observer)
    }
}
```

---

## 八、主题和样式

### 1. MaterialTheme

```kotlin
MaterialTheme(
    colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme(),
    typography = Typography(),
    shapes = Shapes()
) {
    // 应用主题的UI
    MyScreen()
}
```

### 2. 使用主题颜色

```kotlin
Text(
    text = "Hello",
    color = MaterialTheme.colorScheme.primary,          // 主色
    backgroundColor = MaterialTheme.colorScheme.surface, // 表面色
    style = MaterialTheme.typography.titleLarge         // 文字样式
)
```

### 3. 常用颜色

| 颜色属性 | 用途 |
|----------|------|
| `primary` | 主色，用于按钮、重要元素 |
| `secondary` | 次要色 |
| `surface` | 表面色，用于卡片背景 |
| `background` | 背景色 |
| `error` | 错误色，用于错误提示 |
| `onPrimary` | 主色上的文字颜色 |
| `onSurface` | 表面色上的文字颜色 |

---

## 九、性能优化

### 1. 使用 key 优化列表

```kotlin
LazyColumn {
    items(records, key = { it.id }) { record ->
        RecordItem(record)
    }
}
```

### 2. 使用 remember 缓存计算结果

```kotlin
@Composable
fun MyScreen(data: List<Item>) {
    // 缓存计算结果，避免每次重组都重新计算
    val sortedData = remember(data) {
        data.sortedBy { it.name }
    }
    
    LazyColumn {
        items(sortedData) { item ->
            ItemRow(item)
        }
    }
}
```

### 3. 使用 derivedStateOf 避免不必要的重组

```kotlin
@Composable
fun MyScreen(list: List<Item>) {
    // 只有当过滤结果变化时才重组
    val filteredList by remember {
        derivedStateOf {
            list.filter { it.isActive }
        }
    }
    
    LazyColumn {
        items(filteredList) { item ->
            ItemRow(item)
        }
    }
}
```

---

## 十、对比总结

| 概念 | View系统 | Compose |
|------|----------|---------|
| UI定义 | XML文件 | Kotlin函数 |
| 自定义组件 | 继承View/ViewGroup | @Composable函数 |
| 列表 | RecyclerView + Adapter | LazyColumn/LazyRow |
| 状态更新 | notifyDataSetChanged() | 自动重组 |
| 布局 | LinearLayout/RelativeLayout/ConstraintLayout | Column/Row/Box/ConstraintLayout |
| 点击事件 | setOnClickListener | clickable修饰符 |
| 内边距 | android:padding="16dp" | Modifier.padding(16.dp) |
| 宽高 | android:layout_width="match_parent" | Modifier.fillMaxWidth() |
| 可见性 | android:visibility="gone" | if条件渲染 |
| 背景 | android:background="#FF0000" | Modifier.background(Color.Red) |

---

## 十一、学习建议

1. **先理解状态** - State是Compose的核心，理解了状态就理解了Compose
2. **多用预览** - `@Preview` 注解可以实时预览UI，提高开发效率
3. **参考Material3组件** - Google提供了丰富的现成组件，不要重复造轮子
4. **从简单开始** - 先写简单的页面，逐步增加复杂度
5. **多看官方文档** - [Compose官方文档](https://developer.android.com/jetpack/compose)

---

## 十二、参考资源

- [Jetpack Compose 官方文档](https://developer.android.com/jetpack/compose)
- [Material3 组件列表](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)
- [Compose UI 组件列表](https://developer.android.com/reference/kotlin/androidx/compose/ui/package-summary)
- [Compose 性能优化](https://developer.android.com/jetpack/compose/performance)
