package com.example.downloader

import com.example.model.LinkInspectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class VideoLinkException(
    message: String,
    val diagnosticDetail: String? = null
) : Exception(message)

/**
 * In-memory cookie store so redirects, Drive confirmation pages, and CDN hotlink checks
 * keep their session cookies between inspection and download.
 */
class SessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val existing = store.getOrPut(url.host) { mutableListOf() }
        synchronized(existing) {
            cookies.forEach { incoming ->
                existing.removeAll { it.name == incoming.name && it.path == incoming.path }
                existing.add(incoming)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val existing = store[url.host] ?: return emptyList()
        return synchronized(existing) {
            existing.removeAll { it.expiresAt < now }
            existing.toList()
        }
    }
}

/**
 * Self-contained link inspector packaged inside the application source tree.
 * Rewrites cloud-share links (Drive/Dropbox/GitHub), extracts candidate streams from HTML,
 * and verifies actual stream bytes (Range 0-127) before accepting a URL as downloadable video.
 */
class VideoLinkInspector(
    private val client: OkHttpClient = sharedOkHttpClient
) {

    suspend fun inspectUrl(rawUrl: String): LinkInspectionResult = withContext(Dispatchers.IO) {
        val normalized = rewriteKnownHostingUrl(normalizeUrl(rawUrl))

        val request = Request.Builder()
            .url(normalized)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "video/*,application/xhtml+xml,text/html;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
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
            val bodyStream = response.body?.byteStream()

            // Peek initial bytes to distinguish real video/HLS from HTML error pages
            val peekBytes = readBoundedBytes(bodyStream, maxBytes = 512)
            val peekText = peekBytes.toString(Charsets.ISO_8859_1).trimStart()
            val looksHtml = peekText.startsWith("<!doctype", true) ||
                peekText.startsWith("<html", true) ||
                peekText.startsWith("<head", true) ||
                peekText.startsWith("<script", true)

            if (isHlsStream(contentType, finalUrl, peekText)) {
                return@withContext LinkInspectionResult(
                    originalUrl = rawUrl.trim(),
                    resolvedVideoUrl = finalUrl,
                    title = inferTitleFromUrl(finalUrl),
                    contentType = contentType.ifBlank { "application/vnd.apple.mpegurl" },
                    contentLengthBytes = contentLength,
                    isDirectVideo = false,
                    extractedFromHtml = false,
                    isHlsStream = true,
                    httpStatus = statusCode,
                    inspectionNotes = "تم اكتشاف بث فيديو HLS مباشر (.m3u8) جاهز للمعالجة والمونتاج."
                )
            }

            if (!looksHtml && isVideoMimeOrBinaryStream(contentType, finalUrl, peekBytes)) {
                return@withContext LinkInspectionResult(
                    originalUrl = rawUrl.trim(),
                    resolvedVideoUrl = finalUrl,
                    title = inferTitleFromUrl(finalUrl),
                    contentType = contentType.ifBlank { "video/mp4" },
                    contentLengthBytes = contentLength,
                    isDirectVideo = true,
                    extractedFromHtml = false,
                    httpStatus = statusCode,
                    inspectionNotes = "تم التحقق من ملف الفيديو المباشر وحاوية البيانات بنجاح."
                )
            }

            // Read remaining HTML up to 512KB for deep stream extraction
            val restHtml = readBoundedBytes(bodyStream, maxBytes = 512 * 1024 - peekBytes.size)
            val html = (peekBytes + restHtml).toString(Charsets.UTF_8)

            val candidates = extractAllCandidateUrls(html, finalUrl)
            for (candidate in candidates) {
                val verified = probeCandidateStream(candidate, referer = finalUrl)
                if (verified != null) {
                    val pageTitle = extractHtmlTitle(html) ?: inferTitleFromUrl(verified.finalUrl)
                    val thumb = extractMetaContent(html, "og:image")?.let { resolveRelativeUrl(finalUrl, it) }
                    return@withContext LinkInspectionResult(
                        originalUrl = rawUrl.trim(),
                        resolvedVideoUrl = verified.finalUrl,
                        title = pageTitle,
                        contentType = verified.contentType,
                        contentLengthBytes = verified.contentLength,
                        isDirectVideo = !verified.isHls,
                        extractedFromHtml = true,
                        isHlsStream = verified.isHls,
                        thumbnailUrl = thumb,
                        httpStatus = verified.statusCode,
                        inspectionNotes = if (verified.isHls) {
                            "تم استخراج بث HLS من الصفحة بنجاح وجاهز للمونتاج."
                        } else {
                            "تم فحص الصفحة واستخراج رابط الفيديو الفعلي والتحقق من سلامته."
                        }
                    )
                }
            }

            throw VideoLinkException(
                message = "هذا الرابط يعرض صفحة ويب أو مشغّلاً محمياً ولا يتيح ملف فيديو قابلاً للتنزيل المباشر.",
                diagnosticDetail = "الموقع ($finalUrl) لم يُرجع مسار MP4/WebM/HLS مفتوحاً في الصفحة. انسخ رابط الفيديو المباشر (.mp4) أو اختر الفيديو من جهازك."
            )
        }
    }

    fun tryExtractVerifiedVideoFromHtml(html: String, baseUrl: String): LinkInspectionResult? {
        val candidates = extractAllCandidateUrls(html, baseUrl)
        for (candidate in candidates) {
            val verified = probeCandidateStream(candidate, referer = baseUrl)
            if (verified != null) {
                return LinkInspectionResult(
                    originalUrl = baseUrl,
                    resolvedVideoUrl = verified.finalUrl,
                    title = extractHtmlTitle(html) ?: inferTitleFromUrl(verified.finalUrl),
                    contentType = verified.contentType,
                    contentLengthBytes = verified.contentLength,
                    isDirectVideo = !verified.isHls,
                    extractedFromHtml = true,
                    isHlsStream = verified.isHls,
                    httpStatus = verified.statusCode,
                    inspectionNotes = "تم تجاوز صفحة التحويل واستخراج ملف الفيديو الفعلي."
                )
            }
        }
        return null
    }

    private data class VerifiedProbe(
        val finalUrl: String,
        val statusCode: Int,
        val contentType: String,
        val contentLength: Long,
        val isHls: Boolean
    )

    private fun probeCandidateStream(candidateUrl: String, referer: String): VerifiedProbe? {
        return runCatching {
            val req = Request.Builder()
                .url(candidateUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Referer", referer)
                .header("Range", "bytes=0-255")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@runCatching null
                val finalUrl = resp.request.url.toString()
                val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val totalLen = parseTotalLengthFromHeaders(resp.header("Content-Range"), resp.header("Content-Length"))
                val peek = readBoundedBytes(resp.body?.byteStream(), maxBytes = 256)
                val peekStr = peek.toString(Charsets.ISO_8859_1).trimStart()

                if (mime.contains("text/html") || peekStr.startsWith("<!doctype", true) || peekStr.startsWith("<html", true)) {
                    return@runCatching null
                }

                if (isHlsStream(mime, finalUrl, peekStr)) {
                    return@runCatching VerifiedProbe(
                        finalUrl = finalUrl,
                        statusCode = resp.code,
                        contentType = mime.ifBlank { "application/vnd.apple.mpegurl" },
                        contentLength = totalLen,
                        isHls = true
                    )
                }

                if (isVideoMimeOrBinaryStream(mime, finalUrl, peek)) {
                    return@runCatching VerifiedProbe(
                        finalUrl = finalUrl,
                        statusCode = resp.code,
                        contentType = mime.ifBlank { "video/mp4" },
                        contentLength = totalLen,
                        isHls = false
                    )
                }
                null
            }
        }.getOrNull()
    }

    private fun parseTotalLengthFromHeaders(contentRange: String?, contentLength: String?): Long {
        if (!contentRange.isNullOrBlank() && contentRange.contains('/')) {
            val totalPart = contentRange.substringAfterLast('/').trim()
            totalPart.toLongOrNull()?.let { return it }
        }
        return contentLength?.toLongOrNull() ?: -1L
    }

    private fun extractAllCandidateUrls(html: String, baseUrl: String): List<String> {
        val candidates = LinkedHashSet<String>()

        // 1. Google Drive virus-scan confirmation form / href
        if (baseUrl.contains("drive.google.com", ignoreCase = true) || baseUrl.contains("docs.google.com", ignoreCase = true)) {
            val actionMatch = Regex("""<form[^>]+id=["']download-form["'][^>]+action=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            if (actionMatch != null) {
                val actionUrl = decodeHtmlEntities(actionMatch.groupValues[1])
                val idVal = Regex("""name=["']id["']\s+value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val confirmVal = Regex("""name=["']confirm["']\s+value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val uuidVal = Regex("""name=["']uuid["']\s+value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val built = buildString {
                    append(actionUrl)
                    append(if (actionUrl.contains('?')) '&' else '?')
                    append("export=download")
                    if (!idVal.isNullOrBlank()) append("&id=$idVal")
                    if (!confirmVal.isNullOrBlank()) append("&confirm=$confirmVal")
                    if (!uuidVal.isNullOrBlank()) append("&uuid=$uuidVal")
                }
                candidates += built
            }
        }

        // 2. OpenGraph & Twitter stream meta tags
        listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { key ->
            extractMetaContent(html, key)?.let { raw ->
                val decoded = decodeHtmlEntities(raw)
                val resolved = resolveRelativeUrl(baseUrl, decoded)
                if (looksLikeHttpUrl(resolved)) candidates += resolved
            }
        }

        // 3. HTML5 <video> and <source> tags
        val tagRegex = Regex("""<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        tagRegex.findAll(html).forEach { match ->
            val resolved = resolveRelativeUrl(baseUrl, decodeHtmlEntities(match.groupValues[1]))
            if (looksLikeHttpUrl(resolved)) candidates += resolved
        }

        // 4. JSON / JS embedded stream fields (MP4 / WebM / M3U8)
        val jsonFieldRegex = Regex(
            """["'](?:playable_url_quality_hd|playable_url|contentUrl|video_url|videoUrl|mp4_url|playback_url|hd_src|sd_src|fallback_url|media_url|url|src)["']\s*:\s*["'](https?:[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        jsonFieldRegex.findAll(html).forEach { match ->
            val unescaped = unescapeJsonUrl(match.groupValues[1])
            if (looksLikeHttpUrl(unescaped) && (unescaped.contains(".mp4", true) || unescaped.contains(".m3u8", true) || unescaped.contains("video", true) || unescaped.contains("mime=video", true))) {
                candidates += unescaped
            }
        }

        // 5. Raw direct media URLs anywhere in page scripts
        val rawMediaRegex = Regex(
            """https?:\\?/\\?/[^\s"'<>\\]+?\.(?:mp4|mov|webm|m4v|m3u8)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        rawMediaRegex.findAll(html).forEach { match ->
            val unescaped = unescapeJsonUrl(match.value)
            if (looksLikeHttpUrl(unescaped)) candidates += unescaped
        }

        return candidates.toList()
    }

    private fun rewriteKnownHostingUrl(url: String): String {
        // Google Drive share link -> direct download endpoint
        val driveFileMatch = Regex("""https?://drive\.google\.com/file/d/([a-zA-Z0-9_-]+)""").find(url)
        if (driveFileMatch != null) {
            val fileId = driveFileMatch.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }
        val driveOpenMatch = Regex("""https?://drive\.google\.com/open\?id=([a-zA-Z0-9_-]+)""").find(url)
        if (driveOpenMatch != null) {
            val fileId = driveOpenMatch.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        // Dropbox dl=0 -> dl=1
        if (url.contains("dropbox.com/", ignoreCase = true)) {
            return when {
                url.contains("dl=0") -> url.replace("dl=0", "dl=1")
                url.contains('?') && !url.contains("dl=1") && !url.contains("raw=1") -> "$url&raw=1"
                !url.contains('?') -> "$url?raw=1"
                else -> url
            }
        }

        // GitHub blob -> raw.githubusercontent
        val ghBlob = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/(.+)$""").find(url)
        if (ghBlob != null) {
            val (owner, repo, rest) = ghBlob.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$rest"
        }

        return url
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

    private fun isHlsStream(contentType: String, url: String, peekText: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return contentType.contains("mpegurl") ||
            path.endsWith(".m3u8") ||
            peekText.startsWith("#EXTM3U")
    }

    private fun isVideoMimeOrBinaryStream(contentType: String, url: String, peekBytes: ByteArray): Boolean {
        if (contentType.startsWith("video/")) return true
        val hasVideoMagic = hasVideoContainerMagic(peekBytes)
        if (hasVideoMagic) return true
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val hasVideoExt = cleanPath.endsWith(".mp4") ||
            cleanPath.endsWith(".mov") ||
            cleanPath.endsWith(".m4v") ||
            cleanPath.endsWith(".webm") ||
            cleanPath.endsWith(".mkv")
        return hasVideoExt && (contentType.isBlank() || contentType.contains("octet-stream"))
    }

    private fun hasVideoContainerMagic(header: ByteArray): Boolean {
        if (header.size < 8) return false
        val str = String(header, Charsets.ISO_8859_1)
        val isMp4 = str.contains("ftyp")
        val isEbml = header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte()
        val isRiff = str.startsWith("RIFF")
        return isMp4 || isEbml || isRiff
    }

    private fun looksLikeHttpUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun decodeHtmlEntities(input: String): String =
        input.replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun unescapeJsonUrl(input: String): String =
        decodeHtmlEntities(
            input.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u002F", "/")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")
        )

    private fun extractHtmlTitle(html: String): String? {
        extractMetaContent(html, "og:title")?.takeIf { it.isNotBlank() }?.let { return decodeHtmlEntities(it) }
        val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        return titleMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { decodeHtmlEntities(it) }
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
            .takeIf { it.length >= 3 && !it.equals("uc", true) }
            ?: "مشروع فيديو جديد"
    }

    private fun readBoundedBytes(input: java.io.InputStream?, maxBytes: Int): ByteArray {
        if (input == null || maxBytes <= 0) return ByteArray(0)
        val buffer = ByteArray(minOf(4096, maxBytes))
        val out = ByteArrayOutputStream()
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
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        val sharedOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(SessionCookieJar())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
