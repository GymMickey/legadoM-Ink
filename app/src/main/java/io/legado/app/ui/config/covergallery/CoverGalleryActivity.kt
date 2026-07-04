package io.legado.app.ui.config.covergallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent

class CoverGalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        setLegadoContent {
            CoverGalleryScreen(onBackClick = { finish() })
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CoverGalleryActivity::class.java))
        }
    }
}
