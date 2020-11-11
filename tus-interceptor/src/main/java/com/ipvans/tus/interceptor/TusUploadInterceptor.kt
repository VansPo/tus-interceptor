package com.ipvans.tus.interceptor

import com.ipvans.tus.exceptions.FingerprintNotFoundException
import com.ipvans.tus.exceptions.ProtocolException
import com.ipvans.tus.store.TusUrlStore
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.Invocation
import java.net.URL

class TusUploadInterceptor(
    private val urlStore: TusUrlStore,
    private val onUploadStarted: (fingerprint: String) -> Unit = {},
    private val onProgressUpdate: (fingerprint: String, bytesWritten: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
    private val onUploadFinished: (fingerprint: String) -> Unit = {},
    private val tusVersion: String = TUS_VERSION
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val invocation = originalRequest.tag(Invocation::class.java)
            ?: return chain.proceed(originalRequest)
        invocation.method().getAnnotation(TusUpload::class.java)
            ?: return chain.proceed(originalRequest)
        val requestBody = (originalRequest.body as? TusUploadRequestBody)
            ?: return chain.proceed(originalRequest)

        val totalLength = requestBody.file.length()
        var bytesLeft: Long
        var uploadResponse: Response
        onUploadStarted(requestBody.fingerprint)
        do {
            uploadResponse = createOrResumeUpload(chain, originalRequest, requestBody)
            val offset = uploadResponse.header(UPLOAD_OFFSET)
                ?.toLong()
                ?: throw ProtocolException()
            bytesLeft = totalLength - offset
        } while (bytesLeft > 0)
        onUploadFinished(requestBody.fingerprint)
        urlStore.remove(requestBody.fingerprint)

        return uploadResponse
    }

    private fun createOrResumeUpload(
        chain: Interceptor.Chain,
        originalRequest: Request,
        requestBody: TusUploadRequestBody
    ): Response = try {
        resumeUpload(chain, originalRequest, requestBody)
    } catch (e: FingerprintNotFoundException) {
        createNewUpload(chain, originalRequest, requestBody)
    } catch (e: ProtocolException) {
        createNewUpload(chain, originalRequest, requestBody)
    }

    private fun createNewUpload(
        chain: Interceptor.Chain,
        originalRequest: Request,
        requestBody: TusUploadRequestBody
    ): Response {
        val createUploadRequest = Request.Builder()
            .url(originalRequest.url)
            .headers(originalRequest.headers)
            .addHeader("Upload-Metadata", requestBody.getEncodedMetadata())
            .addHeader("Upload-Length", requestBody.file.length().toString())
            .addHeader(TUS_RESUMABLE, tusVersion)
            .post(FormBody.Builder().build())
            .build()

        val createUploadResponse = chain.proceed(createUploadRequest)
        val uploadLocation = createUploadResponse.header("Location")
        if (uploadLocation.isNullOrBlank()) {
            throw ProtocolException()
        }
        val uploadUrl = URL(originalRequest.url.toUrl(), uploadLocation)
        urlStore.put(requestBody.fingerprint, uploadUrl)
        return startUpload(chain, uploadUrl, originalRequest, requestBody, 0)
    }

    private fun resumeUpload(
        chain: Interceptor.Chain,
        originalRequest: Request,
        requestBody: TusUploadRequestBody
    ): Response {
        val url = urlStore.get(requestBody.fingerprint) ?: throw FingerprintNotFoundException()
        return startOrResumeUploadFromUrl(chain, url, originalRequest, requestBody)
    }

    private fun startOrResumeUploadFromUrl(
        chain: Interceptor.Chain,
        url: URL,
        originalRequest: Request,
        requestBody: TusUploadRequestBody
    ): Response {
        val offsetRequest = Request.Builder()
            .url(url)
            .headers(originalRequest.headers)
            .addHeader(TUS_RESUMABLE, tusVersion)
            .head()
            .build()
        val offsetResponse = chain.proceed(offsetRequest)
        val offset = offsetResponse.header(UPLOAD_OFFSET)
            ?.toLong()
            ?: throw ProtocolException()
        return startUpload(chain, url, originalRequest, requestBody, offset)
    }

    private fun startUpload(
        chain: Interceptor.Chain,
        url: URL,
        originalRequest: Request,
        requestBody: TusUploadRequestBody,
        offset: Long
    ): Response {
        val tusRequestBody = TusUploadRequestBodyInternal(
            requestBody.fingerprint,
            requestBody.mediaType,
            requestBody.file,
            offset,
            onProgressUpdate = onProgressUpdate
        )
        val uploadRequest = Request.Builder()
            .url(url)
            .headers(originalRequest.headers)
            .addHeader(UPLOAD_OFFSET, offset.toString())
            .addHeader("Content-Type", "application/offset+octet-stream")
            .addHeader("Expect", "100-continue")
            .addHeader(TUS_RESUMABLE, tusVersion)
            .patch(tusRequestBody)
            .build()
        return chain.proceed(uploadRequest)
    }

    companion object {
        private const val UPLOAD_OFFSET = "Upload-Offset"
        private const val TUS_RESUMABLE = "Tus-Resumable"
        private const val TUS_VERSION = "1.0.0"
    }
}
