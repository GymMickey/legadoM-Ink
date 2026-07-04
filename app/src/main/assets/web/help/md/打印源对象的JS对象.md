# 打印源对象的JS对象

本文档说明在legado项目中，如何在JS脚本中打印源对象及其内容。log()和toast()方法需要java.toast()和java.log()这么使用。

## 打印方法

### 1. 使用 `toast()` 或 `log()` 打印单个属性

```javascript
// 打印源名称
toast(source.bookSourceName)

// 打印源URL
toast(source.bookSourceUrl)

// 打印到调试日志
log(source.bookSourceName)
```

### 2. 打印整个源对象

由于源对象是Java对象（BookSource/RssSource），在JS中可以通过以下方式打印：

**方法一：手动拼接关键属性**
```javascript
var info = "书源名称: " + source.bookSourceName + "\n" +
           "书源URL: " + source.bookSourceUrl + "\n" +
           "书源分组: " + source.bookSourceGroup + "\n" +
           "书源类型: " + source.bookSourceType;
toast(info);
```

**方法二：使用JSON.stringify（需要先转为JS对象）**
```javascript
// 注意：直接对Java对象使用JSON.stringify可能不工作
// 需要手动构建JS对象
var sourceObj = {
    bookSourceName: source.bookSourceName,
    bookSourceUrl: source.bookSourceUrl,
    bookSourceGroup: source.bookSourceGroup,
    bookSourceType: source.bookSourceType,
    enabled: source.enabled,
    enabledExplore: source.enabledExplore
};
toast(JSON.stringify(sourceObj));
```

### 3. 使用源对象的方法

源对象提供了很多方法可以在JS中调用：

```javascript
// 获取源的key（URL）
toast(source.getKey());

// 获取源的标签（名称）
toast(source.getTag());

// 获取自定义变量
toast(source.getVariable());

// 获取登录头部信息
toast(source.getLoginHeader());

// 获取用户登录信息
toast(source.getLoginInfo());
```

## 源对象包含的内容

根据 `BookSource.kt`，源对象包含以下主要字段：

### 基本字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `bookSourceUrl` | String | 书源地址（主键） |
| `bookSourceName` | String | 书源名称 |
| `bookSourceGroup` | String? | 分组（逗号分隔） |
| `bookSourceType` | Int | 类型（0文本/1音频/2图片/3文件/4视频） |
| `enabled` | Boolean | 是否启用 |
| `enabledExplore` | Boolean | 是否启用发现 |
| `customOrder` | Int | 手动排序编号 |

### 规则字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `searchUrl` | String? | 搜索URL |
| `exploreUrl` | String? | 发现URL |
| `ruleSearch` | SearchRule? | 搜索规则 |
| `ruleExplore` | ExploreRule? | 发现规则 |
| `ruleBookInfo` | BookInfoRule? | 书籍信息规则 |
| `ruleToc` | TocRule? | 目录规则 |
| `ruleContent` | ContentRule? | 正文规则 |
| `ruleReview` | ReviewRule? | 段评规则 |

### 登录相关字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `loginUrl` | String? | 登录地址 |
| `loginUi` | String? | 登录UI |
| `loginCheckJs` | String? | 登录检测JS |
| `header` | String? | 请求头 |

### 其他字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `jsLib` | String? | JS库 |
| `concurrentRate` | String? | 并发率 |
| `enabledCookieJar` | Boolean? | 启用CookieJar |
| `lastUpdateTime` | Long | 最后更新时间 |
| `respondTime` | Long | 响应时间 |
| `weight` | Int | 智能排序权重 |

## 源对象的主要方法

### 变量存储方法

| 方法 | 说明 |
|------|------|
| `put(key, value)` | 保存键值对到源变量 |
| `get(key)` | 获取源变量值 |
| `getVariable()` | 获取自定义变量 |
| `putVariable(variable)` | 设置自定义变量 |

### 登录相关方法

| 方法 | 说明 |
|------|------|
| `getLoginHeader()` | 获取登录头部信息 |
| `putLoginHeader(header)` | 保存登录头部信息 |
| `getLoginInfo()` | 获取用户登录信息 |
| `putLoginInfo(info)` | 保存用户登录信息 |
| `login()` | 执行登录 |

### 其他方法

| 方法 | 说明 |
|------|------|
| `getKey()` | 获取源URL |
| `getTag()` | 获取源名称 |
| `getHeaderMap()` | 获取请求头Map |
| `evalJS(jsStr)` | 执行JS脚本 |

## 完整示例

### 示例1：打印源的基本信息

```javascript
var info = "=== 书源信息 ===\n";
info += "名称: " + source.bookSourceName + "\n";
info += "URL: " + source.bookSourceUrl + "\n";
info += "分组: " + source.bookSourceGroup + "\n";
info += "类型: " + source.bookSourceType + "\n";
info += "启用状态: " + source.enabled + "\n";
info += "发现启用: " + source.enabledExplore + "\n";

toast(info);
```

### 示例2：打印源的所有信息（包括变量）

```javascript
var info = "=== 书源完整信息 ===\n";
info += "名称: " + source.bookSourceName + "\n";
info += "URL: " + source.bookSourceUrl + "\n";
info += "分组: " + source.bookSourceGroup + "\n";
info += "类型: " + source.bookSourceType + "\n";
info += "启用状态: " + source.enabled + "\n";
info += "发现启用: " + source.enabledExplore + "\n";

// 打印自定义变量
var variable = source.getVariable();
if (variable) {
    info += "自定义变量: " + variable + "\n";
}

// 打印登录信息
var loginHeader = source.getLoginHeader();
if (loginHeader) {
    info += "登录头部: " + loginHeader + "\n";
}

// 打印请求头
var header = source.header;
if (header) {
    info += "请求头: " + header + "\n";
}

// 使用toast显示
toast(info);

// 或者使用log输出到调试日志
log(info);
```

### 示例3：在规则中使用

```javascript
// 在搜索规则中打印调试信息
var searchUrl = source.searchUrl;
log("搜索URL: " + searchUrl);

// 在发现规则中
var exploreUrl = source.exploreUrl;
log("发现URL: " + exploreUrl);

// 打印规则配置
var ruleSearch = source.ruleSearch;
if (ruleSearch) {
    log("搜索列表规则: " + ruleSearch.bookList);
    log("书名规则: " + ruleSearch.name);
    log("作者规则: " + ruleSearch.author);
}
```

### 示例4：使用源变量存储和读取数据

```javascript
// 保存数据
source.put("lastSearchTime", new Date().getTime().toString());
source.put("searchCount", "10");

// 读取数据
var lastTime = source.get("lastSearchTime");
var count = source.get("searchCount");

log("上次搜索时间: " + lastTime);
log("搜索次数: " + count);
```

## 相关源文件

- `app/src/main/java/io/legado/app/help/JsExtensions.kt` - JS扩展方法（`toast`, `log`等）
- `app/src/main/java/io/legado/app/data/entities/BaseSource.kt` - 源基类（提供`get`, `put`, `getVariable`等方法）
- `app/src/main/java/io/legado/app/data/entities/BookSource.kt` - 书源实体类
- `app/src/main/assets/defaultData/bookSources.json` - 书源JSON示例

## 注意事项

1. **toast() vs log()**
   - `toast()` 会在界面上弹出提示，适合调试时使用
   - `log()` 输出到调试日志，不会打扰用户，适合正式使用

2. **Java对象转JSON**
   - 源对象是Java对象，不能直接使用`JSON.stringify()`
   - 需要手动构建JS对象后再序列化

3. **只读属性**
   - 源对象的大部分属性是只读的（通过`ReadOnlyJavaObject`包装）
   - 不能直接修改源对象的属性，需要通过相应的方法

4. **变量存储**
   - 使用`source.put(key, value)`存储的数据会持久化
   - 数据存储在`CacheManager`中，key格式为`v_${sourceKey}_${key}`

5. **调试位置**
   - `toast()`和`log()`会显示当前执行的规则类型和行号
   - 方便定位问题所在的具体规则
