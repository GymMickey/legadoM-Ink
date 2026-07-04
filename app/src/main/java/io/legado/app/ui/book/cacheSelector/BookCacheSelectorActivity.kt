package io.legado.app.ui.book.cacheSelector

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import io.legado.app.help.HelpDocManager
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoThemeWithBackground
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.showDialogFragment

class BookCacheSelectorActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, BookCacheSelectorActivity::class.java))
        }
    }

    private lateinit var viewModel: BookCacheSelectorViewModel

    private val selectExportDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            viewModel.exportSelectedBooks(this, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[BookCacheSelectorViewModel::class.java]

        setLegadoContent {
            BookCacheSelectorScreen(
                onBackClick = { finish() },
                onSaveClick = {
                    viewModel.saveSelection()
                    finish()
                },
                onExportClick = { selectExportDir.launch { mode = HandleFileContract.DIR } },
                onHelpClick = { showHelp() }
            )
        }
    }

    private fun showHelp() {
        val content = HelpDocManager.loadDoc(assets, "bookCacheHelp")
        showDialogFragment(
            TextDialog(
                title = "书籍缓存备份机制",
                content = content,
                mode = TextDialog.Mode.MD,
                helpDocName = "bookCacheHelp"
            )
        )
    }
}

@Composable
fun BookCacheSelectorContent(
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onExportClick: () -> Unit,
    onHelpClick: () -> Unit
) {
    LegadoThemeWithBackground(backgroundDrawable = null) {
        BookCacheSelectorScreen(
            onBackClick = onBackClick,
            onSaveClick = onSaveClick,
            onExportClick = onExportClick,
            onHelpClick = onHelpClick
        )
    }
}
