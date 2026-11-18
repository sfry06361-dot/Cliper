package com.example.downloader

import android.content.Context
import android.net.Uri
import com.example.model.DownloadState
import com.example.model.LinkInspectionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class StoredMediaFile(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isExport: Boolean
)

/**
 * Self-contained streaming video downloader stored inside the application files.
 * Verifies container magic bytes (MP4/MOV ftyp, WebM/MKV EBML) and persists inside filesDir/downloads.
 */
class VideoDownloadEngine(
    private val context: Context,
    private val linkInspector: VideoLinkInspector = VideoLinkInspector(),
    private val frameInspector: VideoFrameInspector = VideoFrameInspector(context),
    private val client: OkHttpClient = VideoLinkInspector.defaultOkHttpClient()
) {
    @Volatile
    private var activeCall: Call? = null

    val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    val exportsDir: File
        get() = File(context.filesDir, "exports").apply { mkdirs() }

    fun cancelActiveDownload() {
        activeCall?.cancel()
        activeCall = null
    }

    fun inspectAndDownload(rawUrl: String): Flow<DownloadState> = flow {
        emit(DownloadState.Inspecting(rawUrl, "جاري فحص الرابط والاتصال بالخادم..."))
        val inspection = linkInspector.inspectUrl(rawUrl)

        emit(DownloadState.Inspecting(inspection.resolvedVideoUrl, "جاري بدء تنزيل الفيديو داخل ملفات التطبيق..."))

        val fileId = "clip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val ext = guessExtension(inspection.resolvedVideoUrl, inspection.contentType)
        val tempFile = File(downloadsDir, "$fileId.part")
        val targetFile = File(downloadsDir, "$fileId.$ext")

        val request = Request.Builder()
            .url(inspection.resolvedVideoUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .get()
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw VideoLinkException(
                        message = "فشل التنزيل من الخادم (HTTP ${response.code})",
                        diagnosticDetail = response.message
                    )
                }
                val body = response.body ?: throw VideoLinkException("استجابة الخادم فارغة بدون بيانات فيديو.")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: inspection.contentLengthBytes

                var bytesReadTotal = 0L
                var lastEmitTime = System.currentTimeMillis()
                var bytesSinceLastEmit = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesReadTotal += read
                            bytesSinceLastEmit += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastEmitTime
                            if (elapsed >= 120L) {
                                val speed = (bytesSinceLastEmit * 1000L) / elapsed.coerceAtLeast(1L)
                                val percent = if (totalBytes > 0) {
                                    ((bytesReadTotal * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                emit(
                                    DownloadState.Downloading(
                                        bytesRead = bytesReadTotal,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speed,
                                        progressPercent = percent,
                                        inspection = inspection
                                    )
                                )
                                lastEmitTime = now
                                bytesSinceLastEmit = 0L
                            }
                        }
                        output.flush()
                    }
                }
            }

            emit(DownloadState.Verifying(inspection))
            verifyVideoContainer(tempFile)
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val technicalInfo = frameInspector.inspectVideoFile(targetFile.absolutePath)
            emit(
                DownloadState.Completed(
                    filePath = targetFile.absolutePath,
                    inspection = inspection,
                    technicalInfo = technicalInfo
                )
            )
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: VideoLinkException) {
            tempFile.delete()
            emit(DownloadState.Error(e.message ?: "فشل فحص وتنزيل الرابط", e.diagnosticDetail))
        } catch (e: Exception) {
            tempFile.delete()
            emit(
                DownloadState.Error(
                    message = "تعذر إكمال التحميل: ${e.localizedMessage ?: "خطأ في الشبكة"}",
                    diagnosticDetail = e.javaClass.simpleName
                )
            )
        } finally {
            activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    fun importLocalUri(uri: Uri): Flow<DownloadState> = flow {
        val inspection = LinkInspectionResult(
            originalUrl = uri.toString(),
            resolvedVideoUrl = uri.toString(),
            title = "فيديو محلي من الجهاز",
            contentType = context.contentResolver.getType(uri) ?: "video/mp4",
            contentLengthBytes = -1L,
            isDirectVideo = true,
            extractedFromHtml = false,
            inspectionNotes = "تم استيراد الفيديو من ألبوم الجهاز للفحص والمونتاج."
        )
        emit(DownloadState.Inspecting(uri.toString(), "جاري نسخ الفيديو لفحصه داخل الاستوديو..."))

        val fileId = "local_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val targetFile = File(downloadsDir, "$fileId.mp4")

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw VideoLinkException("تعذر فتح ملف الفيديو المختار.")
            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    output.flush()
                }
            }
            emit(DownloadState.Verifying(inspection))
            val techInfo = frameInspector.inspectVideoFile(targetFile.absolutePath)
            emit(DownloadState.Completed(targetFile.absolutePath, inspection, techInfo))
        } catch (e: CancellationException) {
            targetFile.delete()
            throw e
        } catch (e: Exception) {
            targetFile.delete()
            emit(DownloadState.Error("تعذر استيراد الفيديو: ${e.localizedMessage ?: "خطأ غير متوقع"}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun verifyVideoContainer(file: File) {
        if (!file.exists() || file.length() < 256) {
            throw VideoLinkException("الملف المحمل صغير جداً أو تالف ولا يمثل فيديو صالحاً.")
        }
        val header = ByteArray(32)
        FileInputStream(file).use { it.read(header) }
        val headerStr = String(header, Charsets.ISO_8859_1)
        val trimmedLower = headerStr.trimStart().lowercase()
        if (trimmedLower.startsWith("<!doctype") || trimmedLower.startsWith("<html") || trimmedLower.startsWith("{")) {
            throw VideoLinkException(
                message = "الرابط أرجع صفحة ويب أو نصاً بدلاً من ملف فيديو فعلي.",
                diagnosticDetail = "تم إيقاف التحميل أثناء الفحص الأمني للملف."
            )
        }
        val isMp4OrMov = headerStr.contains("ftyp")
        val isEbml = header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        val isRiff = headerStr.startsWith("RIFF")
        val isTs = header[0] == 0x47.toByte()
        if (!isMp4OrMov && !isEbml && !isRiff && !isTs) {
            // Final verification via MediaMetadataRetriever in case of non-standard box offset
            val probeOk = runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                dur > 0L
            }.getOrDefault(false)
            if (!probeOk) {
                throw VideoLinkException("تعذر التحقق من حاوية الفيديو (تنسيق غير مدعوم أو تالف).")
            }
        }
    }

    private fun guessExtension(url: String, mime: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".webm") || mime.contains("webm") -> "webm"
            clean.endsWith(".mov") || mime.contains("quicktime") -> "mov"
            clean.endsWith(".mkv") || mime.contains("matroska") -> "mkv"
            else -> "mp4"
        }
    }

    fun listAllStoredMediaFiles(): List<StoredMediaFile> {
        val downloads = downloadsDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.map {
                StoredMediaFile(
                    fileName = it.name,
                    filePath = it.absolutePath,
                    sizeBytes = it.length(),
                    lastModifiedMs = it.lastModified(),
                    isExport = false
                )
            }
            .orEmpty()
        val exports = exportsDir.listFiles()
            ?.filter { it.isFile }
            ?.map {
                StoredMediaFile(
                    fileName = it.name,
                    filePath = it.absolutePath,
                    sizeBytes = it.length(),
                    lastModifiedMs = it.lastModified(),
                    isExport = true
                )
            }
            .orEmpty()
        return (exports + downloads).sortedByDescending { it.lastModifiedMs }
    }

    fun deleteStoredFile(filePath: String): Boolean {
        return runCatching { File(filePath).delete() }.getOrDefault(false)
    }
}
