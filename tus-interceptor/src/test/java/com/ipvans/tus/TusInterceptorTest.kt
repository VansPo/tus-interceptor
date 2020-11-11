package com.ipvans.tus

import com.ipvans.tus.interceptor.*
import com.ipvans.tus.store.InMemoryTusUrlStore
import com.google.gson.Gson
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
    lateinit var mockWebServer: MockWebServer
    lateinit var client: ApiClient

    private val dispatcher: Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when(request.path) {
                "/files/" -> {
                    MockResponse().setHeader("Location", "1")
                }
                "/files/1" -> {
                    MockResponse().setHeader("Upload-Offset", "22").setResponseCode(204)
                }
                else -> throw NotImplementedError()
            }
        }
    }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        mockWebServer.dispatcher = dispatcher

        client = Retrofit.Builder()
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(
                        TusUploadInterceptor(
                            InMemoryTusUrlStore(),
                            onProgressUpdate = { requestId, bytesWritten, totalBytes -> println("$requestId: $bytesWritten out of $totalBytes") }
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
        val file = File("text.txt").apply { createNewFile() }
        file.writeText("Test file")
        file.appendText("\nAnother line")
        println(file.absolutePath)
        val request = TusUploadRequestBody(null, file)

        val response = withContext(Dispatchers.IO) { client.upload(request) }
        assert(response.isSuccessful)
    }
}
