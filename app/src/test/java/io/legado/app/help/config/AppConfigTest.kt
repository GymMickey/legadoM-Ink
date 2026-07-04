package io.legado.app.help.config

import io.legado.app.model.debug.DebugCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
// 测试 AppConfig 类
class AppConfigTest {

    @Test
    fun debugLogOnlyCategoriesRoundTrip() {
        val original = setOf(DebugCategory.SOURCE, DebugCategory.RSS, DebugCategory.APP)

        val serialized = DebugLogOnlyConfig.serializeCategories(original)
        val parsed = DebugLogOnlyConfig.parseCategories(serialized)

        assertEquals(original, parsed)
    }

    @Test
    fun debugLogOnlyCategoriesFilterAllWhenSerializingAndParsing() {
        val serialized = DebugLogOnlyConfig.serializeCategories(
            setOf(DebugCategory.ALL, DebugCategory.SOURCE)
        )
        val parsed = DebugLogOnlyConfig.parseCategories("ALL,$serialized")

        assertFalse(serialized.contains(DebugCategory.ALL.name))
        assertTrue(DebugCategory.ALL !in parsed)
        assertTrue(DebugCategory.SOURCE in parsed)
    }

    @Test
    fun debugLogOnlyCategoriesParseBlankAsEmptySet() {
        assertEquals(emptySet<DebugCategory>(), DebugLogOnlyConfig.parseCategories(""))
        assertEquals(emptySet<DebugCategory>(), DebugLogOnlyConfig.parseCategories("   "))
    }

    @Test
    fun debugLogOnlyCategoriesIgnoreInvalidNames() {
        val parsed = DebugLogOnlyConfig.parseCategories("SOURCE,FOO,RSS")

        assertEquals(setOf(DebugCategory.SOURCE, DebugCategory.RSS), parsed)
    }
}
