package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class ChildProcessPortTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest @ValueSource(strings = ["", "0", "65536", "partial", "12345\nnot-ready"])
    fun `existing but incomplete or invalid publication is not readiness`(contents: String) {
        val path = directory.resolve("child.port")
        Files.writeString(path,contents)

        val port = ChildProcessPort.read(path)

        assertThat(port).isNull()
    }

    @Test fun `valid port publication is read exactly`() {
        val path = directory.resolve("child.port")
        Files.writeString(path,"17880")
        assertThat(ChildProcessPort.read(path)).isEqualTo(17880)
    }

    @Test fun `atomic publisher replaces an incomplete target with one complete valid port`() {
        val path = directory.resolve("child.port")
        Files.createFile(path)

        ChildProcessPort.publish(path,17880)

        assertThat(ChildProcessPort.read(path)).isEqualTo(17880)
        Files.list(directory).use { assertThat(it.toList()).containsExactly(path) }
    }

    @Test fun `waiting reader ignores empty publication and returns only the complete port`() {
        val path = directory.resolve("child.port")
        val log = directory.resolve("child.log")
        Files.createFile(path)
        Files.writeString(log,"")
        val process = ProcessBuilder("/bin/sh","-c","read marker").start()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            val result = executor.submit<Int> { started.countDown(); ChildProcessPort.await(process,path,log) }
            check(started.await(5,java.util.concurrent.TimeUnit.SECONDS))

            ChildProcessPort.publish(path,17880)

            assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(17880)
        } finally {
            process.outputStream.close()
            process.destroyForcibly()
            assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        }
    }
}
