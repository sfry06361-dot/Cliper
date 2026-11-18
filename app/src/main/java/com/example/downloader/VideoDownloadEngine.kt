package com.example.downloader

import android.content.Context
import android.net.Uri
import com.example.model.DownloadState
import com.example.model.LinkInspectionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
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
 * Peeks initial bytes before writing to disk, auto-recovers if a CDN redirects to HTML,
 * supports direct HLS (.m3u8) streams, and verifies container magic bytes.
 */
class VideoDownloadEngine(
    private val context: Context,
    private val linkInspector: VideoLinkInspector = VideoLinkInspector(),
    private val frameInspector: VideoFrameInspector = VideoFrameInspector(context),
    private val client: OkHttpClient = VideoLinkInspector.sharedOkHttpClient
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
        emit(DownloadState.Inspecting(rawUrl, "جاري فحص الرابط والتحقق من مسار الفيديو..."))
        try {
            val initialInspection = linkInspector.inspectUrl(rawUrl)

            if (initialInspection.isHlsStream) {
                emit(DownloadState.Verifying(initialInspection))
                val techInfo = frameInspector.inspectRemoteStream(
                    initialInspection.resolvedVideoUrl,
                    initialInspection.title
                )
                emit(
                    DownloadState.Completed(
                        filePath = initialInspection.resolvedVideoUrl,
                        inspection = initialInspection,
                        technicalInfo = techInfo
                    )
                )
                return@flow
            }

            downloadWithRecovery(
                inspection = initialInspection,
                referer = initialInspection.originalUrl,
                allowHtmlRecovery = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: VideoLinkException) {
            emit(
                DownloadState.Error(
                    message = e.message ?: "تعذر فحص رابط الفيديو",
                    diagnosticDetail = e.diagnosticDetail
                )
            )
        } catch (e: Exception) {
            emit(
                DownloadState.Error(
                    message = "تعذر فحص الرابط: ${e.localizedMessage ?: "تأكد من اتصالك بالإنترنت وصحة الرابط"}",
                    diagnosticDetail = e.javaClass.simpleName
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<DownloadState>.downloadWithRecovery(
        inspection: LinkInspectionResult,
        referer: String,
        allowHtmlRecovery: Boolean
    ) {
        emit(DownloadState.Inspecting(inspection.resolvedVideoUrl, "جاري تنزيل الفيديو داخل ملفات التطبيق..."))

        val fileId = "clip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val ext = guessExtension(inspection.resolvedVideoUrl, inspection.contentType)
        val tempFile = File(downloadsDir, "$fileId.part")
        val targetFile = File(downloadsDir, "$fileId.$ext")

        val request = Request.Builder()
            .url(inspection.resolvedVideoUrl)
            .header("User-Agent", VideoLinkInspector.BROWSER_USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "video/*,*/*;q=0.8")
            .get()
            .build()

        val call = client.newCall(request)
        activeCall = call

        var recoveredInspection: LinkInspectionResult? = null

        try {
            call.execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (!response.isSuccessful) {
                    throw VideoLinkException(
                        message = "فشل التنزيل من الخادم (HTTP ${response.code})",
                        diagnosticDetail = response.message
                    )
                }
                val body = response.body ?: throw VideoLinkException("استجابة الخادم فارغة بدون بيانات فيديو.")
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: inspection.contentLengthBytes
                val input = body.byteStream()

                // Peek first 512 bytes BEFORE streaming to disk so HTML responses never corrupt downloads
                val peek = ByteArray(512)
                var peekLen = 0
                while (peekLen < peek.size) {
                    val r = input.read(peek, peekLen, peek.size - peekLen)
                    if (r <= 0) break
                    peekLen += r
                }
                val peekText = String(peek, 0, peekLen, Charsets.ISO_8859_1).trimStart()
                val isHtmlPayload = mime.contains("text/html") ||
                    peekText.startsWith("<!doctype", true) ||
                    peekText.startsWith("<html", true) ||
                    peekText.startsWith("<head", true) ||
                    peekText.startsWith("<script", true)

                if (isHtmlPayload) {
                    if (allowHtmlRecovery) {
                        val htmlOut = ByteArrayOutputStream()
                        htmlOut.write(peek, 0, peekLen)
                        val buf = ByteArray(8192)
                        while (htmlOut.size() < 512 * 1024) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            htmlOut.write(buf, 0, r)
                        }
                        val html = htmlOut.toString(Charsets.UTF_8.name())
                        val extracted = linkInspector.tryExtractVerifiedVideoFromHtml(html, finalUrl)
                        if (extracted != null) {
                            recoveredInspection = extracted
                            return@use
                        }
                    }
                    throw VideoLinkException(
                        message = "الموقع أرجع صفحة حماية HTML بدلاً من ملف الفيديو المباشر.",
                        diagnosticDetail = "تأكد أن الرابط عام (غير خاص أو محمي بحساب) أو اختر ملف الفيديو من جهازك."
                    )
                }

                if (peekText.startsWith("#EXTM3U") || mime.contains("mpegurl")) {
                    val hlsInspection = inspection.copy(
                        resolvedVideoUrl = finalUrl,
                        isHlsStream = true,
                        isDirectVideo = false,
                        inspectionNotes = "تم التحويل تلقائياً إلى بث HLS جاهز للمعالجة."
                    )
                    recoveredInspection = hlsInspection
                    return@use
                }

                var bytesReadTotal = peekLen.toLong()
                var lastEmitTime = System.currentTimeMillis()
                var bytesSinceLastEmit = 0L

                FileOutputStream(tempFile).use { output ->
                    if (peekLen > 0) {
                        output.write(peek, 0, peekLen)
                    }
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

            // Handle auto-recovered stream (either extracted MP4 from HTML or HLS stream)
            val rec = recoveredInspection
            if (rec != null) {
                if (rec.isHlsStream) {
                    emit(DownloadState.Verifying(rec))
                    val techInfo = frameInspector.inspectRemoteStream(rec.resolvedVideoUrl, rec.title)
                    emit(DownloadState.Completed(rec.resolvedVideoUrl, rec, techInfo))
                } else {
                    downloadWithRecovery(rec, referer = inspection.resolvedVideoUrl, allowHtmlRecovery = false)
                }
                return
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
    }

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
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
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
