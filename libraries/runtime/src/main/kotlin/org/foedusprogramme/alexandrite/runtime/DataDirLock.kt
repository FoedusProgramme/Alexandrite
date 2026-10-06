package org.foedusprogramme.alexandrite.runtime

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

/** The lock that keeps a data directory to one runtime. */
internal class DataDirLock private constructor(private val directory: Path, private val channel: FileChannel) :
    AutoCloseable {
    override fun close() {
        try {
            channel.close()
        } finally {
            held.remove(directory)
        }
    }

    companion object {
        const val FILE_NAME = "runtime.lock"

        private const val THIS_JVM = "another runtime of this JVM"

        /** Real paths of the data directories that runtimes of this JVM hold. */
        private val held: MutableSet<Path> = ConcurrentHashMap.newKeySet()

        /** Locks [dataDir], calling [inUse] with the holder when another runtime has it. */
        fun acquire(dataDir: Path, inUse: (holder: String) -> Nothing): DataDirLock {
            val directory = Files.createDirectories(dataDir).toRealPath()
            if (!held.add(directory)) inUse(THIS_JVM)
            try {
                return DataDirLock(directory, lockedChannel(directory.resolve(FILE_NAME), inUse))
            } catch (e: Throwable) {
                held.remove(directory)
                throw e
            }
        }

        private fun lockedChannel(file: Path, inUse: (holder: String) -> Nothing): FileChannel {
            val channel = FileChannel.open(file, CREATE, READ, WRITE)
            try {
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    inUse(THIS_JVM)
                }
                if (lock == null) inUse(holder(channel))
                channel.truncate(0)
                channel.write(ByteBuffer.wrap("${ProcessHandle.current().pid()}\n".toByteArray()), 0)
                channel.force(false)
                return channel
            } catch (e: Throwable) {
                try {
                    channel.close()
                } catch (closing: IOException) {
                    e.addSuppressed(closing)
                }
                throw e
            }
        }

        private fun holder(channel: FileChannel): String {
            val pid = try {
                val buffer = ByteBuffer.allocate(32)
                channel.read(buffer, 0)
                String(buffer.array(), 0, buffer.position()).trim().toLongOrNull()
            } catch (e: IOException) {
                null
            }
            return pid?.let { "process $it" } ?: "another process"
        }
    }
}
