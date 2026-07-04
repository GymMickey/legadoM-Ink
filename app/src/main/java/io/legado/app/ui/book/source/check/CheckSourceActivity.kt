package io.legado.app.ui.book.source.check

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.constant.EventBus
import io.legado.app.model.CheckSourceResultEvent
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.theme.LegadoThemeWithBackground
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity

class CheckSourceActivity : AppCompatActivity() {

    private var currentViewModel: CheckSourceViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        observeEvents()

        setLegadoContent {
            val viewModel: CheckSourceViewModel = viewModel()
            currentViewModel = viewModel

            CheckSourceScreen(
                viewModel = viewModel,
                onBackClick = { finish() },
                onOpenConfig = { showDialogFragment<CheckSourceConfig>() },
                onEditSource = { sourceUrl ->
                    startActivity<BookSourceEditActivity> {
                        putExtra("sourceUrl", sourceUrl)
                    }
                },
                onDebugSource = { sourceUrl ->
                    startActivity<BookSourceDebugActivity> {
                        putExtra("key", sourceUrl)
                    }
                }
            )
        }
    }

    private fun observeEvents() {
        observeEvent<String>(EventBus.CHECK_SOURCE) { message ->
            currentViewModel?.updateCheckMessage(message)
        }

        observeEvent<CheckSourceResultEvent>(EventBus.CHECK_SOURCE_RESULT) { result ->
            currentViewModel?.onCheckResult(result)
        }

        observeEvent<Int>(EventBus.CHECK_SOURCE_DONE) {
            currentViewModel?.onCheckComplete()
        }
    }
}

@Composable
fun CheckSourceContent(
    onBackClick: () -> Unit,
    onOpenConfig: () -> Unit,
    onEditSource: (String) -> Unit,
    onDebugSource: (String) -> Unit,
    onViewModelCreated: (CheckSourceViewModel) -> Unit
) {
    LegadoThemeWithBackground(backgroundDrawable = null) {
        val viewModel: CheckSourceViewModel = viewModel()
        onViewModelCreated(viewModel)

        CheckSourceScreen(
            viewModel = viewModel,
            onBackClick = onBackClick,
            onOpenConfig = onOpenConfig,
            onEditSource = onEditSource,
            onDebugSource = onDebugSource
        )
    }
}
