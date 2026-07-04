# Legado 阅读项目数据对象 - JS格式文档

本文档将 Legado 项目中的数据对象转换为 JavaScript 格式，并以表格形式展示各列表对象的结构。

---

## 1. 搜索列表对象

### SearchBook - 搜索书籍结果

| 字段名 | 类型 | 说明 | JS对象示例 |
|--------|------|------|-----------|
| bookUrl | string | 书籍详情页URL（主键） | `bookUrl: "https://example.com/book/123"` |
| origin | string | 书源URL | `origin: "https://booksource.com"` |
| originName | string | 书源名称 | `originName: "示例书源"` |
| type | number | 书籍类型（0文本/1音频/2图片/3文件/4视频） | `type: 0` |
| name | string | 书名 | `name: "斗破苍穹"` |
| author | string | 作者 | `author: "天蚕土豆"` |
| kind | string/null | 分类/标签 | `kind: "玄幻,修真"` |
| coverUrl | string/null | 封面URL | `coverUrl: "https://example.com/cover.jpg"` |
| intro | string/null | 简介 | `intro: "这里是简介..."` |
| wordCount | string/null | 字数 | `wordCount: "100万字"` |
| latestChapterTitle | string/null | 最新章节标题 | `latestChapterTitle: "第100章 大结局"` |
| tocUrl | string | 目录页URL | `tocUrl: "https://example.com/book/123/catalog"` |
| time | number | 搜索时间（时间戳） | `time: 1716012345678` |
| variable | string/null | 自定义变量（JSON字符串） | `variable: "{\"key\":\"value\"}"` |
| originOrder | number | 书源排序 | `originOrder: 0` |
| chapterWordCountText | string/null | 章节字数文本 | `chapterWordCountText: "3000字/章"` |
| chapterWordCount | number | 章节字数 | `chapterWordCount: 3000` |
| respondTime | number | 响应时间（毫秒） | `respondTime: 150` |

#### JS对象示例

```javascript
const searchBook = {
  bookUrl: "https://example.com/book/123",
  origin: "https://booksource.com",
  originName: "示例书源",
  type: 0,
  name: "斗破苍穹",
  author: "天蚕土豆",
  kind: "玄幻,修真",
  coverUrl: "https://example.com/cover.jpg",
  intro: "这里是简介...",
  wordCount: "100万字",
  latestChapterTitle: "第100章 大结局",
  tocUrl: "https://example.com/book/123/catalog",
  time: 1716012345678,
  variable: null,
  originOrder: 0,
  chapterWordCountText: null,
  chapterWordCount: -1,
  respondTime: -1
};
```

#### 搜索列表数组示例

```javascript
const searchBookList = [
  {
    bookUrl: "https://source1.com/book/1",
    origin: "https://source1.com",
    originName: "书源A",
    type: 0,
    name: "斗破苍穹",
    author: "天蚕土豆",
    kind: "玄幻",
    coverUrl: "https://source1.com/cover1.jpg",
    intro: "简介1...",
    wordCount: "100万字",
    latestChapterTitle: "第100章",
    tocUrl: "https://source1.com/book/1/toc",
    time: 1716012345678,
    variable: null,
    originOrder: 1,
    chapterWordCountText: null,
    chapterWordCount: -1,
    respondTime: 120
  },
  {
    bookUrl: "https://source2.com/book/2",
    origin: "https://source2.com",
    originName: "书源B",
    type: 0,
    name: "斗破苍穹",
    author: "天蚕土豆",
    kind: "玄幻",
    coverUrl: "https://source2.com/cover2.jpg",
    intro: "简介2...",
    wordCount: "100万字",
    latestChapterTitle: "第100章",
    tocUrl: "https://source2.com/book/2/toc",
    time: 1716012345678,
    variable: null,
    originOrder: 2,
    chapterWordCountText: null,
    chapterWordCount: -1,
    respondTime: 200
  }
];
```

---

## 2. 发现列表对象

发现列表与搜索列表共用 **SearchBook** 对象结构，区别在于数据来源和规则不同。

| 特性 | 说明 |
|------|------|
| 数据对象 | SearchBook（同上） |
| 数据来源 | 书源预设的发现URL |
| 规则对象 | ExploreRule |

### ExploreRule - 发现规则

| 字段名 | 类型 | 说明 | JS对象示例 |
|--------|------|------|-----------|
| bookList | string/null | 书籍列表规则 | `bookList: "class.book-list@tag.a"` |
| name | string/null | 书名规则 | `name: "tag.h3@text"` |
| author | string/null | 作者规则 | `author: "class.author@text"` |
| intro | string/null | 简介规则 | `intro: "class.intro@text"` |
| kind | string/null | 分类规则 | `kind: "class.tag@text"` |
| lastChapter | string/null | 最新章节规则 | `lastChapter: "class.last-chapter@text"` |
| updateTime | string/null | 更新时间规则 | `updateTime: "class.time@text"` |
| bookUrl | string/null | 书籍URL规则 | `bookUrl: "href"` |
| coverUrl | string/null | 封面URL规则 | `coverUrl: "img@src"` |
| wordCount | string/null | 字数规则 | `wordCount: "class.word-count@text"` |

#### JS对象示例

```javascript
const exploreRule = {
  bookList: "class.book-list@tag.a",
  name: "tag.h3@text",
  author: "class.author@text",
  intro: "class.intro@text",
  kind: "class.tag@text",
  lastChapter: "class.last-chapter@text",
  updateTime: "class.time@text",
  bookUrl: "href",
  coverUrl: "img@src",
  wordCount: "class.word-count@text"
};
```

---

## 3. 目录列表对象

### BookChapter - 书籍章节

| 字段名 | 类型 | 说明 | JS对象示例 |
|--------|------|------|-----------|
| url | string | 章节地址 | `url: "https://example.com/chapter/1"` |
| title | string | 章节标题 | `title: "第一章 起始"` |
| isVolume | boolean | 是否是卷名 | `isVolume: false` |
| baseUrl | string | 基础URL | `baseUrl: "https://example.com"` |
| bookUrl | string | 书籍地址 | `bookUrl: "https://example.com/book/123"` |
| index | number | 章节序号（从0开始） | `index: 0` |
| isVip | boolean | 是否VIP章节 | `isVip: false` |
| isPay | boolean | 是否已购买 | `isPay: false` |
| resourceUrl | string/null | 音频真实URL | `resourceUrl: null` |
| tag | string/null | 更新时间或其他信息 | `tag: "2024-01-01"` |
| wordCount | string/null | 本章节字数 | `wordCount: "3000"` |
| start | number/null | 章节起始位置 | `start: null` |
| end | number/null | 章节终止位置 | `end: null` |
| startFragmentId | string/null | EPUB当前章节fragmentId | `startFragmentId: null` |
| endFragmentId | string/null | EPUB下一章节fragmentId | `endFragmentId: null` |
| variable | string/null | 变量（JSON） | `variable: null` |
| imgUrl | string/null | 标题图片URL | `imgUrl: null` |

#### JS对象示例

```javascript
const bookChapter = {
  url: "https://example.com/chapter/1",
  title: "第一章 起始",
  isVolume: false,
  baseUrl: "https://example.com",
  bookUrl: "https://example.com/book/123",
  index: 0,
  isVip: false,
  isPay: false,
  resourceUrl: null,
  tag: "2024-01-01",
  wordCount: "3000",
  start: null,
  end: null,
  startFragmentId: null,
  endFragmentId: null,
  variable: null,
  imgUrl: null
};
```

#### 目录列表数组示例

```javascript
const chapterList = [
  {
    url: "https://example.com/chapter/1",
    title: "第一章 起始",
    isVolume: false,
    baseUrl: "https://example.com",
    bookUrl: "https://example.com/book/123",
    index: 0,
    isVip: false,
    isPay: false,
    resourceUrl: null,
    tag: "2024-01-01",
    wordCount: "3000",
    start: null,
    end: null,
    startFragmentId: null,
    endFragmentId: null,
    variable: null,
    imgUrl: null
  },
  {
    url: "https://example.com/chapter/2",
    title: "第二章 发展",
    isVolume: false,
    baseUrl: "https://example.com",
    bookUrl: "https://example.com/book/123",
    index: 1,
    isVip: false,
    isPay: false,
    resourceUrl: null,
    tag: "2024-01-02",
    wordCount: "3500",
    start: null,
    end: null,
    startFragmentId: null,
    endFragmentId: null,
    variable: null,
    imgUrl: null
  },
  {
    url: "第一卷",
    title: "第一卷 初入江湖",
    isVolume: true,
    baseUrl: "https://example.com",
    bookUrl: "https://example.com/book/123",
    index: 2,
    isVip: false,
    isPay: false,
    resourceUrl: null,
    tag: null,
    wordCount: null,
    start: null,
    end: null,
    startFragmentId: null,
    endFragmentId: null,
    variable: null,
    imgUrl: null
  }
];
```

---

## 4. 书源规则对象

### SearchRule - 搜索规则

| 字段名 | 类型 | 说明 | JS对象示例 |
|--------|------|------|-----------|
| checkKeyWord | string/null | 校验关键字 | `checkKeyWord: "斗破"` |
| bookList | string/null | 书籍列表规则 | `bookList: "class.search-list@tag.a"` |
| name | string/null | 书名规则 | `name: "tag.h3@text"` |
| author | string/null | 作者规则 | `author: "class.author@text"` |
| intro | string/null | 简介规则 | `intro: "class.intro@text"` |
| kind | string/null | 分类规则 | `kind: "class.tag@text"` |
| lastChapter | string/null | 最新章节规则 | `lastChapter: "class.last@text"` |
| updateTime | string/null | 更新时间规则 | `updateTime: "class.time@text"` |
| bookUrl | string/null | 书籍URL规则 | `bookUrl: "href"` |
| coverUrl | string/null | 封面URL规则 | `coverUrl: "img@src"` |
| wordCount | string/null | 字数规则 | `wordCount: "class.count@text"` |

#### JS对象示例

```javascript
const searchRule = {
  checkKeyWord: "斗破",
  bookList: "class.search-list@tag.a",
  name: "tag.h3@text",
  author: "class.author@text",
  intro: "class.intro@text",
  kind: "class.tag@text",
  lastChapter: "class.last@text",
  updateTime: "class.time@text",
  bookUrl: "href",
  coverUrl: "img@src",
  wordCount: "class.count@text"
};
```

---

### TocRule - 目录规则

| 字段名 | 类型 | 说明 | JS对象示例 |
|--------|------|------|-----------|
| preUpdateJs | string/null | 更新前执行JS | `preUpdateJs: null` |
| chapterList | string/null | 章节列表规则 | `chapterList: "class.chapter-list@tag.a"` |
| chapterName | string/null | 章节名称规则 | `chapterName: "text"` |
| chapterUrl | string/null | 章节URL规则 | `chapterUrl: "href"` |
| formatJs | string/null | 格式化JS | `formatJs: null` |
| isVolume | string/null | 是否卷名规则 | `isVolume: "class.volume"` |
| isVip | string/null | 是否VIP规则 | `isVip: "class.vip"` |
| isPay | string/null | 是否已购买规则 | `isPay: "class.pay"` |
| updateTime | string/null | 更新时间规则 | `updateTime: "class.time@text"` |
| nextTocUrl | string/null | 下一页目录URL规则 | `nextTocUrl: "class.next@href"` |

#### JS对象示例

```javascript
const tocRule = {
  preUpdateJs: null,
  chapterList: "class.chapter-list@tag.a",
  chapterName: "text",
  chapterUrl: "href",
  formatJs: null,
  isVolume: "class.volume",
  isVip: "class.vip",
  isPay: "class.pay",
  updateTime: "class.time@text",
  nextTocUrl: "class.next@href"
};
```

---

## 5. 完整数据对应关系表

| 功能模块 | 列表对象 | 规则对象 | 说明 |
|----------|----------|----------|------|
| 搜索列表 | SearchBook | SearchRule | 根据关键词搜索书籍 |
| 发现列表 | SearchBook | ExploreRule | 书源预设分类浏览 |
| 目录列表 | BookChapter | TocRule | 书籍章节列表 |

---

## 6. 类型常量对照表

| 常量名 | 值 | 说明 |
|--------|-----|------|
| BookType.text | 0 | 文本书籍 |
| BookType.audio | 1 | 音频书籍 |
| BookType.image | 2 | 图片书籍（漫画） |
| BookType.file | 3 | 文件书籍 |
| BookType.video | 4 | 视频书籍 |

---

## 7. 数据流转示例

```javascript
// 1. 搜索请求
const searchKeyword = "斗破苍穹";

// 2. 搜索返回结果（SearchBook数组）
const searchResults = [
  {
    bookUrl: "https://source1.com/book/1",
    origin: "https://source1.com",
    originName: "书源A",
    type: 0,
    name: "斗破苍穹",
    author: "天蚕土豆",
    kind: "玄幻",
    coverUrl: "https://source1.com/cover.jpg",
    intro: "这里是简介...",
    wordCount: "100万字",
    latestChapterTitle: "第100章 大结局",
    tocUrl: "https://source1.com/book/1/toc",
    time: 1716012345678,
    variable: null,
    originOrder: 1,
    chapterWordCountText: null,
    chapterWordCount: -1,
    respondTime: 120
  }
];

// 3. 获取目录返回结果（BookChapter数组）
const tocResults = [
  {
    url: "https://source1.com/chapter/1",
    title: "第一章 起始",
    isVolume: false,
    baseUrl: "https://source1.com",
    bookUrl: "https://source1.com/book/1",
    index: 0,
    isVip: false,
    isPay: false,
    resourceUrl: null,
    tag: "2024-01-01",
    wordCount: "3000",
    start: null,
    end: null,
    startFragmentId: null,
    endFragmentId: null,
    variable: null,
    imgUrl: null
  }
  // ... 更多章节
];
```
