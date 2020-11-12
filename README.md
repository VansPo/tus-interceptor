# tus-interceptor
Retrofit Tus protocol extension

## What is tus?

> tus is an open protocol for resumable file uploads.

Check out the official website at [tus.io](https://tus.io)

To learn more about it, take a look at the [protocol specification](https://github.com/tus/tus-resumable-upload-protocol/blob/master/protocol.md) or explore a [reference server implementation](https://github.com/tus/tusd).

## What is tus-interceptor

This project is an extension for OkHttp+Retrofit, providing an easy way to implement tus protocol in your client application. 

All you need to do it to add an interceptor to your `OkHttp` client and mark your `Retrofit` upload requests with a `@TusUpload` annotation. The interceptor will do the rest for you: redirecting requests, getting the initial offsets and writing file chunks to an open connection.

## Usage

Implement your `TusUrlStore`  :

```kotlin
class InMemoryTusUrlStore: TusUrlStore {
    private val map: MutableMap<String, URL> = mutableMapOf()
    override fun put(fingerprint: String, url: URL) {
        map[fingerprint] = url
    }

    override fun get(fingerprint: String): URL? {
        return map[fingerprint]
    }

    override fun remove(fingerprint: String) {
        map.remove(fingerprint)
    }
}
```

Create Retrofit client:

```kotlin
interface ApiClient {
    // mark request for interceptor to recognize it
    @TusUpload
    @POST("files/")
    suspend fun upload(
      // use TusUploadRequestBody in the request body
      @Body part: TusUploadRequestBody
    ): Response<Any?>
}

val client = Retrofit.Builder()
    .client(
        OkHttpClient.Builder()
            .addInterceptor(
                TusUploadInterceptor(
                    urlStore,
                    chunkSize,
                    payloadSize,
                    onProgressUpdate = { requestId, bytesWritten, totalBytes ->
                        println("$requestId: $bytesWritten out of $totalBytes")
                    }
                )
            )
            .build()
    )
    .baseUrl("http://localhost:8080")
    .addConverterFactory(GsonConverterFactory.create(Gson()))
    .build()
    .create(ApiClient::class.java)
```

That's it! Now, if you interrupt your request mid-execution, next time it will automatically start form the last known offset:

```kotlin
val job = launch(Dispatchers.IO) { client.upload(request) }
delay(50)
job.cancel()
assert(writtenBytes != (1 .. fileSize).toList())
val response = withContext(Dispatchers.IO) { client.upload(request) }
assert(response.isSuccessful)
assert(writtenBytes == (1 .. fileSize).toList())
```

## Download

### Gradle

```groovy
implementation 'com.github.vanspo:tus-interceptor:1.0.1'
```

## License

```
MIT License

Copyright (c) 2020 Ivan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
