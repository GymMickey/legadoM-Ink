# 封面 HTML 预览离屏渲染方案

## 背景

`自动生成封面代码` 页面需要预览用户编写的 HTML 封面模板。原实现直接在 Compose 页面中嵌入 `WebView` 做预览，但实际使用中出现预览区域始终显示白色背景的问题，即使 HTML 模板中已经设置了紫色渐变背景。

## 问题表现

- 代码编辑区中的 HTML 已经包含非白色背景。
- 点击预览后，预览框仍然显示白色。
- 直接刷新 `WebView`、调整 `loadDataWithBaseURL`、切换 `WebView` 创建 Context 后，问题仍存在。

## 原因判断

这个问题不是 HTML 模板内容本身的问题，而是 Compose 中直接嵌入 `WebView` 做实时预览时，`WebView` 的页面加载、绘制、附着时机不稳定，导致预览容器只显示默认白色背景。

此外，早期实现还有一个独立问题：`CodeView` 中的编辑内容没有实时同步回 `htmlCode` 状态，导致预览和保存可能使用旧代码。这个问题需要同时修复。

## 最终方案

不再把 `WebView` 作为预览控件直接显示，而是：

1. 用户点击预览时，将当前 HTML 模板替换变量，得到完整 HTML。
2. 创建一个临时离屏 `WebView`。
3. 将离屏 `WebView` 固定测量和布局为 `600x900`。
4. 调用 `loadDataWithBaseURL` 加载 HTML。
5. 等待 `onPageFinished` 后延迟一小段时间，让 CSS 和 JS 有机会完成布局。
6. 将 `WebView` 绘制到 `Bitmap`。
7. 销毁临时 `WebView`。
8. 在 Compose 预览区用 `Image` 显示生成的 `Bitmap`。

这样预览区不再依赖可见 `WebView` 的实时绘制，白色背景问题被绕开，预览结果也更接近实际封面生成逻辑。

## 关键实现点

`CodeView` 内容同步：

```kotlin
doAfterTextChanged { text ->
    val newCode = text?.toString().orEmpty()
    if (newCode != htmlCode) {
        htmlCode = newCode
    }
}
```

预览区显示 Bitmap：

```kotlin
previewBitmap?.let { bitmap ->
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.cover_html_preview),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
} ?: CircularProgressIndicator()
```

离屏渲染核心：

```kotlin
private suspend fun renderCoverPreviewBitmap(context: Context, html: String): Bitmap? {
    val renderWidth = 600
    val renderHeight = 900
    return withContext(Dispatchers.Main) {
        var webView: WebView? = null
        try {
            var renderComplete = false
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(false)
                settings.displayZoomControls = false
                setInitialScale(100)
                setBackgroundColor(Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.postDelayed({
                            renderComplete = true
                        }, 300)
                    }
                }
            }

            webView.measure(
                View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(renderHeight, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, renderWidth, renderHeight)
            webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)

            var attempts = 0
            while (!renderComplete && attempts < 40) {
                delay(50)
                attempts++
            }

            webView.measure(
                View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(renderHeight, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, renderWidth, renderHeight)

            createBitmap(renderWidth, renderHeight).also { bitmap ->
                webView.draw(Canvas(bitmap))
            }
        } finally {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}
```

## 注意事项

- 离屏 `WebView` 必须在主线程创建、加载和绘制。
- 固定使用 `600x900`，避免小预览框导致 CSS 视口过小。
- 预览不应在每个字符输入时立即渲染，否则会频繁创建 `WebView`。当前方案只在初始化、切换模板或点击预览时刷新。
- 临时 `WebView` 使用后必须 `destroy()`，避免资源泄漏。
- 这个方案适合 HTML 封面预览；如果未来要做实时高频预览，应考虑 debounce 或复用单个离屏 WebView。
