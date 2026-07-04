# 目录不完全加载 - 详情页支持渐进式加载

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。

**目标：** 让 BookInfoActivity（书籍详情页）支持渐进式目录加载，用户开启 `tocPartialLoad` 后，无需等待全部 7000 章目录加载完即可查看目录、点击章节进入正文。

**架构：** 将 `WebBook.getChapterListFlow()`（Flow 版渐进式目录加载）引入 BookInfoViewModel，逐批将结果写入数据库并通知 UI。移除 BookInfoActivity 中"目录为空时阻止打开目录"的拦截。ChapterListFragment 通过 EventBus 监听增量更新。

**技术栈：** Kotlin Coroutines Flow, LiveData, Room DB, EventBus (LiveEventBus)

---

## 涉及文件

- **修改：** `app/src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt` — 核心：新增 Flow 版目录加载逻辑
- **修改：** `app/src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt` — 移除目录空值拦截
- **修改：** `app/src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt` — 观察增量更新事件
- **修改：** `app/src/main/java/io/legado/app/constant/EventBus.kt` — 新增 TOC 增量更新事件常量

---

## 任务

### 任务 1：EventBus 新增 TOC 增量更新事件

**文件：**
- 修改：`app/src/main/java/io/legado/app/constant/EventBus.kt`

在 `REFRESH_BOOK_TOC` 行附近新增：

```kotlin
const val TOC_PARTIAL_LOADED = "tocPartialLoaded"     // 目录渐进加载部分完成
```

---

### 任务 2：BookInfoViewModel 新增 Flow 版目录加载

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt`

**步骤 1：** 在文件头部 import 区新增：

```kotlin
import io.legado.app.utils.postEvent
```

**步骤 2：** 修改 `loadChapter()` 方法中 `else`（网络书源）分支，将 `WebBook.getChapterList()` 替换为 `WebBook.getChapterListFlow()`。

原始代码（第 241-268 行）：

```kotlin
} else {
    val bookSource = bookSource ?: let {
        chapterListData.postValue(emptyList())
        context.toastOnUi(R.string.error_no_source)
        return
    }
    val oldBook = book.copy()
    WebBook.getChapterList(scope, bookSource, book, runPreUpdateJs, isFromBookInfo = isFromBookInfo)
        .onSuccess(IO) {
            if (inBookshelf) {
                book.removeType(BookType.updateError)
                appDb.bookDao.replace(oldBook, book)
                if (oldBook.bookUrl != book.bookUrl) {
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*it.toTypedArray())
                ReadBook.onChapterListUpdated(book)
            }
            chapterListData.postValue(it)
        }.onError {
            chapterListData.postValue(emptyList())
            AppLog.put("获取目录失败\n${it.localizedMessage}", it)
            context.toastOnUi(R.string.error_get_chapter_list)
        }
}
```

替换为：

```kotlin
} else {
    val bookSource = bookSource ?: let {
        chapterListData.postValue(emptyList())
        context.toastOnUi(R.string.error_no_source)
        return
    }
    val oldBook = book.copy()
    execute(scope) {
        WebBook.getChapterListFlow(bookSource, book, runPreUpdateJs, isFromBookInfo = isFromBookInfo)
            .collect { partial ->
                val chapters = partial.chapters
                if (chapters.isEmpty()) return@collect
                if (partial.isComplete) {
                    // 目录全部加载完成：完整更新数据库和书籍信息
                    if (inBookshelf) {
                        book.removeType(BookType.updateError)
                        if (oldBook.bookUrl == book.bookUrl) {
                            appDb.bookDao.update(book)
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*chapters.toTypedArray())
                        ReadBook.onChapterListUpdated(book)
                    }
                    chapterListData.postValue(chapters)
                } else {
                    // 中间过程：增量保存到数据库，通知目录视图刷新
                    appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    book.totalChapterNum = chapters.size
                    chapterListData.postValue(chapters)
                    ReadBook.onChapterListUpdated(book, loadContent = false, isIncremental = true)
                    postEvent(EventBus.TOC_PARTIAL_LOADED, book.bookUrl)
                }
            }
    }.onError {
        chapterListData.postValue(emptyList())
        AppLog.put("获取目录失败\n${it.localizedMessage}", it)
        context.toastOnUi(R.string.error_get_chapter_list)
    }
}
```

注意：
- `execute {}` 就是 `BaseViewModel.execute()`，内部使用 `Dispatchers.IO`。
- 每次 Flow emit 时，将部分章节插入数据库 + post 到 `chapterListData` + 发送 `TOC_PARTIAL_LOADED` 事件。
- `ReadBook.onChapterListUpdated(book, loadContent = false, isIncremental = true)` 确保 ReadBook 状态更新但不加载正文。

---

### 任务 3：BookInfoActivity 移除目录空值拦截

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt`

将第 836-852 行的 `tvTocView.setOnClickListener` 从：

```kotlin
tvTocView.setOnClickListener {
    if (viewModel.chapterListData.value.isNullOrEmpty()) {
        toastOnUi(R.string.chapter_list_empty)
        return@setOnClickListener
    }
    viewModel.getBook()?.let { book ->
        if (!viewModel.inBookshelf) {
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    openChapterList()
                }
            }
        } else {
            openChapterList()
        }
    }
}
```

修改为：

```kotlin
tvTocView.setOnClickListener {
    viewModel.getBook()?.let { book ->
        if (!viewModel.inBookshelf) {
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    openChapterList()
                }
            }
        } else {
            openChapterList()
        }
    }
}
```

即：移除 `chapterListData.value.isNullOrEmpty()` 检查和对应的 toast。让详情页的"查看目录"按钮始终可用（只要 book 存在）。

---

### 任务 4：ChapterListFragment 观察 TOC 增量更新事件

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt`

在 `observeLiveBus()` 方法中新增对 `TOC_PARTIAL_LOADED` 事件的监听：

```kotlin
override fun observeLiveBus() {
    observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
        viewModel.bookData.value?.bookUrl?.let { bookUrl ->
            if (book.bookUrl == bookUrl) {
                adapter.cacheFileNames.add(chapter.getFileName())
                adapter.notifyChapterChanged(chapter.index)
            }
        }
    }
    // 新增：监听目录渐进加载事件，刷新目录列表
    observeEvent<String>(EventBus.TOC_PARTIAL_LOADED) { bookUrl ->
        if (viewModel.bookUrl == bookUrl) {
            upChapterList(null)
        }
    }
}
```

这样每次 Flow 有新的中间结果写入数据库后，ChapterListFragment 会自动从数据库重新读取并刷新 RecyclerView。

---

## 验证

1. 构建 debug 版本：`./gradlew assembleAppMaxDebug`
2. 在设置中开启"目录不完全加载"开关
3. 打开一本章节数较多的网络书籍的详情页
4. 等待几秒（第一页目录加载完），点击"查看目录"
5. 验证：目录列表中应显示已加载的部分章节
6. 点击第一章或当前阅读位置章节，验证能进入正文
7. 返回目录，验证随着后台加载继续，目录条目数在增长
