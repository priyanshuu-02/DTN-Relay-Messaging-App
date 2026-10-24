package com.dtn.mesh.ui.theme

import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtnIconsTest {

    @Test
    fun everyBundledIconParses() {
        val icons = DtnIcons.all
        assertEquals("icon names are unique", icons.size, icons.map { it.name }.toSet().size)
        icons.forEach { icon ->
            val paths = (0 until icon.root.size).map { icon.root[it] }.filterIsInstance<VectorPath>()
            assertTrue("${icon.name} has a path", paths.isNotEmpty())
            paths.forEach { assertTrue("${icon.name} path has nodes", it.pathData.size > 2) }
            assertEquals(24f, icon.viewportWidth)
        }
    }
}
