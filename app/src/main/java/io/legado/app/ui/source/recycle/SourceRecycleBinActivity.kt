package io.legado.app.ui.source.recycle

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent

class SourceRecycleBinActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        setLegadoContent {
            SourceRecycleBinScreen(onBackClick = { finish() })
        }
    }
}

