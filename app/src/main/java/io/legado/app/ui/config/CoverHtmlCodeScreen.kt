package io.legado.app.ui.config

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.help.DefaultData
import io.legado.app.help.config.CoverHtmlTemplateConfig
import io.legado.app.constant.EventBus
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addHtmlPattern
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.components.dialog.AppConfirmDialog
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverHtmlCodeScreen(
    template: CoverHtmlTemplateConfig.Template?,
    isNewTemplate: Boolean,
    onBackClick: () -> Unit,
    onShowTemplateList: () -> Unit
) {
    val context = LocalContext.current
    val containerColor = coverHtmlCardContainerColor()
    val topBarColor = coverHtmlTopBarContainerColor()
    
    var templateName by remember { mutableStateOf("") }
    var htmlCode by remember { mutableStateOf("") }
    var bookName by remember { mutableStateOf("示例书名") }
    var author by remember { mutableStateOf("示例作者") }
    
    var originalName by remember { mutableStateOf("") }
    var originalHtmlCode by remember { mutableStateOf("") }
    
    var currentTemplate by remember { mutableStateOf(template) }
    var currentIsNewTemplate by remember { mutableStateOf(isNewTemplate) }
    
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingTemplateSwitch by remember { mutableStateOf<CoverHtmlTemplateConfig.Template?>(null) }
    
    var codeView by remember { mutableStateOf<CodeView?>(null) }
    var previewVersion by remember { mutableIntStateOf(0) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(currentTemplate, currentIsNewTemplate) {
        if (currentIsNewTemplate) {
            templateName = ""
            htmlCode = DefaultData.coverHtmlTemplate
            originalName = ""
            originalHtmlCode = DefaultData.coverHtmlTemplate
        } else {
            val t = currentTemplate ?: CoverHtmlTemplateConfig.getSelectedTemplate()
            templateName = t.name
            htmlCode = t.htmlCode
            originalName = t.name
            originalHtmlCode = t.htmlCode
            currentTemplate = t
        }
    }
    
    fun previewCover() {
        previewVersion++
    }
    
    fun doSaveTemplate() {
        if (htmlCode.isNotBlank()) {
            if (currentIsNewTemplate) {
                val newTemplate = CoverHtmlTemplateConfig.Template(
                    id = CoverHtmlTemplateConfig.generateId(),
                    name = templateName.ifEmpty { "未命名模板" },
                    htmlCode = htmlCode,
                    isSelected = true
                )
                CoverHtmlTemplateConfig.addTemplate(newTemplate)
                CoverHtmlTemplateConfig.setSelectedTemplate(newTemplate.id)
                currentTemplate = newTemplate
                currentIsNewTemplate = false
            } else {
                val existingTemplate = currentTemplate?.copy(
                    name = templateName.ifEmpty { "未命名模板" },
                    htmlCode = htmlCode
                )
                if (existingTemplate != null) {
                    CoverHtmlTemplateConfig.updateTemplate(existingTemplate)
                    currentTemplate = existingTemplate
                }
            }
            originalName = templateName
            originalHtmlCode = htmlCode
            CoverImageView.clearHtmlCoverCache()
            postEvent(EventBus.COVER_HTML_TEMPLATE_CHANGED, "")
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }
    
    if (showSaveDialog && pendingTemplateSwitch != null) {
        AppConfirmDialog(
            title = stringResource(R.string.cover_html_save_changes),
            text = stringResource(R.string.cover_html_unsaved_hint),
            confirmText = stringResource(R.string.action_save),
            dismissText = stringResource(R.string.discard),
            onConfirm = {
                doSaveTemplate()
                currentTemplate = pendingTemplateSwitch
                currentIsNewTemplate = false
                showSaveDialog = false
                pendingTemplateSwitch = null
            },
            onDismissRequest = {
                currentTemplate = pendingTemplateSwitch
                currentIsNewTemplate = false
                showSaveDialog = false
                pendingTemplateSwitch = null
            }
        )
    }

    LaunchedEffect(currentTemplate, currentIsNewTemplate) {
        previewCover()
    }

    LaunchedEffect(previewVersion) {
        previewBitmap = null
        if (htmlCode.isNotBlank()) {
            val renderedHtml = BookCover.renderHtmlTemplate(htmlCode, bookName, author)
            previewBitmap = renderCoverPreviewBitmap(context, renderedHtml)
        }
    }
    
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarColor,
                    scrolledContainerColor = topBarColor,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Text(
                        text = stringResource(R.string.cover_html_code),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                },
                actions = {
                    IconButton(onClick = onShowTemplateList) {
                        Icon(Icons.Default.Sort, contentDescription = "模板列表")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cover_html_template_name)) },
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = stringResource(R.string.cover_html_preview),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bookName,
                        onValueChange = { bookName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.cover_html_book_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.cover_html_author)) },
                        singleLine = true
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextButton(onClick = { previewCover() }) {
                    Text(stringResource(R.string.cover_html_preview))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier
                        .size(180.dp, 270.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    previewBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cover_html_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } ?: CircularProgressIndicator()
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = stringResource(R.string.html_code),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    color = containerColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            CodeView(ctx).apply {
                                addHtmlPattern()
                                addJsPattern()
                                setPadding(16, 16, 16, 16)
                                doAfterTextChanged { text ->
                                    val newCode = text?.toString().orEmpty()
                                    if (newCode != htmlCode) {
                                        htmlCode = newCode
                                    }
                                }
                                codeView = this
                            }
                        },
                        update = { view ->
                            if (view.text.toString() != htmlCode) {
                                view.setText(htmlCode)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                    )
                }
            }
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        templateName = ""
                        htmlCode = DefaultData.coverHtmlTemplate
                        codeView?.setText(DefaultData.coverHtmlTemplate)
                    }) {
                        Text(stringResource(R.string.btn_default_s))
                    }
                    
                    Row {
                        TextButton(onClick = onBackClick) {
                            Text(
                                stringResource(R.string.cancel),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            if (htmlCode.isBlank()) {
                                context.toastOnUi(R.string.cover_html_code_empty)
                            } else {
                                doSaveTemplate()
                                onBackClick()
                            }
                        }) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                }
            }
        }
    }
}

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
                android.view.View.MeasureSpec.makeMeasureSpec(renderWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(renderHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, renderWidth, renderHeight)
            webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)

            var attempts = 0
            while (!renderComplete && attempts < 40) {
                delay(50)
                attempts++
            }

            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(renderWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(renderHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, renderWidth, renderHeight)
            createBitmap(renderWidth, renderHeight).also { bitmap ->
                webView.draw(Canvas(bitmap))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
