# Legado 书源规则 - Data URL 使用指南

本文档介绍 Legado 阅读软件中 `data:` URL 的支持和使用方法，供书源规则开发者参考。

---

## 目录

1. [概述](#1-概述)
2. [Data URL 格式](#2-data-url-格式)
3. [使用场景](#3-使用场景)
4. [书源规则中的使用方法](#4-书源规则中的使用方法)
5. [示例](#5-示例)
6. [注意事项](#6-注意事项)

---

## 1. 概述

Legado 的书源规则**完整支持** `data:` 类型的 URL 传输。这意味着你可以：

- 直接在规则中嵌入 HTML 内容，无需网络请求
- 嵌入 Base64 编码的图片数据
- 构造虚拟页面用于测试或特殊处理

---

## 2. Data URL 格式

### 标准格式

```
data:[<mediatype>][;base64],<data>
```

### 常见类型

| MIME 类型 | 说明 | 示例 |
|-----------|------|------|
| `text/html` | HTML 文档 | `data:text/html,<h1>Hello</h1>` |
| `text/html;charset=utf-8` | UTF-8 编码的 HTML | `data:text/html;charset=utf-8,<h1>中文</h1>` |
| `text/plain` | 纯文本 | `data:text/plain,Hello World` |
| `image/png` | PNG 图片（Base64） | `data:image/png;base64,iVBORw0KGgo...` |
| `image/jpeg` | JPEG 图片（Base64） | `data:image/jpeg;base64,/9j/4AAQSkZJRg...` |
| `application/json` | JSON 数据 | `data:application/json,{"key":"value"}` |

---

## 3. 使用场景

### 场景 1：嵌入测试 HTML

当目标网站结构复杂或需要调试时，可直接嵌入简化版 HTML：

```
data:text/html;charset=utf-8;base64,PGh0bWw+PGJvZHk+PGRpdiBjbGFzcz0iYm9vayI+PGgzPuWtseS4gOa1ty</div></body></html>
```

### 场景 2：嵌入图片数据

某些书源需要显示内嵌图片，可直接使用 Base64 编码：

```
data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==
```

### 场景 3：构造虚拟目录页

对于某些特殊书源，可以动态构造目录 HTML：

```
data:text/html;charset=utf-8,<ul><li><a href="/chapter/1">第一章</a></li><li><a href="/chapter/2">第二章</a></li></ul>
```

### 场景 4：配合 JS 处理

在书源规则中通过 JS 动态生成 data URL：

```javascript
// 构造 data URL
var html = "<div class='book-list'>" + bookItems + "</div>";
var base64 = java.base64Encode(html);
var dataUrl = "data:text/html;charset=utf-8;base64," + base64;
```

---

## 4. 书源规则中的使用方法

### 4.1 搜索规则中使用

```json
{
  "bookSourceUrl": "https://example.com",
  "bookSourceName": "测试书源",
  "searchUrl": "data:text/html;charset=utf-8;base64,PGh0bWw+...",
  "ruleSearch": {
    "bookList": "class.book-list",
    "name": "tag.h3@text",
    "author": "class.author@text",
    "bookUrl": "tag.a@href"
  }
}
```

### 4.2 目录规则中使用

```json
{
  "ruleToc": {
    "chapterList": "tag.li",
    "chapterName": "tag.a@text",
    "chapterUrl": "tag.a@href"
  },
  "exploreUrl": "data:text/html;charset=utf-8,<ul><li><a href='/c1'>第一章</a></li></ul>"
}
```

### 4.3 配合 URL 参数使用

可以通过 URL 参数指定返回类型：

```
data:text/html;charset=utf-8;base64,PGh0bWw+...,{"type":"text/html"}
```

参数说明：

| 参数 | 类型 | 说明 |
|------|------|------|
| `type` | string | 指定 MIME 类型，影响解析方式 |
| `charset` | string | 指定字符编码（已在 data URL 中包含） |

---

## 5. 示例

### 示例 1：简单的搜索列表

```json
{
  "bookSourceUrl": "data:source",
  "bookSourceName": "Data URL 测试书源",
  "searchUrl": "data:text/html;charset=utf-8,<div class='search-result'><div class='book'><h3>测试书籍</h3><span class='author'>测试作者</span><a href='data:book/1'>详情</a></div></div>",
  "ruleSearch": {
    "bookList": "class.search-result@div.book",
    "name": "tag.h3@text",
    "author": "class.author@text",
    "bookUrl": "tag.a@href"
  }
}
```

### 示例 2：带图片的搜索结果

```json
{
  "searchUrl": "data:text/html;charset=utf-8,<div class='book'><img src='data:image/png;base64,iVBORw0KGgo...'><h3>书名</h3></div>",
  "ruleSearch": {
    "bookList": "class.book",
    "name": "tag.h3@text",
    "coverUrl": "img@src"
  }
}
```

### 示例 3：动态生成目录

```javascript
// 在书源规则中使用 JS 生成目录
(function() {
  var chapters = [];
  for (var i = 1; i <= 100; i++) {
    chapters.push('<li><a href="/chapter/' + i + '">第' + i + '章</a></li>');
  }
  var html = '<ul>' + chapters.join('') + '</ul>';
  var base64 = java.base64Encode(html);
  return "data:text/html;charset=utf-8;base64," + base64;
})()
```

### 示例 4：配合 WebView 使用

```json
{
  "searchUrl": "data:text/html;charset=utf-8,<div id='content'>需要JS渲染的内容</div>,{\"webView\":true,\"webJs\":\"document.getElementById('content').innerText\"}"
}
```

---

## 6. 注意事项

### 6.1 长度限制

- Data URL 没有硬性长度限制，但建议控制在合理范围内
- 过长的 data URL 可能影响性能和可读性
- 大量数据建议仍使用网络请求

### 6.2 Base64 编码

- 中文内容建议先进行 Base64 编码，避免字符问题
- 使用 `java.base64Encode()` 或在线工具进行编码

```javascript
// 编码示例
var html = "<div>中文内容</div>";
var base64 = java.base64Encode(html);  // 返回 Base64 字符串
var dataUrl = "data:text/html;charset=utf-8;base64," + base64;
```

### 6.3 字符编码

- 建议始终指定 `charset=utf-8` 确保中文正常显示
- 格式：`data:text/html;charset=utf-8,内容`
- 或：`data:text/html;charset=utf-8;base64,Base64编码内容`

### 6.4 调试技巧

1. **验证 Data URL 格式**：
   - 确保以 `data:` 开头
   - MIME 类型正确
   - Base64 编码完整（无换行、无空格）

2. **在线工具**：
   - Base64 编码/解码：https://base64.guru
   - Data URL 生成器：https://dopiaza.org/tools/datauri

3. **在浏览器中测试**：
   - 将 data URL 粘贴到浏览器地址栏验证

### 6.5 性能考虑

| 场景 | 建议 |
|------|------|
| 小量 HTML (<10KB) | 适合使用 data URL |
| 大量 HTML (>50KB) | 建议使用网络请求或本地文件 |
| 图片资源 | 小图标可用 data URL，大图建议用 URL |
| 动态内容 | 使用 JS 生成 data URL |

---

## 7. 完整书源示例

```json
{
  "bookSourceUrl": "data:test",
  "bookSourceName": "Data URL Demo",
  "bookSourceGroup": "Demo",
  "bookSourceType": 0,
  "searchUrl": "data:text/html;charset=utf-8,<div class='search-list'><div class='book-item'><h3 class='title'>斗破苍穹</h3><span class='author'>天蚕土豆</span><span class='tag'>玄幻</span><a class='link' href='data:book/1'>详情</a></div></div>",
  "ruleSearch": {
    "bookList": "class.search-list@div.book-item",
    "name": "class.title@text",
    "author": "class.author@text",
    "kind": "class.tag@text",
    "bookUrl": "class.link@href"
  },
  "ruleBookInfo": {
    "init": "(function(){return 'data:text/html;charset=utf-8,<div class=\\'book-info\\'><h1>斗破苍穹</h1><p class=\\'intro\\'>这里是简介</p></div>';})()",
    "name": "tag.h1@text",
    "intro": "class.intro@text"
  },
  "ruleToc": {
    "chapterList": "tag.li",
    "chapterName": "tag.a@text",
    "chapterUrl": "tag.a@href"
  },
  "exploreUrl": "data:text/html;charset=utf-8,<ul class='toc'><li><a href='data:chapter/1'>第一章 起始</a></li><li><a href='data:chapter/2'>第二章 发展</a></li></ul>"
}
```

---

## 8. 相关参考

- [MDN Data URLs](https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Basics_of_HTTP/Data_URLs)
- [Base64 编码规范](https://tools.ietf.org/html/rfc4648)
- Legado 书源规则文档

---

**提示**：Data URL 是 Legado 书源规则的高级特性，适合用于测试、特殊处理或优化特定场景。合理使用可以大大增强书源的灵活性。
