package io.legado.app.help.config

import io.legado.app.model.debug.DebugCategory

object DebugLogOnlyConfig {

    fun parseCategories(raw: String): Set<DebugCategory> {
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { name -> runCatching { DebugCategory.valueOf(name) }.getOrNull() }
            .filter { it != DebugCategory.ALL }
            .toSet()
    }

    fun serializeCategories(value: Set<DebugCategory>): String {
        return value.filter { it != DebugCategory.ALL }.joinToString(",") { it.name }
    }
}
