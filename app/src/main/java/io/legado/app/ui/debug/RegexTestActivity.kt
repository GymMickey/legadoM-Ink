package io.legado.app.ui.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import io.legado.app.ui.theme.LegadoThemeWithBackground
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent

class RegexTestActivity : AppCompatActivity() {

    companion object {
        fun startIntent(
            context: Context,
            pattern: String = "",
            replacement: String = "",
            isRegex: Boolean = true
        ): Intent {
            return Intent(context, RegexTestActivity::class.java).apply {
                putExtra("pattern", pattern)
                putExtra("replacement", replacement)
                putExtra("isRegex", isRegex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)

        val pattern = intent.getStringExtra("pattern") ?: ""
        val replacement = intent.getStringExtra("replacement") ?: ""
        val isRegex = intent.getBooleanExtra("isRegex", true)

        setLegadoContent {
            RegexTestScreen(
                onBackClick = { finish() },
                initialPattern = pattern,
                initialReplacement = replacement,
                initialIsRegex = isRegex
            )
        }
    }
}

@Composable
fun RegexTestContent(
    onBackClick: () -> Unit,
    initialPattern: String = "",
    initialReplacement: String = "",
    initialIsRegex: Boolean = true
) {
    LegadoThemeWithBackground(backgroundDrawable = null) {
        RegexTestScreen(
            onBackClick = onBackClick,
            initialPattern = initialPattern,
            initialReplacement = initialReplacement,
            initialIsRegex = initialIsRegex
        )
    }
}
