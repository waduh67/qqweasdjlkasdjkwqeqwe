package com.duluin.ftth

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Menegakkan batas module Spring Modulith: tidak ada module yang mengakses
 * package internal module lain, dan tidak ada dependency siklik. Ini analisis
 * statis — tidak memerlukan database maupun context Spring.
 */
class ModularityTests {

    private val modules = ApplicationModules.of(FtthApplication::class.java)

    @Test
    fun `struktur module valid`() {
        modules.verify()
    }

    @Test
    fun `cetak peta module`() {
        modules.forEach(::println)
    }
}
