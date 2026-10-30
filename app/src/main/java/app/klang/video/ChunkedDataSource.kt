package app.klang.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Reads a long YouTube stream in byte ranges of [chunk] bytes, the way YouTube's own players do.
 * One open-ended request for a whole HD video tends to be cut off or slowed down by the server.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: HttpDataSource,
    private val chunk: Long = 8L * 1024 * 1024,
) : DataSource {

    class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var pos = 0L
    private var end = -1L          // exclusive; -1 while unknown
    private var chunkLeft = 0L
    private var chunkOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) pos + dataSpec.length else -1L
        openChunk()
        if (end < 0) {
            // "bytes 0-8388607/123456789" tells the full size
            val total = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringAfter('/')?.trim()?.toLongOrNull()
            if (total != null && total > 0) end = total
        }
        return if (end > 0) end - pos else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val s = spec ?: return
        val len = if (end > 0) minOf(chunk, end - pos) else chunk
        val sub = s.buildUpon().setPosition(pos).setLength(len).build()
        val got = upstream.open(sub)
        chunkLeft = if (got != C.LENGTH_UNSET.toLong()) minOf(got, len) else len
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end in 0..pos) return C.RESULT_END_OF_INPUT
        if (chunkLeft <= 0L) {
            closeChunk()
            openChunk()
        }
        val r = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
        if (r == C.RESULT_END_OF_INPUT) {
            closeChunk()
            if (end < 0) return C.RESULT_END_OF_INPUT
            // the server ended the range early: ask again from where we are
            openChunk()
            val again = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
            if (again == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            pos += again; chunkLeft -= again
            return again
        }
        pos += r
        chunkLeft -= r
        return r
    }

    private fun closeChunk() {
        if (chunkOpen) runCatching { upstream.close() }
        chunkOpen = false
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        spec = null
    }
}
