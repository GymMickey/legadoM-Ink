# 书源规则 JS 变量存储机制详解

## 快速理解

想象一下这个场景：

> 你在一家**图书馆**（Legado App）工作。每当你需要处理一本书时：
> 1. **你不能在墙上随便写字**（不能用 `var`/`let` 声明全局变量）
> 2. 图书馆给你发了一张**工作便签**（临时 JS 作用域），用完就扔
> 3. 但有些信息需要**长期保存**，比如「这本书看到第几章了」——这时候你要用图书馆的**档案柜**（`put`/`get` 系统）
> 4. 而且档案柜还分了**不同层级**：书的层级、章节的层级、书源的层级
> 5. 你有**多种方式**向档案柜存取东西——直接递纸条、写表格、用暗号等等

---

## 1️⃣ 核心问题：为什么不能用 var/let 直接声明？

### 根本原因：每次 JS 执行都是"一次性"的

看看 `AnalyzeRule.kt` 中的 `evalJS` 方法：

```kotlin
fun evalJS(jsStr: String, result: Any? = null): Any? {
    val bindings = buildScriptBindings { bindings ->
        bindings["java"] = this
        bindings["cookie"] = CookieStore
        bindings["cache"] = CacheManager
        bindings["source"] = source
        bindings["book"] = book
        // ... 其他绑定
    }

    // 每次调用都创建一个新的作用域！
    val scope = if (topScope == null) {
        RhinoScriptEngine.getRuntimeScope(bindings)  // ← 全新作用域
    } else {
        bindings.apply { prototype = topScope }
    }

    return RhinoScriptEngine.eval(jsStr, scope, coroutineContext)
    // ← 执行完后，scope 就被丢弃了！
}
```

**关键点理解**：
- 每次 `evalJS()` 都**创建一个新的 `ScriptBindings` 对象**（新的作用域）
- JS 代码执行完，这个作用域对象就**没人引用，等待 GC 回收**
- 你在 JS 中用 `var x = 1` 声明变量，这个变量是**存在这个临时作用域上的**
- 下一次 `evalJS()` 调用又是一个**全新的作用域**，之前的 `var x` 就丢了

### 举个例子

```javascript
// 规则1：声明变量
var myVar = "你好";  // 存在临时作用域A上

// 规则2：使用变量
myVar  // ← 报错！作用域B上根本没有 myVar
```

这就好比每次给你一张**新的白纸**，你在第一张纸上写了东西，然后纸被收走了。下次再给你一张新白纸，上次写的内容自然就找不到了。

---

## 2️⃣ 那共享作用域（SharedJsScope）是怎么回事？

你可能会说：「等等，代码里不是有 `topScopeRef` 和 `SharedJsScope` 吗？」

没错！确实有一个**共享作用域**机制，但它的设计是给你**加载工具函数库**用的，而不是给你存变量用的。

看看 `SharedJsScope.kt` 的关键代码：

```kotlin
fun getScope(jsLib: String?, coroutineContext: CoroutineContext?): Scriptable? {
    val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())

    // 加载 jsLib 中定义的全局函数（工具函数）
    resolveJsLibString(jsLib)?.let {
        RhinoScriptEngine.eval(it, scope, coroutineContext)
    }

    // ★ 关键！阻止扩展 —— 不允许新增属性！
    if (scope is ScriptableObject) {
        scope.preventExtensions()
    }

    return scope
}
```

**关键点理解**：
- `SharedJsScope` 的作用是加载**书源的 `jsLib`**（公共 JS 库代码）
- 加载完后立刻调用了 **`scope.preventExtensions()`** — 这意味着你不能在这个共享作用域上新增任何变量！
- 为啥这么做？为了**安全**和**隔离**，防止不同规则之间互相污染全局命名空间
- 所以 `jsLib` 是用来定义**函数**（工具方法）的，不是给你存变量的

---

## 3️⃣ 完整的变量存取写法（多种写法）

既然不能直接用 JS 变量，Legado 提供了一套完善的**存储 API**。以下是所有可用的存取方式：

---

### 写法一：`java.put()` / `java.get()`

**使用场景**：最常用，适用于所有 JS 规则中

```javascript
// 存变量
java.put("token", "abc123");

// 取变量
var token = java.get("token");
```

**底层实现**：
- `java` 对象指向的是 `AnalyzeRule` 实例
- `put()` 会按优先级自动选择存储位置：**章节级 → 书籍级 → 规则数据级 → 书源级**
- `get()` 同样按优先级查找：`chapter?.getVariable() → book?.getVariable() → ruleData?.getVariable() → source?.get()`

**优先级说明**（从高到低）：
1. **章节级**（`BookChapter.variableMap`）：当前章节处理期间有效
2. **书籍级**（`Book.variableMap`）：整本书处理期间，持久化到数据库
3. **规则数据级**（`RuleData`）：单次规则解析期间
4. **书源级**（`BaseSource`，通过 `CacheManager` 持久化）：跨书籍的全局数据

---

### 写法二：`source.put()` / `source.get()`

**使用场景**：只想存到书源级别，跨所有书籍共享的数据

```javascript
// 存变量（存到 CacheManager，key 为 "v_书源URL_变量名"）
source.put("loginToken", "xyz789");

// 取变量
var token = source.get("loginToken");
```

**底层实现**：
```kotlin
// BaseSource.kt
fun put(key: String, value: String): String {
    CacheManager.put("v_${getKey()}_${key}", value)
    return value
}

fun get(key: String): String {
    return CacheManager.get("v_${getKey()}_${key}") ?: ""
}
```

**特点**：
- 直接存到 `CacheManager`（数据库 + 缓存文件）
- 所有使用同个书源的书籍都能访问到
- 适合存：登录 token、全局配置、并发率等

---

### 写法三：`book.putVariable()` / `book.getVariable()`

**使用场景**：只想存到书籍级别，与特定书籍关联的数据

```javascript
// 存变量
book.putVariable("custom", "用户自定义内容");
book.putVariable("readCount", "100");

// 取变量
var custom = book.getVariable("custom");
var readCount = book.getVariable("readCount");
```

**底层实现**：
```kotlin
// BaseBook.kt
override fun putVariable(key: String, value: String?): Boolean {
    if (super.putVariable(key, value)) {
        variable = GSON.toJson(variableMap)  // 序列化到数据库字段
    }
    return true
}

override fun putBigVariable(key: String, value: String?) {
    RuleBigDataHelp.putBookVariable(bookUrl, key, value)  // 大数据存文件
}

override fun getBigVariable(key: String): String? {
    return RuleBigDataHelp.getBookVariable(bookUrl, key)
}
```

**特点**：
- 小数据（<10000字符）：存在内存 `HashMap`，序列化到 `Book.variable` 数据库字段
- 大数据（>=10000字符）：通过 `RuleBigDataHelp` 存到文件系统
- 随书籍持久化，App 重启还在
- 适合存：书籍自定义信息、阅读配置等

---

### 写法四：`chapter.putVariable()` / `chapter.getVariable()`

**使用场景**：只想存到章节级别，与特定章节关联的数据

```javascript
// 存变量
chapter.putVariable("imgList", "图片URL列表的JSON");
chapter.putVariable("lyric", "歌词内容");

// 取变量
var imgList = chapter.getVariable("imgList");
```

**底层实现**：
```kotlin
// BookChapter.kt
override fun putVariable(key: String, value: String?): Boolean {
    if (super.putVariable(key, value)) {
        variable = GSON.toJson(variableMap)  // 序列化到数据库字段
    }
    return true
}

override fun putBigVariable(key: String, value: String?) {
    RuleBigDataHelp.putChapterVariable(bookUrl, url, key, value)  // 存文件
}

override fun getBigVariable(key: String): String? {
    return RuleBigDataHelp.getChapterVariable(bookUrl, url, key)
}
```

**特点**：
- 小数据存在内存，序列化到 `BookChapter.variable` 数据库字段
- 大数据存到文件系统（按 `bookUrl/chapterUrl/key.txt` 路径）
- 适合存：章节图片列表、章节歌词、章节额外数据等
- **注意**：在函数回调或登录界面等地方，需要手动调用 `chapter.update()` 才能保存

---

### 写法五：`source.putVariable()` / `source.getVariable()`

**使用场景**：存整个书源的单一变量字符串（通常用于 JSON 格式）

```javascript
// 存整个变量字符串
source.putVariable(JSON.stringify({token: "abc", userId: "123"}));

// 取整个变量字符串
var variableStr = source.getVariable();
var data = JSON.parse(variableStr);
```

**底层实现**：
```kotlin
// BaseSource.kt
fun putVariable(variable: String?) {
    if (variable != null) {
        CacheManager.put("sourceVariable_${getKey()}", variable)
    }
}

fun getVariable(): String {
    return CacheManager.get("sourceVariable_${getKey()}") ?: ""
}
```

**特点**：
- 只存一个字符串，不区分 key
- 存到 `CacheManager`，持久化
- 适合存：书源的整体状态、登录信息等

---

### 写法六：规则表达式中的 `@get:变量名`

**使用场景**：在非 JS 规则表达式中引用之前存的变量

```
// 在正文提取规则中使用之前存的变量
@get:token

// 与其他规则组合使用
class.bookname @get:bookId

// 在 URL 规则中使用
https://api.example.com/book?id=@get:bookId
```

**底层实现**：
```kotlin
// AnalyzeRule.kt SourceRule 类
when {
    tmp.startsWith("@get:", true) -> {
        ruleType.add(getRuleType)  // -2
        ruleParam.add(tmp.substring(6, tmp.lastIndex))
    }
}

// 在 makeUpRule() 中替换
regType == getRuleType -> {
    infoVal.insert(0, get(ruleParam[index]))
}
```

**特点**：
- 只能在规则表达式字符串中使用（不能用在 `<js></js>` 代码块内）
- 会调用 `AnalyzeRule.get(key)` 方法，按优先级查找变量
- 适合：在 URL、XPath、JSONPath 等非 JS 规则中引用变量

---

### 写法七：规则表达式中的 `{{ JS表达式 }}`

**使用场景**：在规则表达式中嵌入 JS 代码，可以存取变量、执行计算

```
// 在正文规则中嵌入 JS
{{java.get("headerText") + result}}

// 执行复杂计算
{{parseInt(java.get("price")) * 0.8}}

// 存变量并返回值
{{java.put("count", String(parseInt(java.get("count")) + 1))}}

// 访问 book/chapter 对象
{{book.name + " - " + chapter.title}}

// 在 URL 规则中拼接
https://api.example.com/{{book.bookUrl}}/chapter/{{chapter.index}}
```

**底层实现**：
```kotlin
// AnalyzeRule.kt SourceRule 类
tmp.startsWith("{{") -> {
    ruleType.add(jsRuleType)  // -1
    ruleParam.add(tmp.substring(2, tmp.length - 2))
}

// 在 makeUpRule() 中执行 JS
regType == jsRuleType -> {
    when (val jsEval: Any? = evalJS(ruleParam[index], result)) {
        null -> Unit
        is String -> infoVal.insert(0, jsEval)
        is Double if jsEval % 1.0 == 0.0 -> infoVal.insert(0, String.format("%.0f", jsEval))
        else -> infoVal.insert(0, jsEval.toString())
    }
}
```

**特点**：
- `{{}}` 内的代码会被当作 JS 执行
- 可以调用 `java`、`book`、`chapter`、`source`、`cache` 等对象
- `result` 变量代表上一步规则的结果
- JS 返回值会自动插入到规则表达式中
- 适合：在规则表达式中进行条件判断、变量操作、字符串拼接等

---

### 写法八：规则表达式中的 `{"key":"value"}` JSON 格式存变量

**使用场景**：在规则表达式中同时提取数据并存储到变量

```
// 提取书名并存到变量
class.bookname{"bookName": "$.title"}

// 提取多个字段并存变量
a.book-list{"books": "$.list", "count": "$.total"}

// 与正则表达式组合
(.+?){"chapterTitle": "$1"}
```

**底层实现**：
```kotlin
// AnalyzeRule.kt
private val putPattern = Pattern.compile("\\{([^}]+)\\}")

private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
    val putMatcher = putPattern.matcher(ruleStr)
    while (putMatcher.find()) {
        vRuleStr = vRuleStr.replace(putMatcher.group(), "")
        val putJsonStr = putMatcher.group(1)
        val putJson = GSONStrict.fromJsonObject<Map<String, String>>(putJsonStr).getOrNull()
        if (putJson != null) {
            putMap.putAll(putJson)
        }
    }
    return vRuleStr
}

// 在解析规则时执行 put
private fun putRule(map: Map<String, String>) {
    for ((key, value) in map) {
        put(key, getString(value))  // value 可以是规则表达式
    }
}
```

**特点**：
- JSON 中的 key 是变量名，value 是提取规则（XPath、JSONPath、正则等）
- 提取结果会自动存到对应层级的变量中
- 适合：批量提取并存储多个字段

---

### 写法九：`cache.put()` / `cache.get()`

**使用场景**：需要精确控制缓存时间、跨书源共享的数据

```javascript
// 存变量，带过期时间（秒）
cache.put("tempData", "临时数据", 3600);  // 1小时后过期

// 存变量，永不过期
cache.put("permanentData", "永久数据");

// 存到文件（适合大数据）
cache.putFile("largeData", JSON.stringify(largeObject), 86400);

// 取变量（优先从内存读取）
var data = cache.get("tempData");

// 强制从磁盘读取
var data = cache.get("tempData", true);

// 取文件内容
var fileData = cache.getFile("largeData");

// 删除
cache.delete("tempData");

// 存到内存（最快，但不持久化）
cache.putMemory("fastData", someObject);
var fastData = cache.getFromMemory("fastData");
cache.deleteMemory("fastData");
```

**底层实现**：
```kotlin
// CacheManager.kt
fun put(key: String, value: String, saveTime: Int = 0)  // 保存到数据库和缓存文件
fun get(key: String, onlyDisk: Boolean = false): String? // 读取数据库
fun putFile(key: String, value: String, saveTime: Int)   // 缓存文件内容
fun getFile(key: String): String?                        // 读取文件内容
fun putMemory(key: String, value: Any)                   // 保存到内存
fun getFromMemory(key: String): Any?                     // 读取内存
fun delete(key: String)                                  // 删除
```

**特点**：
- 支持过期时间控制
- 支持内存/磁盘/文件三种存储层级
- 数据不绑定到书籍/章节/书源，完全自由
- 适合存：临时数据、跨书源共享数据、大文件缓存等

---

## 4️⃣ 所有写法对比总结

| 写法 | 存数据 | 取数据 | 存储位置 | 生命周期 | 适用场景 |
|------|--------|--------|---------|---------|---------|
| **① java.put/get** | `java.put("k","v")` | `java.get("k")` | 按优先级自动选择 | 按层级不同 | 最通用，JS规则中常用 |
| **② source.put/get** | `source.put("k","v")` | `source.get("k")` | CacheManager | 书源级别持久化 | 跨书籍的全局数据（如token） |
| **③ book.putVariable** | `book.putVariable("k","v")` | `book.getVariable("k")` | Book.variableMap | 书籍级别持久化 | 书籍专属数据 |
| **④ chapter.putVariable** | `chapter.putVariable("k","v")` | `chapter.getVariable("k")` | Chapter.variableMap | 章节级别 | 章节专属数据（如图片列表） |
| **⑤ source.putVariable** | `source.putVariable(jsonStr)` | `source.getVariable()` | CacheManager | 书源级别持久化 | 书源整体状态（JSON格式） |
| **⑥ @get:变量名** | - | `@get:token` | 按优先级查找 | 按层级不同 | 非JS规则中引用变量 |
| **⑦ {{ JS表达式 }}** | `{{java.put("k","v")}}` | `{{java.get("k")}}` | 按优先级自动选择 | 按层级不同 | 规则表达式中嵌入JS |
| **⑧ JSON格式** | `{"key": "$.path"}` | 自动提取并存储 | 按优先级自动选择 | 按层级不同 | 批量提取并存储字段 |
| **⑨ cache.put/get** | `cache.put("k","v",time)` | `cache.get("k")` | CacheManager | 可设过期时间 | 精确控制缓存/跨书源数据 |

---

## 5️⃣ 数据流全流程

```
JS 规则代码
    │
    ▼
evalJS() (临时作用域)
    │
    ├─ java.put() ──→ 存储系统(持久化)
    │                      │
    │                 ┌────┼────┐
    │                 ▼    ▼    ▼
    │              章节级 书籍级 书源级
    │              HashMap HashMap CacheManager
    │                 │    │      │
    │                 ▼    ▼      ▼
    │             BookChapter Book  持久化存储
    │
    ├─ var x = 1 ──→ 临时变量(用完就丢)
    │
    └─ {{java.get("k")}} ──→ @get:变量名
                                  │
                             JSON格式存储
                                  │
                             cache系统
```

---

## 6️⃣ 为什么这么设计？

从架构角度看，这种设计有几个深思熟虑的考虑：

### 1. 跨请求持久化

```
搜索 → 获得书籍列表 → 点击书籍 → 获取详情 → 获取目录 → 获取正文
  ↑        ↑            ↑          ↑         ↑          ↑
 都可能是不同的 HTTP 请求，每次都会创建新的 AnalyzeRule 实例
```

变量数据需要**跨多个请求、多个 AnalyzeRule 实例**存活。

### 2. 安全性

```kotlin
scope.preventExtensions()
```

防止恶意/有问题的规则代码污染全局命名空间，造成变量冲突。

### 3. 内存管理

每次 `evalJS` 都创建新作用域，执行完后 GC 可以回收。如果所有 JS 变量都长期存活，Android 设备的内存很快就撑爆了。

### 4. 作用域隔离

- **章节级变量**：只在本章节有效（比如当前章节的图片列表）
- **书籍级变量**：整本书共享（比如累计阅读字数）
- **书源级变量**：所有使用同个书源的书籍共享（比如登录 token）

### 5. 灵活选择

提供多种存取方式，让书源作者可以根据需求选择最合适的方式：
- 需要跨书源共享？用 `cache`
- 只需要书源级别？用 `source.put/get`
- 需要绑定到书籍？用 `book.putVariable`
- 在规则表达式中引用？用 `@get:` 或 `{{}}`

---

## 7️⃣ 实践对比

| 操作 | 普通 JS | Legado 规则 JS |
|-----|---------|---------------|
| 存字符串 | `let x = "hello"` | `java.put("x", "hello")` 或 `source.put("x", "hello")` |
| 读字符串 | `x` | `java.get("x")` 或 `source.get("x")` |
| 存数字 | `let n = 42` | `java.put("n", "42")` |
| 读数字 | `n` | `parseInt(java.get("n"))` |
| 存对象 | `let o = {a:1}` | `java.put("o", JSON.stringify({a:1}))` |
| 读对象 | `o.a` | `JSON.parse(java.get("o")).a` |
| 删变量 | `delete x` | `java.put("x", null)` 或 `cache.delete("x")` |
| 带过期时间 | - | `cache.put("x", "v", 3600)` |
| 规则中引用 | - | `@get:x` 或 `{{java.get("x")}}` |

**为什么这么麻烦？**

因为规则引擎的设计哲学是：**每个 JS 代码片段是一个"函数式"的纯计算单元**，输入 → 处理 → 输出。状态的持久化交给专门的存储层管理，而不是依赖 JS 作用域。

---

## 总结

1. **不能用 `var`/`let` 的原因**：每次 `evalJS()` 都是新的临时作用域，执行完就销毁
2. **共享作用域**（`SharedJsScope`）：用来加载 `jsLib` 工具库，且调用了 `preventExtensions()` 禁止新增变量
3. **多种存取方式**：
   - **JS 中**：`java.put/get`、`source.put/get`、`book.putVariable`、`chapter.putVariable`、`source.putVariable`、`cache.put/get`
   - **规则表达式中**：`@get:变量名`、`{{ JS表达式 }}`、`{"key": "规则"}` JSON 格式
4. **存储层级**：章节级 → 书籍级 → 规则数据级 → 书源级，按优先级自动选择
5. **设计目的**：持久化、安全隔离、内存管理、作用域隔离、灵活选择

虽然多了一步 `put`/`get` 的操作，但这换来了数据的**持久化存储**（App 重启还在）、**跨请求共享**、**过期时间控制**、以及**清晰的变量作用域**，对于书源规则这种需要多次网络请求、跨页面操作的场景来说，是更合适的设计。