package io.legado.app.ui.book.read.config

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

object HighlightRuleGroupStore {

    const val DEFAULT_GROUP = "默认分组"

    fun load(context: Context): MutableList<String> {
        val stored = context.getPrefString(PreferKey.highlightRuleGroups)
        val groups = GSON.fromJsonArray<String>(stored).getOrNull()?.toMutableList()
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toMutableList()
            ?: mutableListOf()
        if (groups.isEmpty()) {
            groups.add(DEFAULT_GROUP)
            save(context, groups)
        }
        if (!groups.contains(DEFAULT_GROUP)) {
            groups.add(0, DEFAULT_GROUP)
            save(context, groups)
        }
        return groups
    }

    fun save(context: Context, groups: List<String>) {
        val normalized = groups.filter { it.isNotBlank() }.distinct().ifEmpty { listOf(DEFAULT_GROUP) }
        context.putPrefString(PreferKey.highlightRuleGroups, GSON.toJson(normalized))
    }

    fun ensureFromRules(context: Context, rules: List<HighlightRule>) {
        val groups = load(context)
        val merged = LinkedHashSet<String>()
        merged.addAll(groups)
        rules.mapTo(merged) { it.group.ifBlank { DEFAULT_GROUP } }
        save(context, merged.toList())
    }
}
