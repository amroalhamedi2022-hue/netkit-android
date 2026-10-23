package com.netkit.local.remote

import java.io.InputStream
import java.io.OutputStream

/**
 * Counts real bytes passing through FTP/SFTP data streams.
 * A count is not proof that a remote server durably committed the payload.
 */
class ProgressInputStream(
    private val input: InputStream,
    private val onProgress: (Long) -> Unit
) : InputStream() {
    var transferred: Long = 0
        private set
    override fun read(): Int {
        val value = input.read()
        if (value >= 0) increment(1)
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = input.read(buffer, offset, length)
        if (read > 0) increment(read)
        return read
    }
    private fun increment(n: Int) {
        transferred += n.toLong()
        onProgress(transferred)
    }
    override fun available(): Int = input.available()
    override fun close() = input.close()
    override fun markSupported(): Boolean = false
}

class ProgressOutputStream(
    private val output: OutputStream,
    private val onProgress: (Long) -> Unit
) : OutputStream() {
    var transferred: Long = 0
        private set
    override fun write(value: Int) {
        output.write(value)
        increment(1)
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        if (length > 0) increment(length)
    }
    private fun increment(n: Int) {
        transferred += n.toLong()
        onProgress(transferred)
    }
    override fun flush() = output.flush()
    override fun close() = output.close()
}
