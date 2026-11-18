# ClipCraft (كليب كرافت) — استوديو المونتاج الذكي وتحسين جودة الفيديو

تطبيق أندرويد مبني بلغة **Kotlin** و **Jetpack Compose** مع **Jetpack Media3 Transformer** و **Firebase AI Logic (Gemini 3.8 Flash)** لفحص روابط الفيديو وتنزيلها، استخراج اللقطات المهمة، رفع الجودة والدقة، قص الوقت، وكتابة التفاصيل والنصوص على الفيديو.

## محتويات ملفات التطبيق
- `app/src/main/java/com/example/downloader/` — **محرك فحص الروابط والتنزيل المدمج** (`VideoLinkInspector.kt`, `VideoDownloadEngine.kt`, `VideoFrameInspector.kt`).
- `app/src/main/java/com/example/ai/` — **محلل اللقطات المهمة وكتابة التفاصيل** عبر `GeminiHighlightAdvisor.kt` و `AppCheckInit.kt`.
- `app/src/main/java/com/example/editor/` — **محرك المونتاج وتحسين الجودة الفعلي** (`VideoMontageExporter.kt`).
- `app/src/main/java/com/example/ui/` — واجهات الاستوديو باللغة العربية.
- `.github/workflows/build-apk.yml` — ملف بناء ملف التنزيل (**APK**) تلقائياً على GitHub.

## كيفية تنزيل ملف التطبيق (APK) من GitHub
1. تأكد من الضغط على **Push to GitHub** من قائمة الإعدادات بعد آخر تعديل، وتأكد في GitHub من اختيار الفرع (**Branch**) الذي تم الرفع إليه إذا كان فرع `main` يحتوي على `README.md` فقط.
2. بعد ظهور الملفات في المستودع، افتح تبويب **Actions** في GitHub واختر آخر تشغيل لـ **Build ClipCraft APK**.
3. ستجد ملف التنزيل الجاهز **`ClipCraft-debug-apk`** في قسم **Artifacts** بالأسفل.
4. يمكنك أيضاً استخراج ملف **APK** مباشرةً من داخل منصة البناء عبر قائمة **Settings → Generate APK**.
