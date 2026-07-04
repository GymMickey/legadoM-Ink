# 开发学习记录 - URL访问记录功能

> 本文档记录了开发URL访问记录功能过程中涉及的所有知识点，适合Kotlin和Android开发初学者学习。

***

## 📚 Kotlin语法学习

### 1. data class（数据类）

**代码示例**：

```kotlin
@Entity(tableName = "url_records")
data class UrlRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val domain: String,
    val method: String,
    val timestamp: Long = System.currentTimeMillis()
)
```

**知识点**：

- `data class` 自动生成 `equals()`、`hashCode()`、`toString()`、`copy()` 方法
- 主构造函数必须至少有一个参数
- 参数必须用 `val` 或 `var` 声明

**为什么用数据类**：
URL记录是一个纯数据容器，不需要复杂的业务逻辑，使用 `data class` 可以减少大量样板代码。

***

### 2. sealed class（密封类）

**代码示例**：

```kotlin
sealed class UrlRecordUIState {
    object Loading : UrlRecordUIState()
    data class Success(val records: List<UrlRecord>) : UrlRecordUIState()
    data class Error(val message: String) : UrlRecordUIState()
    object Empty : UrlRecordUIState()
}
```

**知识点**：

- 密封类的所有子类必须在同一文件中定义
- 配合 `when` 表达式可以穷举所有情况，编译器会检查完整性
- `object` 表示单例，`data class` 表示有数据的状态

**为什么用密封类**：
UI状态是有限的几种（加载中、成功、错误、空数据），用密封类可以确保：

1. 不会遗漏任何状态的处理
2. 类型安全，每种状态可以携带不同的数据

**对比 if-else**：

```kotlin
// ❌ 不好的写法 - 可能遗漏状态
var isLoading = false
var isError = false
var errorMessage: String? = null
var data: List<UrlRecord>? = null

// ✅ 好的写法 - 状态完整且类型安全
when (state) {
    is UrlRecordUIState.Loading -> { }
    is UrlRecordUIState.Success -> { }
    is UrlRecordUIState.Error -> { }
    is UrlRecordUIState.Empty -> { }
    // 编译器会提示是否覆盖所有情况
}
```

***

### 3. StateFlow（状态流）

**代码示例**：

```kotlin
// ViewModel中
private val _uiState = MutableStateFlow<UrlRecordUIState>(UrlRecordUIState.Loading)
val uiState: StateFlow<UrlRecordUIState> = _uiState.asStateFlow()

// 修改状态
_uiState.value = UrlRecordUIState.Success(records)

// Activity中观察
lifecycleScope.launch {
    viewModel.uiState.collectLatest { state ->
        // 自动收到状态变化通知
    }
}
```

**知识点**：

- `MutableStateFlow`：可变状态流，内部可修改
- `asStateFlow()`：转换为只读的 `StateFlow`，外部只能观察
- `value`：当前状态值
- `collect`：收集状态变化

**为什么这样写**：
遵循**单向数据流**原则：

```
ViewModel  ──(StateFlow)──→  Activity
    ↑                           │
    └──────(用户事件)───────────┘
```

***

### 4. Flow（数据流）

**代码示例**：

```kotlin
// DAO中定义
@Query("SELECT * FROM url_records ORDER BY timestamp DESC")
fun flowAll(): Flow<List<UrlRecord>>

// ViewModel中收集
appDb.urlRecordDao.flowAll()
    .onStart { _uiState.value = UrlRecordUIState.Loading }
    .catch { e -> _uiState.value = UrlRecordUIState.Error(e.message ?: "加载失败") }
    .collect { records -> _uiState.value = UrlRecordUIState.Success(records) }
```

**知识点**：

- `Flow` 是冷流，只有被收集时才执行
- `onStart`：开始前的操作
- `catch`：捕获异常
- `collect`：收集数据

**Flow vs List**：

| 特性   | List     | Flow       |
| ---- | -------- | ---------- |
| 执行时机 | 立即执行     | 被收集时执行     |
| 数据更新 | 一次性，不会更新 | 持续观察，自动更新  |
| 适用场景 | 静态数据     | 动态数据、数据库观察 |

***

### 5. object（单例对象）

**代码示例**：

```kotlin
object UrlRecordInterceptor : Interceptor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun intercept(chain: Interceptor.Chain): Response {
        // ...
    }
    
    fun cancelAll() {
        scope.cancel()
    }
}
```

**知识点**：

- `object` 声明的类只有一个实例
- 不需要创建对象，直接通过类名访问
- 适合工具类、拦截器等单例场景

**访问方式**：

```kotlin
// 不需要创建实例
UrlRecordInterceptor.intercept(chain)
UrlRecordInterceptor.cancelAll()
```

***

### 6. 协程（Coroutine）

**代码示例**：

```kotlin
// 异步数据库操作
suspend fun clearAll(): Int {
    return withContext(Dispatchers.IO) {
        appDb.urlRecordDao.deleteAll()
    }
}

// 在协程中调用
lifecycleScope.launch {
    val deletedCount = viewModel.clearAll()
    toastOnUi("已清除 ${deletedCount} 条记录")
}
```

**知识点**：

- `suspend`：挂起函数，可以在协程中暂停执行
- `withContext(Dispatchers.IO)`：切换到IO线程执行
- `lifecycleScope.launch`：在生命周期作用域启动协程

**为什么用协程**：

- 数据库操作是耗时操作，不能在主线程执行
- 协程可以优雅地处理异步，避免回调地狱

***

### 7. 空安全（Null Safety）

**代码示例**：

```kotlin
// 可空类型
var currentDomain: String? = null

// 安全调用
record.sourceName?.let { 
    tvSource.text = it 
}

// Elvis操作符
val message = e.message ?: "加载失败"

// 非空断言（谨慎使用）
val value = nullableValue!!
```

**知识点**：

| 操作符   | 名称       | 说明                |
| ----- | -------- | ----------------- |
| `?`   | 可空类型     | `String?` 可以为null |
| `?.`  | 安全调用     | 如果为null则不执行       |
| `?:`  | Elvis操作符 | 如果为null则使用默认值     |
| `!!`  | 非空断言     | 断言不为null，为null抛异常 |
| `let` | 作用域函数    | 非空时执行代码块          |

***

### 8. 属性委托（Property Delegation）

**代码示例**：

```kotlin
// ViewBinding委托
override val binding by viewBinding(ActivityUrlRecordBinding::inflate)

// ViewModel委托
override val viewModel by viewModels<UrlRecordViewModel>()

// 懒加载
private val adapter by lazy { UrlRecordAdapter() }
```

**知识点**：

- `by` 关键字表示属性委托
- `viewBinding`：自动处理视图绑定
- `viewModels`：自动创建和管理ViewModel
- `lazy`：首次访问时才初始化

***

## 🏗️ Android架构学习

### 1. MVVM架构模式

**架构图**：

```
┌─────────────────────────────────────────────────────────────┐
│                      View Layer                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  UrlRecordActivity                                       ││
│  │  - 观察 ViewModel 的状态                                 ││
│  │  - 渲染 UI                                               ││
│  │  - 处理用户交互                                          ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              │ 观察 StateFlow
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    ViewModel Layer                           │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  UrlRecordViewModel                                      ││
│  │  - 管理 UI 状态 (UIState)                                ││
│  │  - 处理业务逻辑                                          ││
│  │  - 协调数据层                                            ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              │ 查询数据
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Model Layer                             │
│  ┌──────────────────────┐    ┌────────────────────────────┐ │
│  │  UrlRecordDao        │    │  AppDatabase               │ │
│  │  - 数据访问接口      │    │  - Room 数据库             │ │
│  └──────────────────────┘    └────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**职责划分**：

| 层级        | 职责        | 不应该做的事            |
| --------- | --------- | ----------------- |
| Activity  | UI渲染、用户交互 | 业务逻辑、数据库操作        |
| ViewModel | 状态管理、业务逻辑 | 持有Activity引用、UI操作 |
| Model     | 数据存储、查询   | 业务逻辑、UI相关         |

***

### 2. 单向数据流

**数据流向**：

```
用户操作 → ViewModel处理 → 更新StateFlow → Activity收到通知 → 更新UI
```

**代码体现**：

```kotlin
// 用户点击清除按钮
// 1. Activity调用ViewModel
viewModel.clearAll()

// 2. ViewModel执行操作并更新状态
suspend fun clearAll(): Int {
    return withContext(Dispatchers.IO) {
        val count = appDb.urlRecordDao.deleteAll()
        // 数据库变化 → Flow自动通知 → UIState更新
        count
    }
}

// 3. Activity自动收到状态变化
lifecycleScope.launch {
    viewModel.uiState.collectLatest { state ->
        // UI自动更新
    }
}
```

***

### 3. 生命周期感知

**代码示例**：

```kotlin
// Activity中
lifecycleScope.launch {
    viewModel.uiState.collectLatest { state ->
        // Activity销毁时自动取消
    }
}

// ViewModel中
class UrlRecordViewModel(application: Application) : BaseViewModel(application) {
    // ViewModel会在Activity销毁时自动清理
}
```

**知识点**：

- `lifecycleScope`：绑定Activity生命周期
- `viewModelScope`：绑定ViewModel生命周期
- Activity销毁时自动取消协程，避免内存泄漏

***

## 🎨 UI开发学习

### 1. ViewBinding（视图绑定）

**使用步骤**：

1. **在build.gradle中启用**：

```gradle
android {
    buildFeatures {
        viewBinding true
    }
}
```

1. **在Activity中使用**：

```kotlin
class UrlRecordActivity : VMBaseActivity<ActivityUrlRecordBinding, UrlRecordViewModel>() {
    
    // 方式1：使用委托（推荐）
    override val binding by viewBinding(ActivityUrlRecordBinding::inflate)
    
    // 方式2：手动创建
    private lateinit var binding: ActivityUrlRecordBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityUrlRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
    
    // 使用
    binding.recyclerView.adapter = adapter
    binding.tvEmpty.visibility = View.VISIBLE
}
```

**对比 findViewById**：

```kotlin
// ❌ 旧方式 - 类型不安全，容易出错
val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)

// ✅ 新方式 - 类型安全，自动补全
binding.recyclerView.adapter = adapter
```

***

### 2. RecyclerView + Adapter

**Adapter实现**：

```kotlin
class UrlRecordAdapter : RecyclerView.Adapter<UrlRecordAdapter.ViewHolder>() {

    private var items: List<UrlRecord> = emptyList()

    // 设置数据
    fun setItems(newItems: List<UrlRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    // 创建ViewHolder
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUrlRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    // 绑定数据
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    // 数据数量
    override fun getItemCount(): Int = items.size

    // ViewHolder内部类
    inner class ViewHolder(private val binding: ItemUrlRecordBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(record: UrlRecord) {
            binding.tvMethod.text = record.method
            binding.tvDomain.text = record.domain
            // ...
        }
    }
}
```

**RecyclerView四要素**：

1. **Adapter**：数据和视图的桥梁
2. **ViewHolder**：持有视图引用，提高性能
3. **LayoutManager**：布局方式（LinearLayoutManager、GridLayoutManager）
4. **ItemDecoration**：列表项装饰（分割线）

***

### 3. SearchView搜索

**实现步骤**：

1. **实现接口**：

```kotlin
class UrlRecordActivity : ..., SearchView.OnQueryTextListener {
```

1. **设置监听**：

```kotlin
searchView.setOnQueryTextListener(this)
```

1. **实现回调**：

```kotlin
// 输入内容变化时触发
override fun onQueryTextChange(newText: String?): Boolean {
    viewModel.setSearchQuery(newText)
    return false  // false表示不消费事件
}

// 提交搜索时触发（按回车）
override fun onQueryTextSubmit(query: String?): Boolean {
    return false
}
```

***

### 4. Menu菜单系统

**定义菜单**：

```xml
<!-- res/menu/url_record.xml -->
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/menu_filter"
        android:title="按域名筛选">
        <menu>
            <item android:id="@+id/menu_filter_all" android:title="全部" />
            <group android:id="@+id/menu_domain_group" android:checkableBehavior="single" />
        </menu>
    </item>
</menu>
```

**在Activity中使用**：

```kotlin
// 加载菜单
override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.url_record, menu)
    return true
}

// 动态添加菜单项
domainMenu?.transaction { menu ->
    menu.removeGroup(R.id.menu_domain_group)
    domains.forEach { domain ->
        menu.add(R.id.menu_domain_group, Menu.NONE, Menu.NONE, domain)
    }
}

// 处理点击
override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
        R.id.menu_filter_all -> { }
    }
    return super.onCompatOptionsItemSelected(item)
}
```

***

## 💾 数据库学习

### 1. Room数据库架构

**三大组件**：

```
┌─────────────────────────────────────────────────────────────┐
│                      AppDatabase                             │
│  @Database(version = 93, entities = [..., UrlRecord::class])│
│  - 数据库配置                                                │
│  - 版本管理                                                  │
│  - DAO注册                                                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ 提供
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       UrlRecordDao                           │
│  @Dao                                                        │
│  - 数据访问接口                                              │
│  - SQL查询方法                                               │
│  - 增删改查操作                                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ 操作
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       UrlRecord                              │
│  @Entity(tableName = "url_records")                         │
│  - 表结构定义                                                │
│  - 字段映射                                                  │
│  - 索引配置                                                  │
└─────────────────────────────────────────────────────────────┘
```

***

### 2. Entity实体类

**完整定义**：

```kotlin
@Entity(
    tableName = "url_records",           // 表名
    indices = [                          // 索引，加速查询
        Index(value = ["timestamp"]),
        Index(value = ["domain"])
    ]
)
data class UrlRecord(
    @PrimaryKey(autoGenerate = true)    // 主键，自增
    val id: Long = 0,
    
    val url: String,                     // URL地址
    val domain: String,                  // 域名
    val method: String,                  // HTTP方法
    val sourceName: String? = null,      // 来源名称（可空）
    val timestamp: Long = System.currentTimeMillis(),  // 时间戳
    val responseCode: Int = 0,           // 响应码
    val duration: Long = 0               // 耗时
)
```

**注解说明**：

| 注解            | 说明   | 示例                                   |
| ------------- | ---- | ------------------------------------ |
| `@Entity`     | 定义表  | `@Entity(tableName = "url_records")` |
| `@PrimaryKey` | 定义主键 | `@PrimaryKey(autoGenerate = true)`   |
| `@ColumnInfo` | 定义列  | `@ColumnInfo(name = "url")`          |
| `@Ignore`     | 忽略字段 | `@Ignore val temp: String`           |
| `@Index`      | 定义索引 | `Index(value = ["domain"])`          |

***

### 3. DAO数据访问对象

**查询方法**：

```kotlin
@Dao
interface UrlRecordDao {

    // 查询所有（Flow方式，持续观察）
    @Query("SELECT * FROM url_records ORDER BY timestamp DESC")
    fun flowAll(): Flow<List<UrlRecord>>

    // 条件查询
    @Query("SELECT * FROM url_records WHERE domain = :domain ORDER BY timestamp DESC")
    fun flowByDomain(domain: String): Flow<List<UrlRecord>>

    // 模糊搜索
    @Query("SELECT * FROM url_records WHERE url LIKE '%' || :keyword || '%' ORDER BY timestamp DESC")
    fun flowSearch(keyword: String): Flow<List<UrlRecord>>

    // 插入
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg records: UrlRecord)

    // 删除所有
    @Query("DELETE FROM url_records")
    fun deleteAll(): Int  // 返回删除数量

    // 删除旧记录
    @Query("DELETE FROM url_records WHERE timestamp < :timestamp")
    fun deleteOldRecords(timestamp: Long): Int

    // 计数
    @Query("SELECT COUNT(*) FROM url_records")
    fun getCount(): Int
}
```

**返回类型选择**：

| 返回类型            | 说明    | 适用场景      |
| --------------- | ----- | --------- |
| `List<T>`       | 一次性查询 | 不需要更新的数据  |
| `Flow<List<T>>` | 持续观察  | 需要实时更新的数据 |
| `Flow<T>`       | 单条观察  | 单个数据变化    |
| `Int`           | 影响行数  | 删除、更新操作   |

***

### 4. 数据库迁移

**版本升级**：

```kotlin
@Database(
    version = 93,  // 版本号+1
    entities = [..., UrlRecord::class],  // 新增Entity
    autoMigrations = [
        // ...,
        AutoMigration(from = 92, to = 93)  // 自动迁移
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract val urlRecordDao: UrlRecordDao  // 新增DAO
}
```

**迁移类型**：

| 类型              | 说明   | 使用场景    |
| --------------- | ---- | ------- |
| `AutoMigration` | 自动迁移 | 新增表、新增列 |
| `Migration`     | 手动迁移 | 复杂结构变化  |

***

## 🌐 网络请求学习

### 1. OkHttp拦截器

**拦截器原理**：

```
请求 → 拦截器1 → 拦截器2 → ... → 服务器
                    ↓
                记录请求信息
                    ↓
响应 ← 拦截器1 ← 拦截器2 ← ... ← 服务器
```

**实现拦截器**：

```kotlin
object UrlRecordInterceptor : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        
        // 执行请求
        val response = chain.proceed(request)
        
        // 记录信息
        val record = UrlRecord(
            url = request.url.toString(),
            domain = request.url.host,
            method = request.method,
            responseCode = response.code,
            duration = System.currentTimeMillis() - startTime
        )
        
        // 异步保存
        scope.launch {
            appDb.urlRecordDao.insert(record)
        }
        
        return response
    }
}
```

**注册拦截器**：

```kotlin
val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(UrlRecordInterceptor)
    .build()
```

***

## 🔗 知识关联

| 本次学习         | 相关知识                     | 扩展阅读                    |
| ------------ | ------------------------ | ----------------------- |
| sealed class | enum class、when表达式       | Kotlin官方文档-密封类          |
| StateFlow    | LiveData、SharedFlow      | Android官方文档-StateFlow   |
| Room         | SQLite、GreenDAO          | Android官方文档-Room        |
| Flow         | RxJava、LiveData          | Kotlin官方文档-Flow         |
| 协程           | Thread、RxJava            | Kotlin官方文档-协程           |
| ViewBinding  | DataBinding、findViewById | Android官方文档-ViewBinding |
| MVVM         | MVP、MVC                  | Android架构指南             |

***

## 💡 实践建议

### 1. 学习路径

```
Kotlin基础 → 协程/Flow → Room数据库 → MVVM架构 → UI组件
```

### 2. 调试技巧

```kotlin
// 使用Log调试Flow
flow
    .onStart { Log.d("TAG", "开始加载") }
    .onEach { Log.d("TAG", "数据: $it") }
    .catch { Log.e("TAG", "错误: $it") }
    .collect()
```

### 3. 常见错误

| 错误       | 原因        | 解决方案                                   |
| -------- | --------- | -------------------------------------- |
| 主线程访问数据库 | Room默认不允许 | 使用 `withContext(Dispatchers.IO)`       |
| Flow不更新  | 忘记collect | 在协程中调用 `collect`                       |
| 内存泄漏     | 协程未取消     | 使用 `lifecycleScope` 或 `viewModelScope` |

### 4. 性能优化

- 使用 `indices` 创建数据库索引
- 使用 `Flow` 替代多次查询
- 使用 `ViewHolder` 复用视图
- 使用 `suspend` 函数避免阻塞

***

## 📁 相关文件

| 文件                        | 说明    | 知识点                      |
| ------------------------- | ----- | ------------------------ |
| `UrlRecord.kt`            | 实体类   | data class、@Entity       |
| `UrlRecordDao.kt`         | 数据访问  | @Dao、Flow、@Query         |
| `UrlRecordUIState.kt`     | UI状态  | sealed class             |
| `UrlRecordViewModel.kt`   | 视图模型  | StateFlow、协程             |
| `UrlRecordActivity.kt`    | 界面    | ViewBinding、RecyclerView |
| `UrlRecordAdapter.kt`     | 列表适配器 | RecyclerView、ViewHolder  |
| `UrlRecordInterceptor.kt` | 网络拦截器 | OkHttp、object            |

