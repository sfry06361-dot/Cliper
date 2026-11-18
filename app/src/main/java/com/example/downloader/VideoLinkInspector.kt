package com.example.downloader

import com.example.model.LinkInspectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class VideoLinkException(
    message: String,
    val diagnosticDetail: String? = null
) : Exception(message)

/**
 * RFC-compliant in-memory cookie store using OkHttp Cookie.matches(url)
 * so parent-domain cookies (e.g. .tiktok.com, .google.com, .youtube.com)
 * carry over cleanly to CDN subdomains during inspection and download.
 */
class SessionCookieJar : CookieJar {
    private val cookies = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (incoming in cookies) {
            this.cookies.removeAll {
                it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
            }
            this.cookies.add(incoming)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}

/**
 * Self-contained link inspector packaged inside the application source tree.
 * - Extracts URLs cleanly from mobile share-sheet text (YouTube, TikTok, etc.)
 * - Rewrites cloud-share links (Drive/Dropbox/GitHub)
 * - Attempts direct stream resolution for YouTube & TikTok public videos
 * - Scrapes HTML/JSON media tags & verifies actual stream bytes (Range 0-255)
 * - Never throws uncaught exceptions out of normal invalid-URL or protection paths
 */
class VideoLinkInspector(
    private val client: OkHttpClient = sharedOkHttpClient
) {

    suspend fun inspectUrl(rawUrl: String): LinkInspectionResult = withContext(Dispatchers.IO) {
        val normalized = rewriteKnownHostingUrl(normalizeUrl(rawUrl))

        // Validate final URL for OkHttp safety so malformed characters never crash
        val parsedHttpUrl = normalized.toHttpUrlOrNull()
            ?: throw VideoLinkException(
                message = "صيغة الرابط غير صالحة أو تحتوي على رموز غير مدعومة.",
                diagnosticDetail = "تأكد من نسخ رابط الفيديو الصحيح بدون مسافات زائدة."
            )

        val host = parsedHttpUrl.host.lowercase()

        // 1. Specialized resolver for YouTube (watch, shorts, youtu.be, live)
        if (isYouTubeHost(host)) {
            extractYouTubeStream(parsedHttpUrl.toString(), rawUrl.trim())?.let { return@withContext it }
        }

        // 2. Specialized resolver for TikTok (tiktok.com, vm.tiktok.com, vt.tiktok.com)
        if (isTikTokHost(host)) {
            extractTikTokStream(parsedHttpUrl.toString(), rawUrl.trim())?.let { return@withContext it }
        }

        // 3. Direct request + generic HTML/JSON stream extraction
        val request = Request.Builder()
            .url(parsedHttpUrl)
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
                    message = buildPlatformHttpErrorMessage(host, statusCode),
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
                message = buildProtectedPlatformMessage(host),
                diagnosticDetail = "الموقع ($finalUrl) لم يُرجع مسار فيديو مباشراً (MP4/HLS). يمكنك اختيار الفيديو من زر «من الجهاز» أو لصق رابط ملف فيديو مباشر."
            )
        }
    }

    private fun extractYouTubeStream(url: String, originalRaw: String): LinkInspectionResult? {
        val videoId = parseYouTubeVideoId(url) ?: return null

        // Try official InnerTube player payloads (IOS & ANDROID_VR often return direct/HLS streams)
        val clientConfigs = listOf(
            Triple("IOS", "19.29.1", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"),
            Triple("ANDROID_VR", "1.56.21", "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip")
        )

        for ((clientName, clientVersion, ua) in clientConfigs) {
            val result = runCatching {
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", clientName)
                            put("clientVersion", clientVersion)
                            put("hl", "ar")
                            put("gl", "EG")
                        })
                    })
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", ua)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://www.youtube.com")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bodyStr = resp.body?.string().orEmpty()
                    if (bodyStr.isBlank()) return@runCatching null
                    val json = JSONObject(bodyStr)
                    parseYouTubePlayerJson(json, url, originalRaw, ua)
                }
            }.getOrNull()

            if (result != null) return result
        }

        // Fallback: parse ytInitialPlayerResponse from HTML watch page
        val htmlResult = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&has_verified=1")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val html = readBoundedBytes(resp.body?.byteStream(), 900 * 1024).toString(Charsets.UTF_8)
                val match = Regex("""ytInitialPlayerResponse\s*=\s*(\{.+?\})\s*;(?:\s*var|\s*</script>)""", RegexOption.DOT_MATCHES_ALL)
                    .find(html) ?: return@runCatching null
                val json = JSONObject(match.groupValues[1])
                parseYouTubePlayerJson(json, url, originalRaw, BROWSER_USER_AGENT)
            }
        }.getOrNull()

        if (htmlResult != null) return htmlResult

        throw VideoLinkException(
            message = "يوتيوب يفرض تشفيراً أو تحققاً أمنياً (Bot/Signature Protection) على هذا الفيديو.",
            diagnosticDetail = "تم حماية التطبيق من الإغلاق: روابط يوتيوب المشفرة لا تسمح بالتنزيل المباشر بدون خادم وسيط. يمكنك حفظ المقطع أو اختياره عبر زر «من الجهاز» للمونتاج الفوري."
        )
    }

    private fun parseYouTubePlayerJson(
        json: JSONObject,
        pageUrl: String,
        originalRaw: String,
        userAgent: String
    ): LinkInspectionResult? {
        val videoDetails = json.optJSONObject("videoDetails")
        val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() } ?: "فيديو يوتيوب"
        val thumb = videoDetails?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }

        val streamingData = json.optJSONObject("streamingData") ?: return null
        val formats = streamingData.optJSONArray("formats")

        if (formats != null) {
            val directUrls = mutableListOf<Pair<Int, String>>()
            for (i in 0 until formats.length()) {
                val fmt = formats.optJSONObject(i) ?: continue
                val directUrl = fmt.optString("url")
                val height = fmt.optInt("height", 360)
                if (directUrl.isNotBlank() && looksLikeHttpUrl(directUrl)) {
                    directUrls += height to directUrl
                }
            }
            for ((_, candidate) in directUrls.sortedByDescending { it.first }) {
                val verified = probeCandidateStream(candidate, referer = "https://www.youtube.com/", userAgent = userAgent)
                if (verified != null) {
                    return LinkInspectionResult(
                        originalUrl = originalRaw,
                        resolvedVideoUrl = verified.finalUrl,
                        title = title,
                        contentType = verified.contentType,
                        contentLengthBytes = verified.contentLength,
                        isDirectVideo = !verified.isHls,
                        extractedFromHtml = true,
                        isHlsStream = verified.isHls,
                        thumbnailUrl = thumb,
                        httpStatus = verified.statusCode,
                        inspectionNotes = "تم استخراج بث الفيديو المباشر من يوتيوب بنجاح."
                    )
                }
            }
        }

        val hlsUrl = streamingData.optString("hlsManifestUrl")
        if (hlsUrl.isNotBlank() && looksLikeHttpUrl(hlsUrl)) {
            val verified = probeCandidateStream(hlsUrl, referer = "https://www.youtube.com/", userAgent = userAgent)
            if (verified != null) {
                return LinkInspectionResult(
                    originalUrl = originalRaw,
                    resolvedVideoUrl = verified.finalUrl,
                    title = title,
                    contentType = verified.contentType,
                    contentLengthBytes = verified.contentLength,
                    isDirectVideo = false,
                    extractedFromHtml = true,
                    isHlsStream = true,
                    thumbnailUrl = thumb,
                    httpStatus = verified.statusCode,
                    inspectionNotes = "تم استخراج بث HLS مباشر من يوتيوب."
                )
            }
        }
        return null
    }

    private fun extractTikTokStream(url: String, originalRaw: String): LinkInspectionResult? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .get()
            .build()

        val extracted = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val finalPageUrl = resp.request.url.toString()
                val html = readBoundedBytes(resp.body?.byteStream(), 800 * 1024).toString(Charsets.UTF_8)

                val candidateUrls = LinkedHashSet<String>()
                // Try JSON keys used by TikTok __UNIVERSAL_DATA_FOR_REHYDRATION__ / SIGI_STATE
                val ttRegex = Regex(
                    """["'](?:playAddr|downloadAddr|PlayUrl|play_url)["']\s*:\s*["'](https?:[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )
                ttRegex.findAll(html).forEach { m ->
                    val decoded = unescapeJsonUrl(m.groupValues[1])
                    if (looksLikeHttpUrl(decoded)) candidateUrls += decoded
                }

                // Fallback to general URL extraction (urlList arrays, og:video, etc.)
                candidateUrls += extractAllCandidateUrls(html, finalPageUrl)

                val title = extractHtmlTitle(html)?.takeIf { it.isNotBlank() } ?: "فيديو تيك توك"
                val thumb = extractMetaContent(html, "og:image")

                for (candidate in candidateUrls) {
                    val verified = probeCandidateStream(
                        candidateUrl = candidate,
                        referer = "https://www.tiktok.com/",
                        userAgent = DESKTOP_USER_AGENT
                    )
                    if (verified != null) {
                        return@runCatching LinkInspectionResult(
                            originalUrl = originalRaw,
                            resolvedVideoUrl = verified.finalUrl,
                            title = title,
                            contentType = verified.contentType,
                            contentLengthBytes = verified.contentLength,
                            isDirectVideo = !verified.isHls,
                            extractedFromHtml = true,
                            isHlsStream = verified.isHls,
                            thumbnailUrl = thumb,
                            httpStatus = verified.statusCode,
                            inspectionNotes = "تم استخراج فيديو تيك توك والتحقق من مساره بنجاح."
                        )
                    }
                }
                null
            }
        }.getOrNull()

        if (extracted != null) return extracted

        throw VideoLinkException(
            message = "تيك توك يفرض تحقق أمان (Captcha / Cookie Protection) على هذا الرابط.",
            diagnosticDetail = "تم منع إغلاق التطبيق بأمان: انسخ رابط الفيديو المباشر أو احفظ مقطع تيك توك على هاتفك واختره من زر «من الجهاز» للمونتاج الفوري."
        )
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

    private fun probeCandidateStream(
        candidateUrl: String,
        referer: String,
        userAgent: String = BROWSER_USER_AGENT
    ): VerifiedProbe? {
        return runCatching {
            val httpUrl = candidateUrl.toHttpUrlOrNull() ?: return@runCatching null
            val req = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", userAgent)
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

        listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { key ->
            extractMetaContent(html, key)?.let { raw ->
                val decoded = decodeHtmlEntities(raw)
                val resolved = resolveRelativeUrl(baseUrl, decoded)
                if (looksLikeHttpUrl(resolved)) candidates += resolved
            }
        }

        val tagRegex = Regex("""<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        tagRegex.findAll(html).forEach { match ->
            val resolved = resolveRelativeUrl(baseUrl, decodeHtmlEntities(match.groupValues[1]))
            if (looksLikeHttpUrl(resolved)) candidates += resolved
        }

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
        val driveFileMatch = Regex("""https?://drive\.google\.com/file/d/([a-zA-Z0-9_-]+)""").find(url)
        if (driveFileMatch != null) {
            return "https://drive.google.com/uc?export=download&id=${driveFileMatch.groupValues[1]}"
        }
        val driveOpenMatch = Regex("""https?://drive\.google\.com/open\?id=([a-zA-Z0-9_-]+)""").find(url)
        if (driveOpenMatch != null) {
            return "https://drive.google.com/uc?export=download&id=${driveOpenMatch.groupValues[1]}"
        }

        if (url.contains("dropbox.com/", ignoreCase = true)) {
            return when {
                url.contains("dl=0") -> url.replace("dl=0", "dl=1")
                url.contains('?') && !url.contains("dl=1") && !url.contains("raw=1") -> "$url&raw=1"
                !url.contains('?') -> "$url?raw=1"
                else -> url
            }
        }

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
        // Automatically extract the first http/https URL if pasted from TikTok/YouTube share sheets with text around it
        val extracted = Regex("""https?://[^\s"'<>\]\)]+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
        val candidate = extracted ?: trimmed
        val withScheme = if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
            candidate
        } else {
            "https://$candidate"
        }
        val httpUrl = withScheme.toHttpUrlOrNull()
            ?: run {
                val uri = runCatching { URI(withScheme) }.getOrNull()
                if (uri == null || uri.host.isNullOrBlank()) {
                    throw VideoLinkException(
                        message = "صيغة الرابط غير صالحة.",
                        diagnosticDetail = "تأكد من نسخ رابط الفيديو بالكامل (مثال: https://example.com/video.mp4)."
                    )
                }
                throw VideoLinkException(
                    message = "الرابط يحتوي على أحرف غير صالحة للاتصال.",
                    diagnosticDetail = "انسخ عنوان الرابط فقط بدون نصوص إضافية."
                )
            }
        return httpUrl.toString()
    }

    private fun parseYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|/videos/|embed/|shorts/|live/|youtu\.be/)([a-zA-Z0-9_-]{11})"""),
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})""")
        )
        for (p in patterns) {
            p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun isYouTubeHost(host: String): Boolean =
        host.contains("youtube.com") || host.contains("youtu.be") || host.contains("youtube-nocookie.com")

    private fun isTikTokHost(host: String): Boolean =
        host.contains("tiktok.com") || host.contains("tiktokv.com")

    private fun buildPlatformHttpErrorMessage(host: String, statusCode: Int): String = when {
        isYouTubeHost(host) -> "يوتيوب رفض الوصول المباشر للفيديو (HTTP $statusCode)."
        isTikTokHost(host) -> "تيك توك حظر الفحص التلقائي لهذا الرابط (HTTP $statusCode)."
        statusCode == 403 || statusCode == 401 -> "الخادم منع الوصول إلى الملف (HTTP $statusCode - رابط محمي أو منتهي الصلاحية)."
        statusCode == 404 -> "الفيديو غير موجود على هذا الرابط (HTTP 404)."
        else -> "تعذر الوصول للرابط (رمز HTTP $statusCode)."
    }

    private fun buildProtectedPlatformMessage(host: String): String = when {
        isYouTubeHost(host) -> "رابط يوتيوب مشفّر ولا يوفر ملف MP4 مباشراً للتنزيل بدون تشفير."
        isTikTokHost(host) -> "رابط تيك توك محمي بصفحة تحقق ولا يتيح ملف الفيديو المباشر."
        host.contains("instagram.com") || host.contains("facebook.com") || host.contains("fb.watch") ->
            "منصات التواصل الاجتماعي تحمي صفحاتها بتسجيل الدخول ولا تتيح ملف فيديو مباشراً."
        else -> "هذا الرابط يعرض صفحة ويب أو مشغّلاً محمياً ولا يتيح ملف فيديو قابلاً للتنزيل المباشر."
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
                .replace("\\u002f", "/")
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
            .takeIf { it.length >= 3 && !it.equals("uc", true) && !it.equals("videoplayback", true) }
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

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
