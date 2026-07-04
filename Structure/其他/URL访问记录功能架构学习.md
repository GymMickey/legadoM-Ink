## 一、架构设计模式

### 1. MVVM 架构
```
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│     View        │ ───► │   ViewModel     │ ───► │     Model       │
│  (Activity)     │      │ (业务逻辑)       │      │  (数据层)       │
└─────────────────┘      └─────────────────┘      └─────────────────┘
```

**学习点**：
- **View (Activity)**：只负责UI展示和用户交互
- **ViewModel**：持有数据，处理业务逻辑，不持有View引用
- **Model (Repository/DAO)**：负责数据存储和读取

**代码示例**：
```kotlin
// Activity 中通过 viewModels 委托获取 ViewModel
override val viewModel by viewModels<UrlRecordViewModel>()

// ViewModel 中持有数据，Activity 直接使用
val allRecords = appDb.urlRecordDao.getAll()
```

---

## 二、Room 数据库

### 1. Entity（实体类）
```kotlin
@Entity(tableName = "url_records", indices = [Index(value = ["timestamp"])])
data class UrlRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    // ...
)
```

**学习点**：
- `@Entity`：标记这是一个数据库表
- `@PrimaryKey`：定义主键，`autoGenerate = true` 表示自增
- `indices`：创建索引，加速查询

### 2. DAO（数据访问对象）
```kotlin
@Dao
interface UrlRecordDao {
    @Query("SELECT * FROM url_records ORDER BY timestamp DESC")
    fun getAll(): List<UrlRecord>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg records: UrlRecord)
}
```

**学习点**：
- `@Dao`：标记这是一个数据访问接口
- `@Query`：自定义SQL查询
- `@Insert`：插入操作
- `@Delete`：删除操作

### 3. Database（数据库类）
```kotlin
@Database(
    version = 93,
    entities = [Book::class, UrlRecord::class, ...]
)
abstract class AppDatabase : RoomDatabase() {
    abstract val urlRecordDao: UrlRecordDao
}
```

**学习点**：
- `@Database`：定义数据库版本和包含的实体
- 版本升级时需要添加 `AutoMigration` 或手动迁移

---

## 三、OkHttp 拦截器

### 1. 拦截器原理
```
请求 ──► 拦截器1 ──► 拦截器2 ──► ... ──► 服务器
                    │
                    ▼
              记录URL信息
```

### 2. 自定义拦截器
```kotlin
object UrlRecordInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        
        try {
            val response = chain.proceed(request)  // 继续请求链
            return response
        } finally {
            val duration = System.currentTimeMillis() - startTime
            // 记录请求信息...
        }
    }
}
```

**学习点**：
- `chain.proceed(request)`：将请求传递给下一个拦截器
- `finally` 块：无论成功失败都会执行，适合做记录
- `object`：Kotlin 单例模式

### 3. 注册拦截器
```kotlin
OkHttpClient.Builder()
    .addInterceptor(UrlRecordInterceptor)  // 应用拦截器
    .build()
```

---

## 四、Kotlin 协程

### 1. 异步操作
```kotlin
// 在 ViewModel 中执行 IO 操作
fun clearAll() {
    execute {  // 在 IO 线程执行
        appDb.urlRecordDao.deleteAll()
    }
}

// 在 Activity 中切换线程
lifecycleScope.launch {
    val records = withContext(Dispatchers.IO) {
        viewModel.allRecords  // IO 线程读取
    }
    adapter.setItems(records)  // 主线程更新UI
}
```

**学习点**：
- `Dispatchers.IO`：IO 操作专用线程池
- `Dispatchers.Main`：主线程，用于更新UI
- `lifecycleScope`：与 Activity 生命周期绑定的协程作用域
- `withContext`：切换到指定线程执行

### 2. 协程作用域
```kotlin
// 拦截器中的协程作用域
private val scope = CoroutineScope(Dispatchers.IO)

scope.launch {
    appDb.urlRecordDao.insert(record)  // 异步写入，不阻塞请求
}
```

---

## 五、ViewBinding

### 1. 启用 ViewBinding
```kotlin
// Activity 中使用
override val binding by viewBinding(ActivityUrlRecordBinding::inflate)

// 使用 binding 访问视图
binding.recyclerView.adapter = adapter
```

**学习点**：
- 替代 `findViewById`，类型安全
- `by viewBinding(...)`：Kotlin 属性委托
- 布局文件名转换为 Binding 类名：`activity_url_record.xml` → `ActivityUrlRecordBinding`

---

## 六、RecyclerView 适配器

### 1. 基本结构
```kotlin
class UrlRecordAdapter : RecyclerView.Adapter<UrlRecordAdapter.ViewHolder>() {
    
    // 1. 创建 ViewHolder
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUrlRecordBinding.inflate(...)
        return ViewHolder(binding)
    }
    
    // 2. 绑定数据
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }
    
    // 3. 返回数量
    override fun getItemCount(): Int = items.size
    
    // ViewHolder 内部类
    inner class ViewHolder(private val binding: ItemUrlRecordBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        fun bind(record: UrlRecord) {
            binding.tvUrl.text = record.url
        }
    }
}
```

---

## 七、SharedPreferences 配置存储

### 1. 扩展函数方式
```kotlin
// 定义扩展函数
fun Context.putPrefBoolean(key: String, value: Boolean) =
    defaultSharedPreferences.edit { putBoolean(key, value) }

// 使用
appCtx.putPrefBoolean(PreferKey.recordUrl, enabled)
```

### 2. 配置监听
```kotlin
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {
    var recordUrl = appCtx.getPrefBoolean(PreferKey.recordUrl)
    
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.recordUrl -> recordUrl = appCtx.getPrefBoolean(PreferKey.recordUrl)
        }
    }
}
```

---

## 八、AndroidManifest.xml 注册

```xml
<activity
    android:name=".ui.urlRecord.UrlRecordActivity"
    android:configChanges="orientation|screenSize"
    android:hardwareAccelerated="true" />
```

**学习点**：
- `configChanges`：配置改变（如屏幕旋转）时不重建 Activity
- `hardwareAccelerated`：启用硬件加速

---

## 九、菜单系统

### 1. 菜单资源文件
```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/menu_record_switch"
        android:checkable="true"
        android:title="@string/record_url_switch" />
</menu>
```

### 2. Activity 中处理菜单
```kotlin
// 创建菜单
override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.url_record, menu)
    return true
}

// 处理点击
override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
        R.id.menu_record_switch -> { /* ... */ }
    }
    return true
}
```

---

## 十、学习建议

| 知识点 | 推荐学习顺序 | 难度 |
|--------|-------------|------|
| Kotlin 基础 | 1 | ⭐ |
| Activity 生命周期 | 2 | ⭐⭐ |
| RecyclerView | 3 | ⭐⭐ |
| Room 数据库 | 4 | ⭐⭐⭐ |
| 协程 | 5 | ⭐⭐⭐ |
| MVVM 架构 | 6 | ⭐⭐⭐ |
| OkHttp 拦截器 | 7 | ⭐⭐⭐⭐ |

这个功能是一个很好的综合练习项目，涵盖了 Android 开发的核心知识点！