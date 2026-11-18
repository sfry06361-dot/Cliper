package com.example.downloader

import com.example.model.LinkInspectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

class VideoLinkException(
    message: String,
    val diagnosticDetail: String? = null
) : Exception(message)

/**
 * Self-contained link inspector packaged inside the application source tree.
 * Probes any pasted URL via HTTP HEAD/GET, resolves redirects, checks MIME & size,
 * and extracts direct video stream URLs from HTML metadata/video tags when possible.
 */
class VideoLinkInspector(
    private val client: OkHttpClient = defaultOkHttpClient()
) {

    suspend fun inspectUrl(rawUrl: String): LinkInspectionResult = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawUrl)

        val headResult = runCatching { probeHead(normalized) }.getOrNull()
        if (headResult != null && isVideoMimeOrExtension(headResult.contentType, headResult.finalUrl)) {
            return@withContext LinkInspectionResult(
                originalUrl = rawUrl.trim(),
                resolvedVideoUrl = headResult.finalUrl,
                title = inferTitleFromUrl(headResult.finalUrl),
                contentType = headResult.contentType.ifBlank { "video/mp4" },
                contentLengthBytes = headResult.contentLength,
                isDirectVideo = true,
                extractedFromHtml = false,
                httpStatus = headResult.statusCode,
                inspectionNotes = "رابط فيديو مباشر جاهز للتحميل والفحص (${headResult.contentType.ifBlank { "video/mp4" }})"
            )
        }

        // Fall back to GET (stream-safe, bounded read if HTML)
        val getRequest = Request.Builder()
            .url(normalized)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "video/*,text/html,application/xhtml+xml,*/*;q=0.8")
            .get()
            .build()

        client.newCall(getRequest).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val statusCode = response.code
            if (!response.isSuccessful) {
                throw VideoLinkException(
                    message = "تعذر الوصول للرابط (رمز HTTP $statusCode)",
                    diagnosticDetail = "URL: $finalUrl | HTTP $statusCode ${response.message}"
                )
            }

            val contentType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L

            if (isVideoMimeOrExtension(contentType, finalUrl)) {
                return@withContext LinkInspectionResult(
                    originalUrl = rawUrl.trim(),
                    resolvedVideoUrl = finalUrl,
                    title = inferTitleFromUrl(finalUrl),
                    contentType = contentType.ifBlank { "video/mp4" },
                    contentLengthBytes = contentLength,
                    isDirectVideo = true,
                    extractedFromHtml = false,
                    httpStatus = statusCode,
                    inspectionNotes = "تم التحقق من تدفق الفيديو المباشر عبر فحص الترويسة."
                )
            }

            if (contentType.contains("html") || contentType.contains("xml") || contentType.isBlank()) {
                val htmlBytes = readBoundedBytes(response.body?.byteStream(), maxBytes = 512 * 1024)
                val html = htmlBytes.toString(Charsets.UTF_8)
                val extractedStream = extractVideoUrlFromHtml(html, finalUrl)
                    ?: throw VideoLinkException(
                        message = "الصفحة لا توفر رابط فيديو مباشر قابل للتنزيل (قد يكون محمياً بتشفير أو بث متقطع).",
                        diagnosticDetail = "Content-Type: $contentType | Final URL: $finalUrl — جرّب رابط MP4 مباشر أو اختر الفيديو من جهازك."
                    )

                val pageTitle = extractHtmlTitle(html) ?: inferTitleFromUrl(extractedStream)
                val thumb = extractMetaContent(html, "og:image")
                val streamProbe = runCatching { probeHead(extractedStream) }.getOrNull()

                return@withContext LinkInspectionResult(
                    originalUrl = rawUrl.trim(),
                    resolvedVideoUrl = streamProbe?.finalUrl ?: extractedStream,
                    title = pageTitle,
                    contentType = streamProbe?.contentType?.ifBlank { "video/mp4" } ?: "video/mp4",
                    contentLengthBytes = streamProbe?.contentLength ?: -1L,
                    isDirectVideo = false,
                    extractedFromHtml = true,
                    thumbnailUrl = thumb?.let { resolveRelativeUrl(finalUrl, it) },
                    httpStatus = streamProbe?.statusCode ?: statusCode,
                    inspectionNotes = "تم فحص صفحة الويب واستخراج مسار الفيديو المباشر بنجاح."
                )
            }

            throw VideoLinkException(
                message = "الرابط لا يشير إلى ملف فيديو مدعوم (نوع المحتوى: ${contentType.ifBlank { "غير معروف" }})",
                diagnosticDetail = "Final URL: $finalUrl"
            )
        }
    }

    private data class HeadProbe(
        val finalUrl: String,
        val statusCode: Int,
        val contentType: String,
        val contentLength: Long
    )

    private fun probeHead(url: String): HeadProbe? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .head()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val contentType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            return HeadProbe(
                finalUrl = response.request.url.toString(),
                statusCode = response.code,
                contentType = contentType,
                contentLength = length
            )
        }
    }

    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            throw VideoLinkException("يرجى إدخال أو لصق رابط الفيديو أولاً.")
        }
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: throw VideoLinkException("صيغة الرابط غير صالحة. تأكد من نسخ الرابط بالكامل.")
        if (uri.host.isNullOrBlank()) {
            throw VideoLinkException("الرابط لا يحتوي على اسم نطاق صالح.")
        }
        return withScheme
    }

    private fun isVideoMimeOrExtension(contentType: String, url: String): Boolean {
        if (contentType.startsWith("video/")) return true
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val hasVideoExt = cleanPath.endsWith(".mp4") ||
            cleanPath.endsWith(".mov") ||
            cleanPath.endsWith(".m4v") ||
            cleanPath.endsWith(".webm") ||
            cleanPath.endsWith(".mkv")
        return hasVideoExt && (contentType.isBlank() || contentType == "application/octet-stream" || contentType == "binary/octet-stream")
    }

    private fun extractVideoUrlFromHtml(html: String, baseUrl: String): String? {
        val metaKeys = listOf(
            "og:video:secure_url",
            "og:video:url",
            "og:video",
            "twitter:player:stream"
        )
        for (key in metaKeys) {
            val found = extractMetaContent(html, key)
            if (!found.isNullOrBlank() && !found.endsWith(".html", true)) {
                val resolved = resolveRelativeUrl(baseUrl, found)
                if (looksLikeDirectStream(resolved)) return resolved
            }
        }

        val videoTagRegex = Regex(
            """<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE)
        )
        videoTagRegex.findAll(html).forEach { match ->
            val candidate = resolveRelativeUrl(baseUrl, match.groupValues[1])
            if (looksLikeDirectStream(candidate)) return candidate
        }

        val jsonUrlRegex = Regex(
            """["'](?:contentUrl|video_url|mp4_url|src|url)["']\s*:\s*["'](https?:\\?/\\?/[^"']+\.(?:mp4|mov|webm|m4v)[^"']*)["']""",
            setOf(RegexOption.IGNORE_CASE)
        )
        jsonUrlRegex.find(html)?.let { match ->
            return match.groupValues[1].replace("\\/", "/")
        }

        val rawMp4Regex = Regex("""https?://[^\s"'<>\\]+\.(?:mp4|mov|webm)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        return rawMp4Regex.find(html)?.value
    }

    private fun looksLikeDirectStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun extractHtmlTitle(html: String): String? {
        extractMetaContent(html, "og:title")?.takeIf { it.isNotBlank() }?.let { return it }
        val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        return titleMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractMetaContent(html: String, key: String): String? {
        val regex1 = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val regex2 = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["']""",
            RegexOption.IGNORE_CASE
        )
        return regex1.find(html)?.groupValues?.getOrNull(1)
            ?: regex2.find(html)?.groupValues?.getOrNull(1)
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrDefault(candidate)
    }

    private fun inferTitleFromUrl(url: String): String {
        val lastSegment = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return lastSegment
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .takeIf { it.length >= 3 }
            ?: "مشروع فيديو جديد"
    }

    private fun readBoundedBytes(input: java.io.InputStream?, maxBytes: Int): ByteArray {
        if (input == null) return ByteArray(0)
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
