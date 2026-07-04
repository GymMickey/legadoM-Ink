package io.legado.app.ui.book.readRecord

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.theme.LegadoThemeWithBackground
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadRecordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)

        setLegadoContent {
            ReadRecordScreen(
                onBackClick = { finish() },
                onBookClick = { bookName, bookAuthor ->
                    lifecycleScope.launch {
                        val book = withContext(Dispatchers.IO) {
                            appDb.bookDao.findByNameAndAuthor(bookName, bookAuthor).first()
                                ?: appDb.bookDao.findByName(bookName).firstOrNull()
                        }
                        if (book == null) {
                            SearchActivity.start(this@ReadRecordActivity, bookName)
                        } else {
                            startActivityForBook(book)
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ReadRecordContent(
    onBackClick: () -> Unit,
    onBookClick: (String, String) -> Unit
) {
    LegadoThemeWithBackground(backgroundDrawable = null) {
        ReadRecordScreen(
            onBackClick = onBackClick,
            onBookClick = onBookClick
        )
    }
}
