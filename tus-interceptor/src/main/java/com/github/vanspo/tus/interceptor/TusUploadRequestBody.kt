package com.github.vanspo.tus.interceptor

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.util.*

class TusUploadRequestBody(
    val mediaType: MediaType?,
    val file: File,
    val fingerprint: String = "%s-%d".format(file.absolutePath, file.length()),
    private val metadata: Map<String, String> = mapOf()
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun writeTo(sink: BufferedSink) {
        // should not be used directly
    }

    fun getEncodedMetadata(): String {
        if (metadata.isEmpty()) {
            return ""
        }
        var encoded = ""
        var firstElement = true
        for ((key, value) in metadata.entries) {
            if (!firstElement) {
                encoded += ","
            }
            encoded += key + " " + base64Encode(value.toByteArray())
            firstElement = false
        }
        return encoded
    }

    private fun base64Encode(input: ByteArray): String? =
        Base64.getEncoder().encodeToString(input)
}
