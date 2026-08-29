package com.duluin.ftth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import java.nio.file.Files
import java.nio.file.Path

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
    fun `hotspot accesses only public BNG and catalog APIs`() {
        val hotspotSources = Files.walk(Path.of("src/main/kotlin/com/duluin/ftth/hotspot")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .flatMap { Files.lines(it) }
                .filter { it.startsWith("import com.duluin.ftth.bng.") || it.startsWith("import com.duluin.ftth.catalog.") }
                .toList()
        }

        assertThat(hotspotSources).allSatisfy { importLine ->
            assertThat(importLine).matches("import com\\.duluin\\.ftth\\.(bng|catalog)\\.[A-Z][A-Za-z0-9_]*(?:\\.\\*)?")
        }
    }

    @Test
    fun `cetak peta module`() {
        modules.forEach(::println)
    }
}
