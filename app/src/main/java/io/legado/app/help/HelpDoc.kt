package io.legado.app.help

data class HelpDoc(
    val fileName: String,
    val displayName: String
)

data class HelpDocGroup(
    val displayName: String,
    val docs: List<HelpDoc>
)
