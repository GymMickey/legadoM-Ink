package io.legado.app.ui.main.homepage.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.ui.widget.components.card.GlassCard

@Composable
fun ButtonGroupModule(
    kinds: List<ExploreKind>,
    sourceUrl: String,
    onKindClick: (sourceUrl: String, url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (kinds.isEmpty()) return
    val maxColumns = 5
    val total = kinds.size
    val numRows = (total + maxColumns - 1) / maxColumns
    val actualColumns = (total + numRows - 1) / numRows

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        kinds.chunked(actualColumns).forEach { rowKinds ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowKinds.forEach { kind ->
                    GlassCard(
                        onClick = { onKindClick(sourceUrl, kind.url ?: "", kind.title) },
                        cornerRadius = 8.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = kind.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (rowKinds.size < actualColumns) {
                    repeat(actualColumns - rowKinds.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
