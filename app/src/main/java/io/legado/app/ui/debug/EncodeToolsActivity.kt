package io.legado.app.ui.debug

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import io.legado.app.ui.theme.LegadoThemeWithBackground
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent

class EncodeToolsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        setLegadoContent {
            EncodeToolsScreen(onBackClick = { finish() })
        }
    }
}

@Composable
fun EncodeToolsContent(
    onBackClick: () -> Unit
) {
    LegadoThemeWithBackground(backgroundDrawable = null) {
        EncodeToolsScreen(onBackClick = onBackClick)
    }
}
