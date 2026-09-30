package com.duluin.ftth.inventory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.ClosedWatchServiceException
import java.util.concurrent.TimeUnit

internal object ChildProcessPort {
    fun read(path: Path): Int? = try {
        Files.readString(path).trim().toIntOrNull()?.takeIf { it in 1..65535 }
    } catch (missing: NoSuchFileException) { null }

    fun publish(path: Path, port: Int) {
        require(port in 1..65535)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent,".child-port-",".pending")
        try {
            Files.writeString(temporary,port.toString())
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun await(process: Process, path: Path, log: Path): Int = FileSystems.getDefault().newWatchService().use { watcher ->
        path.toAbsolutePath().parent.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY)
        val exit = process.onExit().thenRun { watcher.close() }
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(180)
        try {
            var port: Int? = null
            while (port == null) {
                check(process.isAlive) { "Child exited before valid port publication: ${Files.readString(log)}" }
                port = read(path)
                if (port == null) {
                    val remaining = deadline-System.nanoTime()
                    check(remaining>0) { "Child port publication timed out: ${Files.readString(log)}" }
                    val key = watcher.poll(remaining,TimeUnit.NANOSECONDS)
                        ?: error("Child port publication timed out: ${Files.readString(log)}")
                    key.pollEvents()
                    key.reset()
                }
            }
            port
        } catch (closed: ClosedWatchServiceException) {
            error("Child exited before valid port publication: ${Files.readString(log)}")
        } finally { exit.cancel(false) }
    }
}
