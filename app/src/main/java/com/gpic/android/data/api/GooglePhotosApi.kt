package com.gpic.android.data.api

import android.util.Log
import com.gpic.android.data.auth.AuthManager
import gphotos.AddMediaToAlbumOuterClass
import gphotos.CommitTokenOuterClass
import gphotos.CommitUploadOuterClass
import gphotos.CommitUploadResponseOuterClass
import gphotos.CreateAlbumOuterClass
import gphotos.CreateAlbumResponseOuterClass
import gphotos.GetUploadTokenOuterClass
import gphotos.HashCheckOuterClass
import gphotos.RemoteMatchesOuterClass
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Port of gphotos/api.py GooglePhotosAPI to Kotlin/OkHttp
 */
class GooglePhotosApi(
    private val authString: String,
    private val language: String = "en",
    private val saverMode: Boolean = false,
    private val useQuota: Boolean = false,
    private val client: OkHttpClient = defaultClient(),
) {
    private val auth = AuthManager(client, language)
    private val retryConfig = RetryConfig(maxRetries = 3)
    private val userAgent = "com.google.android.apps.photos/49029607 (Linux; U; Android 9; en; Pixel XL; Build/PQ2A.190205.001; Cronet/127.0.6510.5) (gzip)"

    private val model: String = when {
        useQuota -> "Pixel 8"
        saverMode -> "Pixel 2"
        else -> "Pixel XL"
    }
    private val make = "Google"
    private val androidApiVersion = 28L

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // streaming
            .writeTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // we handle retry manually
            .build()

        fun supportedExtensions(): Set<String> = setOf(
            ".avif",".bmp",".gif",".heic",".heif",".ico",
            ".jpg",".jpeg",".png",".tif",".tiff",".webp",
            ".cr2",".cr3",".nef",".arw",".orf",".raf",".rw2",".pef",".sr2",".dng",
            ".3gp",".3g2",".asf",".avi",".divx",".m2t",".m2ts",".m4v",".mkv",
            ".mmv",".mod",".mov",".mp4",".mpg",".mpeg",".mts",".tod",".wmv",".ts",
        )

        fun formatExtensions(): String = supportedExtensions().joinToString(", ")
    }

    private fun bearer(): String = auth.getBearerTokenBlocking(authString)

    private fun postProtoRaw(
        url: String,
        data: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ): okhttp3.Response {
        val headers = mutableMapOf(
            "Content-Type" to "application/x-protobuf",
            "Authorization" to "Bearer ${bearer()}",
            "User-Agent" to userAgent,
            "Accept-Encoding" to "gzip",
            "Accept-Language" to language,
        )
        headers.putAll(extraHeaders)

        var lastError: Exception? = null
        for (attempt in 0..retryConfig.maxRetries) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(data.toRequestBody("application/x-protobuf".toMediaType()))
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) return resp
                val code = resp.code
                val bodyPreview = resp.body?.bytes()?.take(500)?.toByteArray()?.let { String(it) } ?: ""
                resp.close()
                if (!shouldRetry(code)) {
                    throw RuntimeException("Request failed $code: $bodyPreview")
                }
                lastError = RuntimeException("HTTP $code: $bodyPreview")
                if (attempt < retryConfig.maxRetries) {
                    val delay = calculateBackoff(attempt, retryConfig)
                    Log.i("GooglePhotosApi", "Retrying $url in ${delay}ms attempt ${attempt+2}")
                    Thread.sleep(delay)
                }
            } catch (e: Exception) {
                lastError = e
                if (e is RuntimeException && e.message?.contains("HTTP") == true && !shouldRetry(
                    // couldn't parse code, don't retry on our RuntimeExceptions with HTTP?
                    500
                )) {
                    // but we already checked
                }
                // For network errors, retry
                if (attempt < retryConfig.maxRetries) {
                    val delay = calculateBackoff(attempt, retryConfig)
                    Log.i("GooglePhotosApi", "Retrying due to ${e.message} in ${delay}ms")
                    Thread.sleep(delay)
                }
            }
        }
        throw RuntimeException("Request failed after ${retryConfig.maxRetries+1} attempts: $lastError")
    }

    fun getUploadToken(sha1B64: String, fileSize: Long): String {
        val msg = GetUploadTokenOuterClass.GetUploadToken.newBuilder()
            .setF1(2).setF2(2).setF3(1).setF4(3)
            .setFileSizeBytes(fileSize)
            .build()
        val resp = postProtoRaw(
            "https://photos.googleapis.com/data/upload/uploadmedia/interactive",
            msg.toByteArray(),
            mapOf("X-Goog-Hash" to "sha1=$sha1B64", "X-Upload-Content-Length" to fileSize.toString())
        )
        resp.use {
            val id = it.header("X-GUploader-UploadID") ?: it.header("x-gUploader-uploadID")
            if (id.isNullOrBlank()) throw RuntimeException("Missing X-GUploader-UploadID")
            return id
        }
    }

    fun getUploadTokenSkipHash(fileSize: Long): String {
        val msg = GetUploadTokenOuterClass.GetUploadToken.newBuilder()
            .setF1(2).setF2(2).setF3(1).setF4(3)
            .setFileSizeBytes(fileSize)
            .build()
        val resp = postProtoRaw(
            "https://photos.googleapis.com/data/upload/uploadmedia/interactive",
            msg.toByteArray(),
            mapOf("X-Upload-Content-Length" to fileSize.toString())
        )
        resp.use {
            val id = it.header("X-GUploader-UploadID") ?: it.header("x-gUploader-uploadID")
            if (id.isNullOrBlank()) throw RuntimeException("Missing X-GUploader-UploadID")
            return id
        }
    }

    /**
     * Streaming upload with progress callback and optional on-the-fly SHA1.
     * Port of _do_upload_attempt
     */
    fun uploadFile(
        file: File,
        uploadId: String,
        fileSize: Long = file.length(),
        onProgress: ((bytesRead: Long, total: Long) -> Unit)? = null,
        resumeOffset: Long = 0,
        computeHash: Boolean = false,
        hashOut: MutableList<ByteArray>? = null,
    ): CommitTokenOuterClass.CommitToken {
        val uploadUrl = "https://photos.googleapis.com/data/upload/uploadmedia/interactive?upload_id=$uploadId"

        var attemptOffset = resumeOffset
        var lastError: Exception? = null
        for (attempt in 0..retryConfig.maxRetries) {
            if (cancelled) throw RuntimeException("Upload cancelled")
            // On retries, check server resume point
            if (attempt > 0) {
                val (resumeByte, existingToken) = tryResume(uploadUrl, fileSize)
                if (existingToken != null) return existingToken
                if (resumeByte != null) attemptOffset = resumeByte + 1
            }
            try {
                return doUploadAttempt(file, uploadUrl, fileSize, onProgress, attemptOffset, computeHash, hashOut)
            } catch (e: Exception) {
                lastError = e
                val isHttp = e.message?.contains("HTTP") == true
                // Determine if retryable – for now retry on IO / 5xx / 429
                // e already handles HTTP non-retry shouldn't retry, but we treat all as retryable except if message contains 4xx?
                if (e.message?.contains("Upload rejected") == true && e.message?.contains("400") == true) throw e
                if (attempt < retryConfig.maxRetries) {
                    val delay = calculateBackoff(attempt, retryConfig)
                    Log.i("GooglePhotosApi","Upload retry in ${delay}ms attempt ${attempt+2} due to ${e.message}")
                    Thread.sleep(delay)
                }
            }
        }
        throw RuntimeException("Upload failed after ${retryConfig.maxRetries+1}: $lastError")
    }

    private fun tryResume(uploadUrl: String, fileSize: Long): Pair<Long?, CommitTokenOuterClass.CommitToken?> {
        return try {
            val req = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer ${bearer()}")
                .header("Content-Length", "0")
                .header("Content-Range", "bytes */$fileSize")
                .header("User-Agent", userAgent)
                .put(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200, 201 -> {
                        val token = CommitTokenOuterClass.CommitToken.parseFrom(resp.body?.bytes())
                        null to token
                    }
                    308 -> {
                        val range = resp.header("Range") ?: ""
                        if ("=" in range) {
                            val parts = range.substringAfter("=").split("-")
                            val last = parts.getOrNull(1)?.toLongOrNull()
                            if (last != null) return last to null
                        }
                        0L to null
                    }
                    else -> null to null
                }
            }
        } catch (e: Exception) {
            null to null
        }
    }

    private fun doUploadAttempt(
        file: File,
        uploadUrl: String,
        fileSize: Long,
        onProgress: ((Long, Long) -> Unit)?,
        startByte: Long,
        computeHash: Boolean,
        hashOut: MutableList<ByteArray>?,
    ): CommitTokenOuterClass.CommitToken {
        if (cancelled) throw RuntimeException("Upload cancelled")
        val sha1 = if (computeHash) MessageDigest.getInstance("SHA-1") else null

        // For streaming we need to count bytes and report progress
        // OkHttp RequestBody that streams file chunk and calls onProgress
        val contentLength = fileSize - startByte
        val fileInput = FileInputStream(file).apply { if (startByte > 0) skip(startByte) }

        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = contentLength
            override fun writeTo(sink: okio.BufferedSink) {
                val buffer = ByteArray(256 * 1024)
                var totalRead = startByte
                var lastEmit = System.currentTimeMillis()
                while (true) {
                    if (cancelled) throw RuntimeException("Upload cancelled")
                    val read = fileInput.read(buffer)
                    if (read == -1) break
                    totalRead += read
                    sha1?.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                    onProgress?.invoke(totalRead, fileSize)
                }
                fileInput.close()
                if (computeHash && hashOut != null && sha1 != null) {
                    hashOut.add(sha1.digest())
                }
            }
        }

        val req = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Bearer ${bearer()}")
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", contentLength.toString())
            .header("Content-Range", "bytes $startByte-${fileSize-1}/$fileSize")
            .header("User-Agent", userAgent)
            .put(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code >= 400) {
                val preview = resp.body?.bytes()?.take(500)?.joinToString("") { it.toInt().toChar().toString() } ?: ""
                Log.e("GooglePhotosApi","Upload failed ${resp.code}: $preview")
                throw RuntimeException("Upload rejected (${resp.code}): $preview")
            }
            val bytes = resp.body?.bytes() ?: throw RuntimeException("Empty upload response")
            // hashOut if computeHash already handled in writeTo, but if we didn't compute there, fallback?
            // Ensure hashOut filled if needed
            if (computeHash && (hashOut == null || hashOut.isEmpty()) && sha1 != null) {
                // Should have been filled; if not, compute separately (fallback)
                // but we already digested via streaming, so we have it
            }
            return CommitTokenOuterClass.CommitToken.parseFrom(bytes)
        }
    }

    fun tryResumeSession(uploadId: String, fileSize: Long): Pair<Long?, CommitTokenOuterClass.CommitToken?> {
        val url = "https://photos.googleapis.com/data/upload/uploadmedia/interactive?upload_id=$uploadId"
        return tryResume(url, fileSize)
    }

    fun commitUpload(
        commitToken: CommitTokenOuterClass.CommitToken,
        fileName: String,
        sha1Hash: ByteArray,
        timestamp: Long,
    ): String {
        val msg = CommitUploadOuterClass.CommitUpload.newBuilder()
            .setField1(
                CommitUploadOuterClass.CommitUpload.Field1Type.newBuilder()
                    .setField1(
                        CommitUploadOuterClass.CommitUpload.Field1Type.InnerField1Type.newBuilder()
                            .setField1(commitToken.field1)
                            .setField2(commitToken.field2)
                            .build()
                    )
                    .setFileName(fileName)
                    .setSha1Hash(com.google.protobuf.ByteString.copyFrom(sha1Hash))
                    .setField4(
                        CommitUploadOuterClass.CommitUpload.Field1Type.Field4Type.newBuilder()
                            .setFileLastModifiedTimestamp(timestamp)
                            .setField2(46000000)
                            .build()
                    )
                    .setQuality(if (model == "Pixel 2") 1 else 3)
                    .setField10(1)
                    .build()
            )
            .setField2(
                CommitUploadOuterClass.CommitUpload.Field2Type.newBuilder()
                    .setModel(model)
                    .setMake(make)
                    .setAndroidApiVersion(androidApiVersion)
                    .build()
            )
            .setField3(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01, 0x03)))
            .build()

        val resp = postProtoRaw(
            "https://photosdata-pa.googleapis.com/6439526531001121323/16538846908252377752",
            msg.toByteArray(),
            mapOf("x-goog-ext-173412678-bin" to "CgcIAhClARgC", "x-goog-ext-174067345-bin" to "CgIIAg==")
        )
        resp.use {
            val bytes = it.body?.bytes() ?: throw RuntimeException("Empty commit response")
            val parsed = CommitUploadResponseOuterClass.CommitUploadResponse.parseFrom(bytes)
            val mediaKey = parsed.field1.field3.mediaKey
            if (mediaKey.isNullOrEmpty()) throw RuntimeException("Upload rejected: no media key")
            return mediaKey
        }
    }

    fun findMediaByHash(sha1Hash: ByteArray): String {
        val msg = HashCheckOuterClass.HashCheck.newBuilder()
            .setField1(
                HashCheckOuterClass.HashCheck.Field1Type.newBuilder()
                    .setField1(
                        HashCheckOuterClass.HashCheck.Field1Type.InnerField1Type.newBuilder()
                            .setSha1Hash(com.google.protobuf.ByteString.copyFrom(sha1Hash))
                            .build()
                    )
                    .setField2(HashCheckOuterClass.HashCheck.Field1Type.InnerField2Type.newBuilder().build())
                    .build()
            )
            .build()
        val resp = postProtoRaw(
            "https://photosdata-pa.googleapis.com/6439526531001121323/5084965799730810217",
            msg.toByteArray()
        )
        resp.use {
            val bytes = it.body?.bytes() ?: return ""
            val parsed = RemoteMatchesOuterClass.RemoteMatches.parseFrom(bytes)
            return if (parsed.hasField1() && parsed.field1.hasField2() && parsed.field1.field2.hasField2()) {
                parsed.field1.field2.field2.mediaKey ?: ""
            } else ""
        }
    }

    fun createAlbum(albumName: String, mediaKeys: List<String>): String {
        val protoKeys = mediaKeys.map {
            CreateAlbumOuterClass.CreateAlbum.Field4Type.newBuilder()
                .setField1(CreateAlbumOuterClass.CreateAlbum.Field4Type.Field1Type.newBuilder().setMediaKey(it).build())
                .build()
        }
        val msg = CreateAlbumOuterClass.CreateAlbum.newBuilder()
            .setAlbumName(albumName)
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setField3(1)
            .addAllMediaKeys(protoKeys)
            .setField7(CreateAlbumOuterClass.CreateAlbum.Field7Type.newBuilder().setField1(3).build())
            .setDeviceInfo(
                CreateAlbumOuterClass.CreateAlbum.Field8Type.newBuilder()
                    .setModel(model).setMake(make).setAndroidApiVersion(androidApiVersion).build()
            )
            .build()
        val resp = postProtoRaw(
            "https://photosdata-pa.googleapis.com/6439526531001121323/8386163679468898444",
            msg.toByteArray(),
            mapOf("x-goog-ext-173412678-bin" to "CgcIAhClARgC", "x-goog-ext-174067345-bin" to "CgIIAg==")
        )
        resp.use {
            val bytes = it.body?.bytes() ?: throw RuntimeException("Empty create album response")
            val parsed = CreateAlbumResponseOuterClass.CreateAlbumResponse.parseFrom(bytes)
            return parsed.field1.albumMediaKey
        }
    }

    fun addMediaToAlbum(albumMediaKey: String, mediaKeys: List<String>) {
        val msg = AddMediaToAlbumOuterClass.AddMediaToAlbum.newBuilder()
            .addAllMediaKeys(mediaKeys)
            .setAlbumMediaKey(albumMediaKey)
            .setField5(AddMediaToAlbumOuterClass.AddMediaToAlbum.Field5Type.newBuilder().setField1(2).build())
            .setDeviceInfo(
                AddMediaToAlbumOuterClass.AddMediaToAlbum.Field6Type.newBuilder()
                    .setModel(model).setMake(make).setAndroidApiVersion(androidApiVersion).build()
            )
            .setTimestamp(System.currentTimeMillis() / 1000)
            .build()
        postProtoRaw(
            "https://photosdata-pa.googleapis.com/6439526531001121323/484917746253879292",
            msg.toByteArray(),
            mapOf("x-goog-ext-173412678-bin" to "CgcIAhClARgC", "x-goog-ext-174067345-bin" to "CgIIAg==")
        ).close()
    }

    fun calculateSha1(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r == -1) break
                md.update(buf, 0, r)
            }
        }
        return md.digest()
    }

    // Convenience for InputStream-based files (e.g., DocumentFile from SAF)
    fun calculateSha1(stream: java.io.InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        stream.use { s ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val r = s.read(buf)
                if (r == -1) break
                md.update(buf, 0, r)
            }
        }
        return md.digest()
    }
}
