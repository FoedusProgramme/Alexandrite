package org.foedusprogramme.alexandrite.runtime.lifecycle

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** Holds the lock file of the data directory in [args] until stdin closes. */
fun main(args: Array<String>) {
    FileChannel.open(Path.of(args.single()).resolve("runtime.lock"), CREATE, READ, WRITE).use { channel ->
        checkNotNull(channel.tryLock()) { "the lock file is locked" }
        channel.write(ByteBuffer.wrap("${ProcessHandle.current().pid()}\n".toByteArray()), 0)
        println("locked")
        System.out.flush()
        System.`in`.read()
    }
}
