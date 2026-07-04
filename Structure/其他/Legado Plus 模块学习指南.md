# Legado Plus 模块学习指南

> 本指南为每个核心模块提供详尽、生动的讲解，帮助你深入理解项目的各个部分。

---

## 📚 如何使用本指南

### 学习路径建议

```
1. 先阅读"快速理解"部分，建立整体概念
2. 再看"架构图解"，理解模块结构
3. 跟着"代码走读"阅读源码
4. 完成"实践任务"巩固理解
5. 尝试"进阶挑战"提升能力
```

### 模块依赖关系

```
┌─────────────────────────────────────────────────────┐
│                   应用层模块                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │ 阅读模块  │  │ 书源模块  │  │  RSS模块  │         │
│  └─────┬────┘  └─────┬────┘  └─────┬────┘         │
└────────┼─────────────┼─────────────┼───────────────┘
         │             │             │
         ▼             ▼             ▼
┌─────────────────────────────────────────────────────┐
│                   核心层模块                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │规则解析   │  │网络请求   │  │数据存储   │         │
│  │引擎      │  │模块      │  │模块      │         │
│  └──────────┘  └──────────┘  └──────────┘         │
└─────────────────────────────────────────────────────┘
```

---

## 🎯 模块一：应用启动模块

### 快速理解

想象一下，应用启动就像是一场音乐会的开场：

1. **App.kt** - 音乐厅经理，提前准备所有设施
2. **WelcomeActivity** - 迎宾员，引导观众入场
3. **MainActivity** - 主舞台，展示主要演出

### 架构图解

```
启动流程时序图：

用户点击图标
     │
     ▼
┌─────────────┐
│   App.kt    │ ──── 初始化全局配置
│  onCreate   │      • 主题配置
└─────────────┘      • 网络引擎
     │               • 数据库
     ▼               • 通知渠道
┌─────────────┐
│  Welcome    │ ──── 显示欢迎界面
│  Activity   │      • 检查权限
└─────────────┘      • 加载配置
     │
     ▼
┌─────────────┐
│   Main      │ ──── 展示主界面
│  Activity   │      • 书架
└─────────────┘      • 发现
                     • 订阅
                     • 我的
```

### 代码走读

#### 第一步：App.kt - 全局初始化

```kotlin
// 文件位置：app/src/main/java/io/legado/app/App.kt

class App : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // ① 崩溃处理 - 捕获全局异常
        CrashHandler.init(this)
        
        // ② 主题配置 - 根据时间自动切换日夜模式
        ThemeConfig.init(this)
        
        // ③ 生命周期监控 - 监听所有Activity生命周期
        LifecycleHelp.init(this)
        
        // ④ 网络引擎 - 预下载Cronet（Chromium网络栈）
        CronetUtils.preDownload()
        
        // ⑤ 通知渠道 - 创建下载、朗读、Web三个通知渠道
        createNotificationChannels()
        
        // ⑥ 数据库初始化 - Room数据库
        val db = AppDatabase.getInstance(this)
        
        // ⑦ JS引擎 - 初始化Rhino引擎
        initRhino()
    }
}
```

**关键点理解**：
- **Application** 是整个应用的上下文，最先创建，最后销毁
- 所有全局配置都在这里初始化
- 使用协程进行耗时初始化，避免阻塞启动

#### 第二步：WelcomeActivity - 欢迎页

```kotlin
// 文件位置：ui/welcome/WelcomeActivity.kt

class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {
    
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // ① 延迟跳转，展示欢迎界面
        lifecycleScope.launch {
            delay(500) // 延迟500毫秒
            
            // ② 检查是否需要直接进入阅读
            if (AppConfig.directIntoRead) {
                startService<ReadBookActivity>()
            }
            
            // ③ 跳转到主界面
            startActivity<MainActivity>()
            
            // ④ 关闭欢迎页
            finish()
        }
    }
}
```

**关键点理解**：
- WelcomeActivity 是 LAUNCHER Activity（在 AndroidManifest.xml 中配置）
- 使用协程延迟跳转，不阻塞主线程
- 可以根据配置决定是否直接进入阅读

#### 第三步：MainActivity - 主界面

```kotlin
// 文件位置：ui/main/MainActivity.kt

class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>() {
    
    private val fragments = listOf(
        BookshelfFragment(),    // 书架
        ExploreFragment(),      // 发现
        RssFragment(),          // 订阅
        MyFragment()            // 我的
    )
    
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // ① 设置ViewPager适配器
        binding.viewPager.adapter = FragmentAdapter(fragments)
        
        // ② 绑定底部导航
        binding.bottomNav.setupWithViewPager(binding.viewPager)
        
        // ③ 处理导航事件
        binding.bottomNav.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_bookshelf -> binding.viewPager.currentItem = 0
                R.id.menu_explore -> binding.viewPager.currentItem = 1
                R.id.menu_rss -> binding.viewPager.currentItem = 2
                R.id.menu_my -> binding.viewPager.currentItem = 3
            }
            true
        }
    }
}
```

**关键点理解**：
- 使用 ViewPager + BottomNavigationView 架构
- 4个Fragment对应4个主要功能模块
- FragmentAdapter 管理Fragment生命周期

### 实践任务

#### 任务1：添加启动日志
在 App.kt 的 onCreate 方法中添加日志，观察初始化顺序：

```kotlin
override fun onCreate() {
    super.onCreate()
    Log.d("App", "开始初始化")
    
    CrashHandler.init(this)
    Log.d("App", "崩溃处理初始化完成")
    
    ThemeConfig.init(this)
    Log.d("App", "主题配置初始化完成")
    
    // ... 其他初始化
}
```

#### 任务2：修改欢迎页延迟时间
将 WelcomeActivity 的延迟时间改为 1000 毫秒，观察效果。

#### 任务3：添加新的启动页
创建一个新的启动页，显示应用Logo和版本号。

### 进阶挑战

1. **优化启动速度**
   - 分析各初始化步骤的耗时
   - 将非必要初始化改为异步执行
   - 使用 Systrace 分析启动性能

2. **添加启动动画**
   - 在 WelcomeActivity 中添加 Lottie 动画
   - 实现渐变背景效果
   - 添加品牌展示

---

## 🎯 模块二：书架模块

### 快速理解

书架就像你的私人图书馆：

1. **BookshelfFragment** - 图书馆大厅，展示所有书籍
2. **BookshelfAdapter** - 书架管理员，负责摆放书籍
3. **BookshelfViewModel** - 图书馆馆长，管理书籍数据
4. **BookDao** - 图书馆档案室，存储书籍信息

### 架构图解

```
书架模块数据流：

┌─────────────┐
│   用户操作   │
│  (点击/滑动) │
└──────┬──────┘
       │
       ▼
┌─────────────┐         ┌─────────────┐
│  Fragment   │ ◄──────►│  ViewModel  │
│  (UI展示)   │  LiveData │  (业务逻辑) │
└─────────────┘         └──────┬──────┘
       │                       │
       ▼                       ▼
┌─────────────┐         ┌─────────────┐
│   Adapter   │         │  Repository │
│  (列表适配) │         │  (数据仓库) │
└─────────────┘         └──────┬──────┘
                               │
                               ▼
                        ┌─────────────┐
                        │     DAO     │
                        │  (数据访问) │
                        └──────┬──────┘
                               │
                               ▼
                        ┌─────────────┐
                        │    Room     │
                        │  (数据库)   │
                        └─────────────┘
```

### 代码走读

#### 第一步：BookshelfFragment - 书架界面

```kotlin
// 文件位置：ui/main/explore/BookshelfFragment.kt

class BookshelfFragment : VMBaseFragment<FragmentBookshelfBinding, BookshelfViewModel>() {
    
    private val adapter by lazy { BookshelfAdapter() }
    
    override fun onFragmentCreated(savedInstanceState: Bundle?) {
        // ① 初始化RecyclerView
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@BookshelfFragment.adapter
        }
        
        // ② 观察数据变化
        viewModel.books.observe(viewLifecycleOwner) { books ->
            adapter.submitList(books)
        }
        
        // ③ 下拉刷新
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshBooks()
        }
    }
}
```

**关键点理解**：
- 使用 RecyclerView 展示书籍列表
- GridLayoutManager 实现网格布局
- LiveData 自动更新UI
- SwipeRefreshLayout 实现下拉刷新

#### 第二步：BookshelfViewModel - 书架业务逻辑

```kotlin
// 文件位置：ui/main/explore/BookshelfViewModel.kt

class BookshelfViewModel(application: Application) : BaseViewModel(application) {
    
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    
    // 书籍列表（LiveData，自动更新UI）
    val books: LiveData<List<Book>> = bookDao.observeAll()
    
    // 刷新书籍
    fun refreshBooks() {
        execute {
            // 从数据库重新加载
            bookDao.getAll()
        }
    }
    
    // 删除书籍
    fun deleteBook(book: Book) {
        execute {
            bookDao.delete(book)
        }
    }
    
    // 更新书籍排序
    fun updateSort(books: List<Book>) {
        execute {
            bookDao.update(*books.toTypedArray())
        }
    }
}
```

**关键点理解**：
- ViewModel 持有 LiveData，Fragment 观察 LiveData
- execute 方法在协程中执行数据库操作
- 数据库操作在 IO 线程执行，不阻塞主线程

#### 第三步：BookshelfAdapter - 书架适配器

```kotlin
// 文件位置：ui/main/explore/BookshelfAdapter.kt

class BookshelfAdapter : 
    BaseDiffAdapter<Book, BaseViewHolder<ItemBookBinding>>() {
    
    // 创建ViewHolder
    override fun onCreateViewHolder(
        parent: ViewGroup, 
        viewType: Int
    ): BaseViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BaseViewHolder(binding)
    }
    
    // 绑定数据
    override fun onBindViewHolder(
        holder: BaseViewHolder, 
        position: Int
    ) {
        val book = getItem(position)
        holder.binding.apply {
            // ① 绑定书籍数据
            this.book = book
            
            // ② 加载封面
            Glide.with(root)
                .load(book.coverUrl)
                .placeholder(R.drawable.cover_default)
                .into(ivCover)
            
            // ③ 点击事件
            root.setOnClickListener {
                onItemClick?.invoke(book)
            }
            
            // ④ 长按事件
            root.setOnLongClickListener {
                onItemLongClick?.invoke(book)
                true
            }
            
            // 立即执行数据绑定
            executePendingBindings()
        }
    }
}
```

**关键点理解**：
- ViewHolder 模式复用视图，提高性能
- ViewBinding 绑定视图和数据
- Glide 加载图片，自动处理缓存
- DiffUtil 计算差异，局部刷新

#### 第四步：BookDao - 书籍数据访问

```kotlin
// 文件位置：data/dao/BookDao.kt

@Dao
interface BookDao {
    
    // 查询所有书籍（LiveData，自动更新）
    @Query("SELECT * FROM books ORDER BY durTime DESC")
    fun observeAll(): LiveData<List<Book>>
    
    // 查询所有书籍（一次性查询）
    @Query("SELECT * FROM books ORDER BY durTime DESC")
    suspend fun getAll(): List<Book>
    
    // 插入书籍（冲突时替换）
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg book: Book)
    
    // 删除书籍
    @Delete
    suspend fun delete(vararg book: Book)
    
    // 更新书籍
    @Update
    suspend fun update(vararg book: Book)
    
    // 根据URL查询
    @Query("SELECT * FROM books WHERE bookUrl = :url")
    suspend fun getByUrl(url: String): Book?
}
```

**关键点理解**：
- @Dao 标记数据访问对象
- @Query 定义SQL查询
- suspend 函数支持协程调用
- LiveData 自动通知UI更新

### 实践任务

#### 任务1：添加书籍分组功能
1. 在 BookshelfFragment 中添加分组选择器
2. 根据分组过滤书籍列表
3. 实现分组管理界面

#### 任务2：实现书籍拖拽排序
1. 使用 ItemTouchHelper 实现拖拽
2. 更新书籍排序字段
3. 保存排序结果到数据库

#### 任务3：添加书籍搜索功能
1. 在书架顶部添加搜索框
2. 根据书名或作者搜索
3. 高亮显示搜索结果

### 进阶挑战

1. **性能优化**
   - 实现分页加载（Paging3）
   - 优化图片加载
   - 减少数据库查询次数

2. **UI增强**
   - 实现多种布局模式（列表/网格/瀑布流）
   - 添加书籍封面动画
   - 实现书架背景自定义

---

## 🎯 模块三：阅读模块

### 快速理解

阅读模块是整个应用的核心，就像一个精密的阅读机器：

1. **ReadBookActivity** - 阅读器的"身体"，展示界面
2. **ReadBookViewModel** - 阅读器的"大脑"，控制逻辑
3. **ReadBook** - 阅读器的"心脏"，管理状态
4. **PageFactory** - 阅读器的"手"，翻页操作
5. **ContentFactory** - 阅读器的"胃"，消化内容

### 架构图解

```
阅读模块架构图：

┌─────────────────────────────────────────────────────┐
│                   ReadBookActivity                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │  翻页动画  │  │  内容展示  │  │  菜单控制  │         │
│  └──────────┘  └──────────┘  └──────────┘         │
└─────────────────────┬───────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────┐
│                  ReadBookViewModel                  │
│  • 章节加载    • 进度保存    • 状态管理              │
└─────────────────────┬───────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────┐
│                     ReadBook                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │  网络书籍  │  │  本地书籍  │  │  缓存管理  │         │
│  │  WebBook  │  │ LocalBook │  │ CacheBook │         │
│  └──────────┘  └──────────┘  └──────────┘         │
└─────────────────────┬───────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────┐
│                   内容处理层                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │  章节解析  │  │  排版引擎  │  │  翻页引擎  │         │
│  └──────────┘  └──────────┘  └──────────┘         │
└─────────────────────────────────────────────────────┘
```

### 代码走读

#### 第一步：ReadBookActivity - 阅读界面

```kotlin
// 文件位置：ui/book/read/ReadBookActivity.kt

class ReadBookActivity : VMBaseActivity<ActivityReadBookBinding, ReadBookViewModel>() {
    
    private lateinit var pageFactory: PageFactory
    
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // ① 初始化翻页引擎
        pageFactory = PageFactory(this, binding.pageView)
        
        // ② 加载书籍
        val bookUrl = intent.getStringExtra("bookUrl")
        viewModel.loadBook(bookUrl)
        
        // ③ 观察章节内容
        viewModel.chapterContent.observe(this) { content ->
            pageFactory.setContent(content)
        }
        
        // ④ 设置翻页监听
        binding.pageView.setPageChangeListener(object : PageChangeListener {
            override fun onPageChange(page: Int) {
                viewModel.saveProgress(page)
            }
            
            override fun onChapterChange(chapter: Int) {
                viewModel.loadChapter(chapter)
            }
        })
        
        // ⑤ 显示/隐藏菜单
        binding.pageView.setOnClickListener {
            toggleMenu()
        }
    }
}
```

**关键点理解**：
- PageFactory 管理翻页逻辑
- ViewModel 管理数据和状态
- PageView 是自定义的阅读视图
- 点击屏幕切换菜单显示

#### 第二步：ReadBookViewModel - 阅读业务逻辑

```kotlin
// 文件位置：ui/book/read/ReadBookViewModel.kt

class ReadBookViewModel(application: Application) : BaseViewModel(application) {
    
    private val readBook = ReadBook
    
    // 当前章节内容
    private val _chapterContent = MutableStateFlow<String>("")
    val chapterContent: StateFlow<String> = _chapterContent
    
    // 加载书籍
    fun loadBook(bookUrl: String) {
        execute {
            val book = readBook.loadBook(bookUrl)
            loadChapter(book.durChapterIndex)
        }
    }
    
    // 加载章节
    fun loadChapter(chapterIndex: Int) {
        execute {
            // ① 获取章节信息
            val chapter = readBook.getChapter(chapterIndex)
            
            // ② 加载章节内容
            val content = readBook.getContent(chapter)
            
            // ③ 更新UI
            _chapterContent.value = content
            
            // ④ 预加载下一章
            readBook.preLoadNextChapter(chapterIndex)
        }
    }
    
    // 保存阅读进度
    fun saveProgress(page: Int) {
        execute {
            readBook.saveProgress(page)
        }
    }
}
```

**关键点理解**：
- StateFlow 替代 LiveData，支持协程
- 章节加载在协程中执行
- 自动预加载下一章，提升体验

#### 第三步：ReadBook - 阅读控制中心

```kotlin
// 文件位置：model/ReadBook.kt

object ReadBook {
    
    var book: Book? = null
    var chapterList: List<BookChapter> = emptyList()
    
    // 加载书籍
    suspend fun loadBook(bookUrl: String): Book {
        val bookDao = AppDatabase.getInstance().bookDao()
        val book = bookDao.getByUrl(bookUrl)!!
        
        // 加载章节列表
        chapterList = if (book.isLocalBook()) {
            LocalBook.getChapterList(book)
        } else {
            WebBook.getChapterList(book)
        }
        
        this.book = book
        return book
    }
    
    // 获取章节内容
    suspend fun getContent(chapter: BookChapter): String {
        return if (book?.isLocalBook() == true) {
            LocalBook.getContent(book!!, chapter)
        } else {
            WebBook.getContent(book!!, chapter)
        }
    }
    
    // 保存进度
    suspend fun saveProgress(page: Int) {
        book?.let {
            it.durChapterPos = page
            it.durTime = System.currentTimeMillis()
            AppDatabase.getInstance().bookDao().update(it)
        }
    }
}
```

**关键点理解**：
- object 单例模式，全局唯一
- 区分本地书籍和网络书籍
- 自动保存阅读进度

#### 第四步：PageFactory - 翻页引擎

```kotlin
// 文件位置：model/read/PageFactory.kt

class PageFactory(
    private val context: Context,
    private val pageView: PageView
) {
    private val pageList = mutableListOf<Page>()
    private var currentPage = 0
    
    // 设置内容
    fun setContent(content: String) {
        // ① 分段处理
        val paragraphs = content.split("\n")
        
        // ② 排版引擎处理
        pageList.clear()
        var currentPageContent = StringBuilder()
        
        for (paragraph in paragraphs) {
            // 添加段落
            currentPageContent.append(paragraph)
            currentPageContent.append("\n")
            
            // 检查是否需要分页
            if (needNewPage(currentPageContent)) {
                pageList.add(Page(currentPageContent.toString()))
                currentPageContent = StringBuilder()
            }
        }
        
        // ③ 添加最后一页
        if (currentPageContent.isNotEmpty()) {
            pageList.add(Page(currentPageContent.toString()))
        }
        
        // ④ 刷新显示
        pageView.setPageCount(pageList.size)
        pageView.setCurrentPage(0)
    }
    
    // 翻到下一页
    fun nextPage() {
        if (currentPage < pageList.size - 1) {
            currentPage++
            pageView.setCurrentPage(currentPage)
        }
    }
    
    // 翻到上一页
    fun prevPage() {
        if (currentPage > 0) {
            currentPage--
            pageView.setCurrentPage(currentPage)
        }
    }
}
```

**关键点理解**：
- 内容分页处理
- 支持多种翻页动画
- 自动计算页数

### 实践任务

#### 任务1：添加阅读进度条
1. 在阅读界面底部添加进度条
2. 显示当前页/总页数
3. 支持拖动跳转

#### 任务2：实现自动翻页
1. 添加自动翻页设置
2. 设置翻页间隔时间
3. 支持暂停/继续

#### 任务3：添加阅读统计
1. 记录阅读时长
2. 统计阅读字数
3. 生成阅读报告

### 进阶挑战

1. **排版优化**
   - 支持自定义字体
   - 实现段落缩进
   - 支持图文混排

2. **翻页动画**
   - 实现仿真翻页效果
   - 添加覆盖/滑动动画
   - 支持自定义动画

3. **性能优化**
   - 实现章节缓存
   - 优化大文件加载
   - 减少内存占用

---

## 🎯 模块四：书源规则模块

### 快速理解

书源规则是整个应用的灵魂，就像一个万能翻译器：

1. **BookSource** - 翻译规则书，定义如何解析网站
2. **AnalyzeRule** - 翻译官，执行解析规则
3. **WebBook** - 外交官，与网站沟通
4. **ScriptEngine** - 特工，执行特殊任务（JS脚本）

### 架构图解

```
书源规则解析流程：

┌─────────────┐
│  用户搜索   │
│  "斗破苍穹" │
└──────┬──────┘
       │
       ▼
┌─────────────┐         ┌─────────────┐
│   WebBook   │ ───────►│  BookSource │
│  (发起请求)  │         │  (规则定义) │
└──────┬──────┘         └─────────────┘
       │
       ▼
┌─────────────┐
│  HTTP请求   │
│  获取HTML   │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────┐
│            AnalyzeRule                  │
│  ┌────────┬────────┬────────┬────────┐ │
│  │  CSS   │ XPath  │JSONPath│  JS    │ │
│  │ 选择器 │ 路径   │  路径  │ 脚本   │ │
│  └────────┴────────┴────────┴────────┘ │
└─────────────────┬───────────────────────┘
                  │
                  ▼
           ┌─────────────┐
           │  解析结果   │
           │  书籍列表   │
           └─────────────┘
```

### 代码走读

#### 第一步：BookSource - 书源定义

```kotlin
// 文件位置：data/entities/BookSource.kt

@Entity(tableName = "bookSource")
data class BookSource(
    @PrimaryKey
    var bookSourceUrl: String = "",          // 书源地址（唯一标识）
    var bookSourceName: String = "",         // 书源名称
    var bookSourceGroup: String? = null,     // 书源分组
    
    // 搜索规则
    var searchRule: SearchRule? = null,
    
    // 发现规则
    var exploreRule: ExploreRule? = null,
    
    // 书籍信息规则
    var bookInfoRule: BookInfoRule? = null,
    
    // 目录规则
    var tocRule: TocRule? = null,
    
    // 正文规则
    var contentRule: ContentRule? = null
)

// 搜索规则定义
data class SearchRule(
    var url: String? = null,           // 搜索URL
    var bookList: String? = null,      // 书籍列表规则
    var name: String? = null,          // 书名规则
    var author: String? = null,        // 作者规则
    var intro: String? = null,         // 简介规则
    var coverUrl: String? = null       // 封面规则
)
```

**书源规则示例**：
```json
{
  "bookSourceUrl": "https://www.example.com",
  "bookSourceName": "示例书源",
  "searchRule": {
    "url": "https://www.example.com/search?keyword={{key}}",
    "bookList": ".book-list .item",
    "name": "h3.title@text",
    "author": ".author@text",
    "coverUrl": "img@src"
  }
}
```

#### 第二步：AnalyzeRule - 规则解析核心

```kotlin
// 文件位置：model/analyzeRule/AnalyzeRule.kt

class AnalyzeRule {
    
    var content: String = ""          // 待解析内容
    var baseUrl: String = ""          // 基础URL
    
    // 设置内容
    fun setContent(content: String, baseUrl: String = ""): AnalyzeRule {
        this.content = content
        this.baseUrl = baseUrl
        return this
    }
    
    // 获取字符串结果
    fun getString(rule: String): String {
        return when {
            // CSS选择器
            rule.startsWith("@css:") -> {
                parseByCss(rule.substring(5))
            }
            
            // XPath
            rule.startsWith("@xpath:") -> {
                parseByXPath(rule.substring(7))
            }
            
            // JSON Path
            rule.startsWith("$.") -> {
                parseByJsonPath(rule)
            }
            
            // 正则表达式
            rule.startsWith(":") -> {
                parseByRegex(rule.substring(1))
            }
            
            // 默认（CSS）
            else -> {
                parseByCss(rule)
            }
        }
    }
    
    // 获取列表结果
    fun getList(rule: String): List<String> {
        // 类似getString，但返回列表
    }
    
    // CSS选择器解析
    private fun parseByCss(rule: String): String {
        val document = Jsoup.parse(content)
        val elements = document.select(rule)
        return elements.text()
    }
    
    // XPath解析
    private fun parseByXPath(rule: String): String {
        val document = Jsoup.parse(content)
        val xpath = JXDocument(document)
        val nodes = xpath.selN(rule)
        return nodes.firstOrNull()?.asString() ?: ""
    }
    
    // JSON Path解析
    private fun parseByJsonPath(rule: String): String {
        val jsonObject = JSONObject(content)
        val result = JsonPath.read(jsonObject, rule)
        return result.toString()
    }
    
    // 正则表达式解析
    private fun parseByRegex(rule: String): String {
        val regex = Regex(rule)
        val match = regex.find(content)
        return match?.groupValues?.get(1) ?: ""
    }
}
```

**规则类型说明**：

| 规则类型 | 语法 | 示例 | 适用场景 |
|---------|------|------|---------|
| CSS选择器 | `@css:` 或默认 | `.title@text` | HTML页面 |
| XPath | `@xpath:` | `//div[@class='title']/text()` | XML/HTML |
| JSON Path | `$.` | `$.data.books[0].name` | JSON数据 |
| 正则表达式 | `:` | `:(.*?)<` | 复杂文本 |
| JavaScript | `{{}}` | `{{result.replace('a', 'b')}}` | 复杂处理 |

#### 第三步：WebBook - 网络书籍获取

```kotlin
// 文件位置：model/webBook/WebBook.kt

object WebBook {
    
    // 搜索书籍
    suspend fun searchBook(
        key: String,
        source: BookSource
    ): List<SearchBook> {
        return withContext(Dispatchers.IO) {
            // ① 构建搜索URL
            val url = buildSearchUrl(key, source)
            
            // ② 发送HTTP请求
            val response = OkHttpUtils.get(url)
            
            // ③ 解析搜索结果
            AnalyzeRule()
                .setContent(response.body, url)
                .parseSearchResult(source.searchRule)
        }
    }
    
    // 获取书籍信息
    suspend fun getBookInfo(
        bookUrl: String,
        source: BookSource
    ): BookInfo {
        return withContext(Dispatchers.IO) {
            // ① 发送请求
            val response = OkHttpUtils.get(bookUrl)
            
            // ② 解析书籍信息
            AnalyzeRule()
                .setContent(response.body, bookUrl)
                .parseBookInfo(source.bookInfoRule)
        }
    }
    
    // 获取章节列表
    suspend fun getChapterList(
        book: Book,
        source: BookSource
    ): List<BookChapter> {
        return withContext(Dispatchers.IO) {
            // ① 发送请求
            val response = OkHttpUtils.get(book.tocUrl)
            
            // ② 解析章节列表
            AnalyzeRule()
                .setContent(response.body, book.tocUrl)
                .parseChapterList(source.tocRule)
        }
    }
    
    // 获取章节内容
    suspend fun getContent(
        chapter: BookChapter,
        source: BookSource
    ): String {
        return withContext(Dispatchers.IO) {
            // ① 发送请求
            val response = OkHttpUtils.get(chapter.url)
            
            // ② 解析章节内容
            AnalyzeRule()
                .setContent(response.body, chapter.url)
                .parseContent(source.contentRule)
        }
    }
}
```

**关键点理解**：
- 所有网络请求都在协程中执行
- AnalyzeRule 是解析的核心
- 支持多种解析方式

#### 第四步：规则调试

```kotlin
// 文件位置：ui/book/source/debug/BookSourceDebugActivity.kt

class BookSourceDebugActivity : VMBaseActivity() {
    
    // 调试搜索规则
    fun debugSearch() {
        val source = getCurrentSource()
        
        execute {
            // ① 执行搜索
            val result = WebBook.searchBook("斗破苍穹", source)
            
            // ② 显示调试信息
            withContext(Dispatchers.Main) {
                adapter.submitList(result)
                showDebugLog("搜索成功，找到${result.size}本书")
            }
        }
    }
    
    // 调试章节列表
    fun debugChapterList(book: SearchBook) {
        execute {
            // ① 获取书籍信息
            val bookInfo = WebBook.getBookInfo(book.bookUrl, source)
            
            // ② 获取章节列表
            val chapters = WebBook.getChapterList(bookInfo, source)
            
            // ③ 显示调试信息
            withContext(Dispatchers.Main) {
                showChapterList(chapters)
                showDebugLog("获取章节成功，共${chapters.size}章")
            }
        }
    }
    
    // 调试章节内容
    fun debugContent(chapter: BookChapter) {
        execute {
            // ① 获取章节内容
            val content = WebBook.getContent(chapter, source)
            
            // ② 显示调试信息
            withContext(Dispatchers.Main) {
                showContent(content)
                showDebugLog("获取内容成功，共${content.length}字")
            }
        }
    }
}
```

### 实践任务

#### 任务1：编写一个简单的书源
为一个小说网站编写书源规则：
1. 分析网站HTML结构
2. 编写搜索规则
3. 编写书籍信息规则
4. 编写章节规则
5. 调试并验证

#### 任务2：实现规则测试工具
创建一个规则测试工具：
1. 输入URL和规则
2. 实时显示解析结果
3. 支持多种规则类型
4. 显示调试日志

#### 任务3：添加规则帮助文档
完善规则帮助文档：
1. CSS选择器教程
2. XPath教程
3. JSON Path教程
4. 正则表达式教程
5. JavaScript使用说明

### 进阶挑战

1. **规则优化**
   - 实现规则缓存
   - 优化解析性能
   - 支持规则热更新

2. **高级功能**
   - 实现登录规则
   - 支持验证码识别
   - 添加反爬虫策略

3. **调试工具**
   - 可视化规则编辑器
   - 实时预览解析结果
   - 规则错误提示

---

## 🎯 模块五：数据存储模块

### 快速理解

数据存储就像一个现代化的仓库系统：

1. **AppDatabase** - 仓库总管，管理所有仓库
2. **DAO** - 仓库管理员，负责存取货物
3. **Entity** - 货物清单，定义货物规格
4. **Migration** - 仓库改造，升级仓库布局

### 架构图解

```
Room 数据库架构：

┌─────────────────────────────────────────────────────┐
│                  AppDatabase                        │
│              (数据库总管)                            │
│  • 版本管理    • 迁移处理    • DAO管理               │
└─────────────────────┬───────────────────────────────┘
                      │
        ┌─────────────┼─────────────┐
        │             │             │
        ▼             ▼             ▼
┌───────────┐  ┌───────────┐  ┌───────────┐
│  BookDao  │  │SourceDao  │  │ChapterDao │
│ (书籍仓库) │  │(书源仓库) │  │(章节仓库) │
└─────┬─────┘  └─────┬─────┘  └─────┬─────┘
      │              │              │
      ▼              ▼              ▼
┌───────────┐  ┌───────────┐  ┌───────────┐
│   Book    │  │BookSource │  │  Chapter  │
│ (书籍实体) │  │(书源实体) │  │(章节实体) │
└───────────┘  └───────────┘  └───────────┘
```

### 代码走读

#### 第一步：AppDatabase - 数据库定义

```kotlin
// 文件位置：data/AppDatabase.kt

@Database(
    entities = [
        Book::class,              // 书籍
        BookSource::class,        // 书源
        BookChapter::class,       // 章节
        BookGroup::class,         // 分组
        Bookmark::class,          // 书签
        Cache::class,             // 缓存
        Cookie::class,            // Cookie
        ReadRecord::class,        // 阅读记录
        ReplaceRule::class,       // 替换规则
        RssSource::class,         // RSS源
        RssArticle::class,        // RSS文章
        // ... 更多实体
    ],
    version = 15,                 // 数据库版本
    exportSchema = true           // 导出Schema
)
abstract class AppDatabase : RoomDatabase() {
    
    // 21个DAO接口
    abstract fun bookDao(): BookDao
    abstract fun bookSourceDao(): BookSourceDao
    abstract fun bookChapterDao(): BookChapterDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cacheDao(): CacheDao
    abstract fun cookieDao(): CookieDao
    abstract fun readRecordDao(): ReadRecordDao
    abstract fun replaceRuleDao(): ReplaceRuleDao
    abstract fun rssSourceDao(): RssSourceDao
    abstract fun rssArticleDao(): RssArticleDao
    // ... 更多DAO
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        // 单例模式获取数据库实例
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "legado.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    // ... 所有迁移
                    MIGRATION_14_15
                )
                .build()
        }
    }
}
```

**关键点理解**：
- @Database 标记数据库类
- entities 定义所有实体类
- version 定义数据库版本
- Migration 处理版本升级

#### 第二步：Entity - 实体类定义

```kotlin
// 文件位置：data/entities/Book.kt

@Entity(tableName = "books")
data class Book(
    @PrimaryKey                   // 主键
    var bookUrl: String = "",     // 书籍URL（唯一标识）
    
    @ColumnInfo(name = "name")    // 列名
    var name: String = "",        // 书名
    
    @ColumnInfo(name = "author")
    var author: String? = null,   // 作者（可为空）
    
    @ColumnInfo(name = "coverUrl")
    var coverUrl: String? = null, // 封面URL
    
    @ColumnInfo(name = "intro")
    var intro: String? = null,    // 简介
    
    @ColumnInfo(name = "kind")
    var kind: String? = null,     // 分类
    
    @ColumnInfo(name = "wordCount")
    var wordCount: String? = null,// 字数
    
    @ColumnInfo(name = "lastChapter")
    var lastChapter: String? = null, // 最新章节
    
    @ColumnInfo(name = "durChapterIndex")
    var durChapterIndex: Int = 0, // 当前章节索引
    
    @ColumnInfo(name = "durChapterPos")
    var durChapterPos: Int = 0,   // 当前章节位置
    
    @ColumnInfo(name = "durTime")
    var durTime: Long = 0,        // 最后阅读时间
    
    @ColumnInfo(name = "order")
    var order: Int = 0            // 排序
) {
    // 辅助方法
    fun isLocalBook(): Boolean {
        return bookUrl.startsWith("local_")
    }
}
```

**关键点理解**：
- @Entity 标记实体类
- @PrimaryKey 标记主键
- @ColumnInfo 定义列名
- data class 自动生成 equals、hashCode、toString

#### 第三步：DAO - 数据访问对象

```kotlin
// 文件位置：data/dao/BookDao.kt

@Dao
interface BookDao {
    
    // ========== 查询操作 ==========
    
    // 查询所有书籍（LiveData）
    @Query("SELECT * FROM books ORDER BY durTime DESC")
    fun observeAll(): LiveData<List<Book>>
    
    // 查询所有书籍（一次性）
    @Query("SELECT * FROM books ORDER BY durTime DESC")
    suspend fun getAll(): List<Book>
    
    // 根据URL查询
    @Query("SELECT * FROM books WHERE bookUrl = :url")
    suspend fun getByUrl(url: String): Book?
    
    // 根据分组查询
    @Query("SELECT * FROM books WHERE `group` = :groupId ORDER BY `order`")
    suspend fun getByGroup(groupId: Long): List<Book>
    
    // 搜索书籍
    @Query("SELECT * FROM books WHERE name LIKE '%' || :keyword || '%' OR author LIKE '%' || :keyword || '%'")
    suspend fun search(keyword: String): List<Book>
    
    // ========== 插入操作 ==========
    
    // 插入（冲突时替换）
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg book: Book)
    
    // 批量插入
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(books: List<Book>)
    
    // ========== 更新操作 ==========
    
    // 更新
    @Update
    suspend fun update(vararg book: Book)
    
    // 更新阅读进度
    @Query("UPDATE books SET durChapterIndex = :chapterIndex, durChapterPos = :chapterPos, durTime = :time WHERE bookUrl = :bookUrl")
    suspend fun updateProgress(bookUrl: String, chapterIndex: Int, chapterPos: Int, time: Long)
    
    // ========== 删除操作 ==========
    
    // 删除
    @Delete
    suspend fun delete(vararg book: Book)
    
    // 删除所有
    @Query("DELETE FROM books")
    suspend fun deleteAll()
    
    // 根据URL删除
    @Query("DELETE FROM books WHERE bookUrl = :url")
    suspend fun deleteByUrl(url: String)
}
```

**关键点理解**：
- @Dao 标记数据访问对象
- @Query 定义SQL查询
- @Insert 定义插入操作
- @Update 定义更新操作
- @Delete 定义删除操作
- suspend 函数支持协程调用
- LiveData 自动通知UI更新

#### 第四步：Migration - 数据库迁移

```kotlin
// 文件位置：data/AppDatabase.kt

// 从版本1迁移到版本2
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 添加新列
        database.execSQL("ALTER TABLE books ADD COLUMN newColumn TEXT")
    }
}

// 从版本2迁移到版本3
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 创建新表
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS new_table (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL
            )
        """)
    }
}

// 从版本14迁移到版本15
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 复杂迁移逻辑
        // 1. 创建临时表
        // 2. 复制数据
        // 3. 删除旧表
        // 4. 重命名临时表
    }
}
```

**关键点理解**：
- Migration 处理数据库版本升级
- 每个版本升级都需要对应的Migration
- 使用SQL语句修改数据库结构
- 迁移失败会导致应用崩溃

### 实践任务

#### 任务1：添加新实体类
创建一个阅读统计实体类：
1. 定义 ReadingStats 实体类
2. 添加到 AppDatabase
3. 创建 ReadingStatsDao
4. 实现增删改查方法

#### 任务2：实现数据备份
实现数据库备份功能：
1. 导出数据库为JSON文件
2. 从JSON文件导入数据
3. 支持自动备份
4. 支持云同步

#### 任务3：优化查询性能
优化数据库查询：
1. 添加索引
2. 优化复杂查询
3. 使用事务
4. 测试查询性能

### 进阶挑战

1. **数据库加密**
   - 使用 SQLCipher 加密数据库
   - 实现密钥管理
   - 处理性能影响

2. **多数据库支持**
   - 实现分库分表
   - 支持多用户数据隔离
   - 实现数据迁移

3. **性能监控**
   - 监控查询性能
   - 分析慢查询
   - 优化数据库结构

---

## 🎯 模块六：网络请求模块

### 快速理解

网络请求模块就像一个高效的快递系统：

1. **OkHttp** - 快递公司，负责运输
2. **Cronet** - 高速快递，使用Chromium引擎
3. **HttpHelp** - 快递调度中心，管理请求
4. **CookieManager** - 身份证管理，存储登录状态

### 架构图解

```
网络请求架构：

┌─────────────────────────────────────────────────────┐
│                  HttpHelp                          │
│              (网络请求管理)                         │
└─────────────────────┬───────────────────────────────┘
                      │
        ┌─────────────┴─────────────┐
        │                           │
        ▼                           ▼
┌───────────────┐           ┌───────────────┐
│    OkHttp     │           │    Cronet     │
│  (标准引擎)   │           │ (Chromium引擎)│
└───────┬───────┘           └───────┬───────┘
        │                           │
        └─────────────┬─────────────┘
                      │
                      ▼
              ┌───────────────┐
              │  Cookie管理   │
              │  请求缓存     │
              │  代理设置     │
              └───────────────┘
```

### 代码走读

#### 第一步：OkHttp配置

```kotlin
// 文件位置：help/http/HttpHelp.kt

object HttpHelp {
    
    // 获取OkHttpClient
    fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // 连接超时
            .connectTimeout(30, TimeUnit.SECONDS)
            // 读取超时
            .readTimeout(30, TimeUnit.SECONDS)
            // 写入超时
            .writeTimeout(30, TimeUnit.SECONDS)
            // 添加拦截器
            .addInterceptor(LoggingInterceptor())
            .addInterceptor(HeaderInterceptor())
            // Cookie管理
            .cookieJar(CookieManager.getInstance())
            // 缓存
            .cache(Cache(cacheDir, 10 * 1024 * 1024))
            // 代理
            .proxySelector(ProxySelector.getDefault())
            .build()
    }
}

// 日志拦截器
class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        Log.d("Http", "发送请求: ${request.url}")
        val startTime = System.nanoTime()
        
        val response = chain.proceed(request)
        
        val endTime = System.nanoTime()
        Log.d("Http", "耗时: ${(endTime - startTime) / 1e6} ms")
        
        return response
    }
}

// 请求头拦截器
class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("User-Agent", "Legado/${BuildConfig.VERSION_NAME}")
            .addHeader("Accept", "text/html,application/xhtml+xml")
            .build()
        
        return chain.proceed(request)
    }
}
```

#### 第二步：网络请求工具类

```kotlin
// 文件位置：utils/OkHttpUtils.kt

object OkHttpUtils {
    
    private val client = HttpHelp.getOkHttpClient()
    
    // GET请求
    suspend fun get(url: String): Response {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            client.newCall(request).execute()
        }
    }
    
    // POST请求（JSON）
    suspend fun postJson(url: String, json: String): Response {
        return withContext(Dispatchers.IO) {
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            client.newCall(request).execute()
        }
    }
    
    // POST请求（表单）
    suspend fun postForm(url: String, params: Map<String, String>): Response {
        return withContext(Dispatchers.IO) {
            val formBody = FormBody.Builder().apply {
                params.forEach { (key, value) ->
                    add(key, value)
                }
            }.build()
            
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()
            
            client.newCall(request).execute()
        }
    }
    
    // 下载文件
    suspend fun download(url: String, destFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = get(url)
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
```

#### 第三步：Cookie管理

```kotlin
// 文件位置：help/http/CookieManager.kt

class CookieManager : CookieJar {
    
    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    
    // 保存Cookie
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        cookieStore[host] = cookies
        
        // 持久化存储
        saveToDatabase(host, cookies)
    }
    
    // 加载Cookie
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        
        // 从内存加载
        cookieStore[host]?.let { return it }
        
        // 从数据库加载
        return loadFromDatabase(host)
    }
    
    // 保存到数据库
    private fun saveToDatabase(host: String, cookies: List<Cookie>) {
        val cookieDao = AppDatabase.getInstance().cookieDao()
        
        execute {
            cookies.forEach { cookie ->
                val entity = Cookie(
                    host = host,
                    name = cookie.name,
                    value = cookie.value,
                    expiresAt = cookie.expiresAt
                )
                cookieDao.insert(entity)
            }
        }
    }
    
    // 从数据库加载
    private fun loadFromDatabase(host: String): List<Cookie> {
        val cookieDao = AppDatabase.getInstance().cookieDao()
        val entities = runBlocking { cookieDao.getByHost(host) }
        
        return entities.map { entity ->
            Cookie.Builder()
                .name(entity.name)
                .value(entity.value)
                .expiresAt(entity.expiresAt)
                .build()
        }
    }
    
    // 清除Cookie
    fun clear() {
        cookieStore.clear()
        execute {
            AppDatabase.getInstance().cookieDao().deleteAll()
        }
    }
}
```

### 实践任务

#### 任务1：添加网络状态监听
1. 监听网络连接状态
2. 网络断开时提示用户
3. 网络恢复时自动重试

#### 任务2：实现请求重试机制
1. 添加重试拦截器
2. 设置最大重试次数
3. 指数退避策略

#### 任务3：实现请求缓存
1. 配置OkHttp缓存
2. 实现离线缓存策略
3. 缓存过期处理

### 进阶挑战

1. **网络优化**
   - 实现请求合并
   - 添加请求优先级
   - 实现请求取消

2. **安全增强**
   - 实现证书锁定
   - 添加请求签名
   - 防止中间人攻击

3. **性能监控**
   - 监控请求耗时
   - 分析网络性能
   - 优化请求策略

---

## 📝 学习建议

### 学习方法

1. **由浅入深**
   - 先理解模块功能
   - 再看架构设计
   - 最后深入代码

2. **动手实践**
   - 每学一个模块就修改代码
   - 完成实践任务
   - 尝试进阶挑战

3. **记录总结**
   - 写学习笔记
   - 画架构图
   - 分享学习心得

### 调试技巧

1. **使用日志**
   ```kotlin
   Log.d("TAG", "调试信息")
   ```

2. **断点调试**
   - 在关键代码设置断点
   - 观察变量值
   - 单步执行

3. **性能分析**
   - 使用 Android Profiler
   - 分析内存、CPU、网络
   - 找出性能瓶颈

### 常见问题

1. **看不懂代码怎么办？**
   - 先看注释和文档
   - 搜索相关知识点
   - 询问社区或AI助手

2. **不知道从哪里开始？**
   - 从最简单的模块开始
   - 跟着学习路线走
   - 完成实践任务

3. **遇到Bug怎么办？**
   - 查看错误日志
   - 使用断点调试
   - 搜索解决方案

---

## 🎓 学习资源

### 官方文档
- [Kotlin 官方文档](https://kotlinlang.org/docs/)
- [Android 开发者文档](https://developer.android.com)
- [Room 持久化库](https://developer.android.com/training/data-storage/room)
- [OkHttp 官方文档](https://square.github.io/okhttp/)

### 推荐书籍
- 《Kotlin实战》
- 《第一行代码 Android》
- 《Android 架构组件实战》
- 《深入理解 Kotlin 协程》

### 在线课程
- Google Codelabs
- Udacity Android 课程
- Coursera Android 开发

---

**祝你学习愉快！从入门到精通，成为 Android 开发高手！**
