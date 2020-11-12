package com.github.vanspo.tus.interceptor

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream

internal class TusUploadRequestBodyInternal(
    private val requestId: String,
    private val mediaType: MediaType?,
    file: File,
    private val offset: Long = 0L,
    private val chunkSize: Int,
    private val payloadSize: Int,
    private val onProgressUpdate: (requestId: String, bytesWritten: Long, totalBytes: Long) -> Unit = { _, _, _ -> }
) : RequestBody() {
    private val inputStream = FileInputStream(file)
    private val buffer = ByteArray(chunkSize)
    private val totalBytes: Long = file.length()

    override fun contentType(): MediaType? = mediaType

    override fun writeTo(sink: BufferedSink) {
        inputStream.use {
            it.skip(offset)
            var bytesRead: Int
            var bytesLeftToRead = payloadSize.coerceAtMost(it.available())
            do {
                bytesRead = it.read(buffer, 0, chunkSize.coerceAtMost(it.available()))
                if (bytesRead <= 0)
                    continue
                sink.write(buffer, 0, bytesRead)
                sink.flush()
                bytesLeftToRead -= bytesRead
                onProgressUpdate(requestId, totalBytes - it.available(), totalBytes)
            } while (bytesRead > 0 && bytesLeftToRead > 0)
        }
    }
}
