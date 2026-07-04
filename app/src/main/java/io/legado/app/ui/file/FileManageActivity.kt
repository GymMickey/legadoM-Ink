package io.legado.app.ui.file

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import io.legado.app.ui.theme.LegadoThemeWithBackground
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent

class FileManageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        val initialPath = intent.getStringExtra(EXTRA_INITIAL_PATH)
        setLegadoContent {
            FileManageScreen(
                initialPath = initialPath,
                onBackClick = { finish() }
            )
        }
    }

    companion object {
        const val EXTRA_INITIAL_PATH = "initialPath"
    }
}

@Composable
fun FileManageContent(
    initialPath: String? = null,
    onBackClick: () -> Unit
) {
    LegadoThemeWithBackground(backgroundDrawable = null) {
        FileManageScreen(
            initialPath = initialPath,
            onBackClick = onBackClick
        )
    }
}
