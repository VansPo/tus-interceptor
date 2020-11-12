package com.ipvans.tus

import com.ipvans.tus.interceptor.*
import com.ipvans.tus.store.InMemoryTusUrlStore
import com.google.gson.Gson
import com.ipvans.tus.store.TusUrlStore
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Before
import org.junit.Test

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File
import java.util.concurrent.TimeUnit

interface ApiClient {
    @TusUpload
    @POST("files/")
    suspend fun upload(@Body part: TusUploadRequestBody): Response<Any?>
}

class TusInterceptorTest {
    private lateinit var urlStore: TusUrlStore
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: ApiClient
    private lateinit var writtenBytes: MutableList<Int>
    private val chunkSize = 1
    private val payloadSize = 10

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        urlStore = InMemoryTusUrlStore()
        writtenBytes = mutableListOf()
        client = Retrofit.Builder()
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(
                        TusUploadInterceptor(
                            urlStore,
                            chunkSize,
                            payloadSize,
                            onProgressUpdate = { requestId, bytesWritten, totalBytes ->
                                writtenBytes.add(bytesWritten.toInt())
                                println("$requestId: $bytesWritten out of $totalBytes")
                            }
                        )
                    )
                    .addInterceptor(Interceptor { chain ->
                        // TODO: https://github.com/square/okhttp/issues/4698
                        val newRequest = chain.request().newBuilder()
                            .removeHeader("Expect")
                            .build()
                        chain.proceed(newRequest)
                    })
                    .build()
            )
            .baseUrl(mockWebServer.url("/"))
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .build()
            .create(ApiClient::class.java)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `single chunk upload was successful`() = runBlocking {
        val request = setupRequest(payloadSize)
        mockWebServer.enqueue(MockResponse().setHeader("Location", "1"))
        mockWebServer.enqueueOffset(payloadSize)

        val response = withContext(Dispatchers.IO) { client.upload(request) }
        assert(response.isSuccessful)
        assert(writtenBytes == (1 .. payloadSize).toList())
    }

    @Test
    fun `multiple chunks upload was successful`() = runBlocking {
        val request = setupRequest(payloadSize * 2)
        mockWebServer.enqueue(MockResponse().setHeader("Location", "1"))
        mockWebServer.enqueueOffset(payloadSize)
        mockWebServer.enqueueOffset(payloadSize)
        mockWebServer.enqueueOffset(payloadSize * 2)
        val response = withContext(Dispatchers.IO) { client.upload(request) }
        assert(response.isSuccessful)
        assert(writtenBytes == (1 .. payloadSize * 2).toList())
    }

    @Test
    fun `upload interrupted mid way and resumed`() = runBlocking {
        val request = setupRequest(payloadSize * 2)
        mockWebServer.enqueue(MockResponse().setHeader("Location", "1"))
        mockWebServer.enqueueOffset(payloadSize)
        mockWebServer.enqueueOffset(payloadSize, 100)
        mockWebServer.enqueueOffset(payloadSize)
        mockWebServer.enqueueOffset(payloadSize * 2)
        val job = launch(Dispatchers.IO) { client.upload(request) }
        delay(50)
        job.cancel()
        assert(writtenBytes == (1 .. payloadSize).toList())
        val response = withContext(Dispatchers.IO) { client.upload(request) }
        assert(response.isSuccessful)
        assert(writtenBytes == (1 .. payloadSize * 2).toList())
    }

    @Test
    fun `upload resumed successfully`() = runBlocking {
        val request = setupRequest(payloadSize)
        urlStore.put(request.fingerprint, mockWebServer.url("/1").toUrl())
        mockWebServer.enqueueOffset(payloadSize / 2)
        mockWebServer.enqueueOffset(payloadSize)
        val response = withContext(Dispatchers.IO) { client.upload(request) }
        assert(response.isSuccessful)
        assert(writtenBytes == ((payloadSize / 2) + 1 .. payloadSize).toList())
    }

    private fun setupRequest(size: Int): TusUploadRequestBody {
        val file = createFile(size)
        return TusUploadRequestBody(null, file)
    }

    private fun createFile(size: Int) = File("text.txt").apply {
        createNewFile()
        repeat((0 until size).count()) { appendText("a") }
        deleteOnExit()
    }

    private fun MockWebServer.enqueueOffset(offset: Int, delayMillis: Long = 0) = enqueue(
        MockResponse().setHeader("Upload-Offset", offset)
            .setResponseCode(204)
            .setHeadersDelay(delayMillis, TimeUnit.MILLISECONDS)
    )
}
