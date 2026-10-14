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
            // Per-I/O-operation, not whole-call: a long upload is fine, a stalled one now fails
            // (and is retried/resumed) instead of hanging forever.
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
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
        endpoint: String = "request",
    ): okhttp3.Response {
        val headers = mutableMapOf(
            "Content-Type" to "application/x-protobuf",
            "Authorization" to "Bearer ${bearer()}",
            "User-Agent" to userAgent,
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
                val bodyPreview = readPreview(resp)
                resp.close()
                if (!shouldRetry(code)) {
                    throw RuntimeException("[$endpoint] Request failed $code: $bodyPreview")
                }
                lastError = RuntimeException("[$endpoint] HTTP $code: $bodyPreview")
                if (attempt < retryConfig.maxRetries) {
                    val delay = calculateBackoff(attempt, retryConfig)
                    Log.i("GooglePhotosApi", "Retrying [$endpoint] $url in ${delay}ms attempt ${attempt+2}")
                    Thread.sleep(delay)
                }
            } catch (e: Exception) {
                lastError = e
                if (isNonRetryable(e)) throw e
                // Network errors / 5xx / 429 retry; 4xx already rethrown above
                if (attempt < retryConfig.maxRetries) {
                    val delay = calculateBackoff(attempt, retryConfig)
                    Log.i("GooglePhotosApi", "Retrying [$endpoint] due to ${e.message} in ${delay}ms")
                    Thread.sleep(delay)
                }
            }
        }
        throw RuntimeException("[$endpoint] Request failed after ${retryConfig.maxRetries+1} attempts: $lastError")
    }
    private fun gunzipMaybe(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return try {
            java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()
        } catch (_: Exception) { bytes }
    }

    private fun readPreview(resp: okhttp3.Response): String {
        return try {
            val raw = resp.body?.bytes()?.take(1200)?.toByteArray() ?: return ""
            previewBytes(gunzipMaybe(raw))
        } catch (_: Exception) { "" } ?: ""
    }

    private fun previewBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // Internal API errors are often protobuf/binary — String() shows as encrypted garbage.
        // Show readable text when possible, else compact hex for copy/paste debugging.
        val printable = bytes.count { it.toInt() in 32..126 || it.toInt() == 10 || it.toInt() == 13 } 
        return if (printable * 100 / bytes.size > 70) {
            String(bytes).take(500)
        } else {
            "bin:" + bytes.take(64).joinToString("") { "%02x".format(it) } + "...(${bytes.size}B)"
        }
    }

    private fun isNonRetryable(e: Exception): Boolean {
        val m = e.message ?: return false
        var code: Int? = null
        var idx = m.indexOf("Request failed ")
        if (idx >= 0) {
            val num = m.substring(idx + 15).trim().take(3)
            code = num.toIntOrNull()
        }
        if (code == null) {
            idx = m.indexOf("Upload rejected (")
            if (idx >= 0) {
                val num = m.substring(idx + 17).trim().take(3)
                code = num.toIntOrNull()
            }
        }
        if (code != null) return !shouldRetry(code)
        return false
    }


    fun getUploadToken(sha1B64: String, fileSize: Long): String {
        val msg = GetUploadTokenOuterClass.GetUploadToken.newBuilder()
            .setF1(2).setF2(2).setF3(1).setF4(3)
            .setFileSizeBytes(fileSize)
            .build()
        val resp = postProtoRaw(
            "https://photos.googleapis.com/data/upload/uploadmedia/interactive",
            msg.toByteArray(),
            mapOf("X-Goog-Hash" to "sha1=$sha1B64", "X-Upload-Content-Length" to fileSize.toString()),
        endpoint = "getUploadToken"
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
            mapOf("X-Upload-Content-Length" to fileSize.toString()),
        endpoint = "getUploadToken"
        )
        resp.use {
            val id = it.header("X-GUploader-UploadID") ?: it.header("x-gUploader-uploadID")
            if (id.isNullOrBlank()) throw RuntimeException("Missing X-GUploader-UploadID")
            return id
        }
    }

    /**
     * Streaming upload with resume. Port of _do_upload_attempt.
     *
     * @param openStream fresh stream positioned at the given offset, one per attempt.
     * @param hashOut receives the SHA-1 of the whole file, but only from an attempt that streamed
     *   it from byte 0 — a resumed attempt sees only the tail, and its digest would be wrong.
     */
    fun uploadStream(
        openStream: (offset: Long) -> java.io.InputStream,
        fileSize: Long,
        uploadId: String,
        onProgress: ((bytesRead: Long, total: Long) -> Unit)? = null,
        resumeOffset: Long = 0,
        hashOut: MutableList<ByteArray>? = null,
    ): CommitTokenOuterClass.CommitToken {
        val uploadUrl = uploadUrl(uploadId)
        var attemptOffset = resumeOffset
        var lastError: Exception? = null
        for (attempt in 0..retryConfig.maxRetries) {
            if (cancelled) throw RuntimeException("Upload cancelled")
            if (attempt > 0) {
                val (nextByte, existingToken) = tryResume(uploadUrl, fileSize)
                if (existingToken != null) return existingToken
                if (nextByte != null) attemptOffset = nextByte
            }
            try {
                return doUploadStreamAttempt(openStream, uploadUrl, fileSize, onProgress, attemptOffset, hashOut)
            } catch (e: Exception) {
                lastError = e
                if (cancelled || isNonRetryable(e)) throw e
                if (attempt < retryConfig.maxRetries) {
                    val delay = calculateBackoff(attempt, retryConfig)
                    Log.i("GooglePhotosApi", "Stream upload retry in ${delay}ms attempt ${attempt + 2} due to ${e.message}")
                    Thread.sleep(delay)
                }
            }
        }
        throw RuntimeException("Upload failed after ${retryConfig.maxRetries + 1}: $lastError")
    }

    private fun doUploadStreamAttempt(
        openStream: (Long) -> java.io.InputStream,
        uploadUrl: String,
        fileSize: Long,
        onProgress: ((Long, Long) -> Unit)?,
        startByte: Long,
        hashOut: MutableList<ByteArray>?,
    ): CommitTokenOuterClass.CommitToken {
        if (cancelled) throw RuntimeException("Upload cancelled")
        if (startByte < 0 || startByte > fileSize) throw RuntimeException("Invalid resume offset $startByte/$fileSize")
        val sha1 = if (hashOut != null && startByte == 0L) MessageDigest.getInstance("SHA-1") else null
        val contentLength = fileSize - startByte

        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = contentLength
            override fun writeTo(sink: okio.BufferedSink) {
                openStream(startByte).use { raw ->
                    val buffer = ByteArray(256 * 1024)
                    var totalRead = startByte
                    while (totalRead < fileSize) {
                        if (cancelled) throw RuntimeException("Upload cancelled")
                        val read = raw.read(buffer, 0, minOf(buffer.size.toLong(), fileSize - totalRead).toInt())
                        if (read == -1) break
                        totalRead += read
                        sha1?.update(buffer, 0, read)
                        sink.write(buffer, 0, read)
                        onProgress?.invoke(totalRead, fileSize)
                    }
                    if (totalRead != fileSize) {
                        throw RuntimeException("Stream ended early: $totalRead/$fileSize bytes")
                    }
                }
                if (sha1 != null && hashOut != null && hashOut.isEmpty()) hashOut.add(sha1.digest())
            }
        }

        val req = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Bearer ${bearer()}")
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", contentLength.toString())
            .header("Content-Range", "bytes $startByte-${fileSize - 1}/$fileSize")
            .header("User-Agent", userAgent)
            .put(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code >= 400) {
                val preview = try { resp.body?.bytes()?.take(1200)?.toByteArray()?.let { previewBytes(gunzipMaybe(it)) } } catch (_: Exception) { "" } ?: ""
                Log.e("GooglePhotosApi", "Stream upload failed ${resp.code}: $preview")
                throw RuntimeException("Upload rejected (${resp.code}): $preview")
            }
            val bytes = resp.body?.bytes() ?: throw RuntimeException("Empty upload response")
            return CommitTokenOuterClass.CommitToken.parseFrom(bytes)
        }
    }

    /**
     * Hash a stream with progress (for GB files hashing also looks stuck).
     */
    fun calculateSha1WithProgress(
        openStream: (offset: Long) -> java.io.InputStream,
        fileSize: Long,
        onProgress: ((bytesRead: Long, total: Long) -> Unit)? = null,
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        openStream(0).use { s ->
            val buf = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                if (cancelled) throw RuntimeException("Upload cancelled")
                val r = s.read(buf)
                if (r == -1) break
                md.update(buf, 0, r)
                total += r
                onProgress?.invoke(total, fileSize)
            }
        }
        return md.digest()
    }

    /**
     * Ask the server how much of [uploadUrl] it already has.
     * @return (next byte to send, null) for a 308; (null, token) when the upload already finished;
     *   (null, null) when the session is unknown/expired.
     */
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
                    200, 201 -> null to CommitTokenOuterClass.CommitToken.parseFrom(resp.body?.bytes())
                    308 -> nextByteFromRange(resp.header("Range")) to null
                    else -> null to null
                }
            }
        } catch (e: Exception) {
            null to null
        }
    }

    fun tryResumeSession(uploadId: String, fileSize: Long): Pair<Long?, CommitTokenOuterClass.CommitToken?> =
        tryResume(uploadUrl(uploadId), fileSize)

    private fun uploadUrl(uploadId: String) =
        "https://photos.googleapis.com/data/upload/uploadmedia/interactive?upload_id=$uploadId"

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
            mapOf("x-goog-ext-173412678-bin" to "CgcIAhClARgC", "x-goog-ext-174067345-bin" to "CgIIAg=="),
        endpoint = "commitUpload"
        )
        resp.use {
            val raw = it.body?.bytes() ?: throw RuntimeException("[commitUpload] Empty commit response")
            val bytes = gunzipMaybe(raw)
            val parsed = try {
                CommitUploadResponseOuterClass.CommitUploadResponse.parseFrom(bytes)
            } catch (e: Exception) {
                throw RuntimeException("[commitUpload] bad reply (" + bytes.size + "B): " + previewBytes(bytes))
            }
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
            msg.toByteArray(),
        endpoint = "hashCheck"
        )
        resp.use {
            val raw = it.body?.bytes() ?: return ""
            val bytes = gunzipMaybe(raw)
            val parsed = try {
                RemoteMatchesOuterClass.RemoteMatches.parseFrom(bytes)
            } catch (e: Exception) {
                // Dedup is best-effort: a garbled check reply must not block the upload.
                // Proceed as not-found; upload/commit steps will surface real errors with phase tags.
                Log.w("GooglePhotosApi", "[hashCheck] unparseable reply (" + bytes.size + "B): " + previewBytes(bytes))
                return ""
            }
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
            mapOf("x-goog-ext-173412678-bin" to "CgcIAhClARgC", "x-goog-ext-174067345-bin" to "CgIIAg=="),
        endpoint = "createAlbum"
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
            mapOf("x-goog-ext-173412678-bin" to "CgcIAhClARgC", "x-goog-ext-174067345-bin" to "CgIIAg=="),
            endpoint = "addToAlbum"
        ).close()
    }
}

/**
 * Next byte to send, from a resumable-upload 308 `Range` header. The header names the last byte
 * the server holds, inclusive (`bytes=0-999` → resume at 1000); no header means nothing arrived.
 */
internal fun nextByteFromRange(range: String?): Long {
    val last = range?.substringAfter('=', "")?.substringAfter('-', "")?.trim()?.toLongOrNull()
    return if (last == null) 0L else last + 1
}
