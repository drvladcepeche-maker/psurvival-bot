package com.psurvival.worker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Bitmap
import android.view.Display
import android.os.Build
import com.googlecode.tesseract.android.TessBaseAPI
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WorkerAccessibilityService : AccessibilityService() {
    private val executor = Executors.newScheduledThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocrLock = Any()
    @Volatile private var tesseractApi: TessBaseAPI? = null
    @Volatile private var activeJobId: String? = null
    @Volatile private var lastHeartbeatAt = 0L
    private val capturedUiSnapshots = mutableListOf<JSONObject>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        executor.scheduleWithFixedDelay({ pollOrHeartbeat() }, 2, 10, TimeUnit.SECONDS)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        if (!prefs.getBoolean("record_game_actions", false)) return
        val selectedPackage = prefs.getString("selected_game_package", "com.global.ztmslg")
            .orEmpty().ifBlank { "com.global.ztmslg" }
        val activePackage = event.packageName?.toString().orEmpty()
        if (activePackage != selectedPackage && activePackage != "com.global.ztmslg") return

        // Record only explicit accessibility click events. No screenshots, text fields,
        // passwords, chat contents, or events from other apps are captured.
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val source = event.source ?: return
        if (source.isEditable || source.className?.toString().orEmpty().contains("EditText", true)) return
        val bounds = android.graphics.Rect()
        source.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.centerX() < 0 || bounds.centerY() < 0) return

        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        val x = (bounds.centerX() * 10000 / metrics.widthPixels).coerceIn(0, 10000)
        val y = (bounds.centerY() * 10000 / metrics.heightPixels).coerceIn(0, 10000)
        synchronized(recordingLock) {
            val current = System.currentTimeMillis()
            val recordedAt = event.eventTime
            val steps = runCatching {
                JSONArray(prefs.getString("recorded_game_steps", "[]").orEmpty())
            }.getOrDefault(JSONArray())
            if (steps.length() >= 500) return
            val previous = prefs.getLong("record_last_event_time", 0L)
            val gap = if (previous > 0L && recordedAt > previous) recordedAt - previous else 0L
            if (gap >= 700L) {
                steps.put(JSONObject().put("type", "wait").put("duration_ms", gap.coerceIn(700L, 10_000L)))
            }
            val textLabel = source.text?.toString()?.trim()?.take(120).orEmpty()
            val descriptionLabel = source.contentDescription?.toString()?.trim()?.take(120).orEmpty()
            // Prefer semantic selectors so recorded scenarios survive small layout changes.
            // Fall back to normalized coordinates when Unity exposes no accessible label.
            val step = when {
                textLabel.isNotEmpty() -> JSONObject()
                    .put("type", "tap_text")
                    .put("text", textLabel)
                descriptionLabel.isNotEmpty() -> JSONObject()
                    .put("type", "tap_description")
                    .put("description", descriptionLabel)
                else -> JSONObject()
                    .put("type", "tap_relative")
                    .put("x", x)
                    .put("y", y)
            }
            steps.put(step)
            prefs.edit()
                .putString("recorded_game_steps", steps.toString())
                .putLong("record_last_event_time", recordedAt)
                .putLong("record_last_saved_at", current)
                .apply()
        }
    }

    private val recordingLock = Any()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        executor.shutdownNow()
        synchronized(ocrLock) {
            runCatching { tesseractApi?.recycle() }
            tesseractApi = null
        }
        super.onDestroy()
    }

    private fun settings(): Pair<String, String>? {
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        val id = prefs.getString("worker_id", "").orEmpty().trim()
        val token = prefs.getString("token", "").orEmpty().trim()
        return if (id.isNotEmpty() && token.startsWith("psw_")) id to token else null
    }

    @Synchronized
    private fun pollOrHeartbeat() {
        val config = settings() ?: return
        val current = activeJobId
        if (current != null) {
            if (System.currentTimeMillis() - lastHeartbeatAt > 25_000) {
                runCatching { api(config.second, JSONObject().put("action", "heartbeat").put("job_id", current)) }
                lastHeartbeatAt = System.currentTimeMillis()
            }
            return
        }
        val response = runCatching {
            api(config.second, JSONObject().put("action", "claim"))
        }.getOrNull() ?: return
        if (!response.optBoolean("ok")) return
        val job = response.optJSONObject("job") ?: return
        val jobId = job.optString("id")
        if (jobId.isBlank()) return
        activeJobId = jobId
        lastHeartbeatAt = System.currentTimeMillis()
        executor.execute { executeJob(config.second, job) }
    }

    private fun executeJob(token: String, job: JSONObject) {
        val jobId = job.optString("id")
        try {
            capturedUiSnapshots.clear()
            val payload = job.optJSONObject("payload") ?: JSONObject()
            val steps = payload.optJSONArray("steps") ?: JSONArray()
            if (steps.length() == 0) throw IllegalArgumentException("Задание не содержит steps; шаблон действий не настроен")
            val result = JSONObject().put("executed_steps", steps.length()).put("task_type", job.optString("task_type"))
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: throw IllegalArgumentException("Некорректный шаг #$i")
                executeStep(step)
            }
            if (capturedUiSnapshots.isNotEmpty()) {
                val snapshots = JSONArray()
                capturedUiSnapshots.forEach { snapshots.put(it) }
                result.put("ui_snapshots", snapshots)
            }
            api(token, JSONObject().put("action", "complete").put("job_id", jobId).put("result", result))
        } catch (error: Throwable) {
            runCatching {
                api(token, JSONObject()
                    .put("action", "fail")
                    .put("job_id", jobId)
                    .put("error", (error.message ?: "Executor error").take(500))
                    .put("retryable", error !is IllegalArgumentException)
                    .put("diagnostics", compactFailureDiagnostics()))
            }
        } finally {
            activeJobId = null
            lastHeartbeatAt = 0L
        }
    }

    /**
     * Persist a bounded, text-only diagnostic trail for failed OCR/screen steps.
     * Screenshots, editable fields, credentials and unrelated app content are never stored.
     */
    private fun compactFailureDiagnostics(): JSONObject {
        val snapshots = JSONArray()
        capturedUiSnapshots.takeLast(3).forEach { snapshot ->
            val ocr = snapshot.optJSONObject("screenshot_ocr") ?: JSONObject()
            val sourceWords = ocr.optJSONArray("words") ?: JSONArray()
            val candidates = (0 until sourceWords.length()).mapNotNull { index ->
                sourceWords.optJSONObject(index)
            }
            val lowConfidence = candidates.sortedBy {
                it.optDouble("confidence", 101.0)
            }.take(80)
            val highConfidence = candidates.sortedByDescending {
                it.optDouble("confidence", -1.0)
            }.take(20)
            val selectedWords = JSONArray()
            (lowConfidence + highConfidence).distinctBy {
                listOf(it.optString("pass"), it.optString("text"), it.optJSONObject("bounds")?.toString())
                    .joinToString("|")
            }.forEach { word ->
                selectedWords.put(JSONObject()
                    .put("text", word.optString("text").take(80))
                    .put("pass", word.optString("pass"))
                    .put("confidence", word.optDouble("confidence", -1.0))
                    .put("bounds", word.optJSONObject("bounds") ?: JSONObject()))
            }
            val compactOcr = JSONObject()
                .put("status", ocr.optString("status", "unknown"))
                .put("engine", ocr.optString("engine", ""))
                .put("page_segmentation", ocr.optString("page_segmentation", ""))
                .put("passes", ocr.optJSONArray("passes") ?: JSONArray())
                .put("text", ocr.optString("text", "").take(4000))
                .put("classification_text", ocr.optString("classification_text", "").take(4000))
                .put("word_count", sourceWords.length())
                .put("words_sample", selectedWords)
            snapshots.put(JSONObject()
                .put("package_name", snapshot.optString("package_name", ""))
                .put("captured_at_ms", snapshot.optLong("captured_at_ms", 0L))
                .put("window_bounds", snapshot.optJSONObject("window_bounds") ?: JSONObject())
                .put("screen_classification", snapshot.optJSONObject("screen_classification") ?: JSONObject())
                .put("screenshot_ocr", compactOcr))
        }
        return JSONObject().put("ui_snapshots", snapshots)
    }

    private fun executeStep(step: JSONObject) {
        when (step.optString("type")) {
            "wait" -> {
                val millis = step.optLong("duration_ms", 1000L).coerceIn(250L, 10_000L)
                Thread.sleep(millis)
            }
            "back" -> {
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw IllegalStateException("Не удалось выполнить Back")
            }
            "tap_text" -> {
                val needle = step.optString("text").trim()
                if (needle.isEmpty() || !tapMatchingNode(needle, false)) throw IllegalStateException("Не найден кликабельный текст: $needle")
            }
            "tap_ocr_word" -> {
                val needle = step.optString("text").trim()
                val normalizedNeedle = needle.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
                if (normalizedNeedle.isEmpty() || needle.any { it.isWhitespace() }) {
                    throw IllegalArgumentException("tap_ocr_word requires one word in text")
                }
                val snapshot = captureGameUiSnapshot()
                capturedUiSnapshots.add(snapshot)
                val words = snapshot.optJSONObject("screenshot_ocr")?.optJSONArray("words")
                    ?: throw IllegalStateException("OCR word boxes are unavailable")
                val matches = (0 until words.length()).mapNotNull { index ->
                    words.optJSONObject(index)?.takeIf { item ->
                        item.optString("text").lowercase(java.util.Locale.ROOT)
                            .filter { it.isLetterOrDigit() } == normalizedNeedle
                    }
                }
                // A word can be recognized by both independent page-layout passes.
                // Merge only spatially overlapping boxes; separate occurrences remain ambiguous.
                val clusters = mutableListOf<MutableList<JSONObject>>()
                for (candidate in matches) {
                    val candidateBounds = candidate.optJSONObject("bounds") ?: continue
                    val cluster = clusters.firstOrNull { existing ->
                        sameOcrWord(candidateBounds, existing.first().optJSONObject("bounds"))
                    }
                    if (cluster == null) clusters.add(mutableListOf(candidate)) else cluster.add(candidate)
                }
                if (clusters.size != 1) {
                    throw IllegalStateException("OCR word match must be unique: $needle (locations=${clusters.size})")
                }
                val cluster = clusters.single()
                val match = cluster.maxByOrNull { it.optDouble("confidence", -1.0) }
                    ?: throw IllegalStateException("OCR word box is unavailable: $needle")
                val confidence = match.optDouble("confidence", -1.0)
                val independentPasses = cluster.map { it.optString("pass") }.filter { it.isNotBlank() }.distinct().size
                val averageConfidence = cluster.map { it.optDouble("confidence", -1.0) }
                    .filter { it >= 0.0 }.average()
                // Keep the strict single-pass threshold. A lower-confidence hit is accepted
                // only when both layout modes independently locate the same word and box.
                val corroborated = independentPasses >= 2 && confidence >= 30.0 && averageConfidence >= 35.0
                if (confidence < 55.0 && !corroborated) {
                    throw IllegalStateException(
                        "OCR confidence too low for tap: $needle ($confidence; corroborated_passes=$independentPasses)"
                    )
                }
                val bounds = match.optJSONObject("bounds")
                    ?: throw IllegalStateException("OCR word has no bounding box: $needle")
                val metrics = resources.displayMetrics
                val left = bounds.optInt("left", -1)
                val top = bounds.optInt("top", -1)
                val right = bounds.optInt("right", -1)
                val bottom = bounds.optInt("bottom", -1)
                if (left < 0 || top < 0 || right <= left || bottom <= top) {
                    throw IllegalStateException("Invalid OCR word bounds: $needle")
                }
                val offsetX = step.optInt("offset_x_px", 0)
                val offsetY = step.optInt("offset_y_px", 0)
                val x = ((left + right) / 2f * metrics.widthPixels / 10000f + offsetX)
                    .coerceIn(0f, (metrics.widthPixels - 1).toFloat())
                val y = ((top + bottom) / 2f * metrics.heightPixels / 10000f + offsetY)
                    .coerceIn(0f, (metrics.heightPixels - 1).toFloat())
                val windowBounds = snapshot.optJSONObject("window_bounds")
                    ?: throw IllegalStateException("Active game window bounds are unavailable")
                val windowLeft = windowBounds.optInt("left", 0)
                val windowTop = windowBounds.optInt("top", 0)
                val windowRight = windowBounds.optInt("right", 0)
                val windowBottom = windowBounds.optInt("bottom", 0)
                if (x < windowLeft || x >= windowRight || y < windowTop || y >= windowBottom) {
                    throw IllegalStateException("OCR target is outside the interactive game window: $needle")
                }
                if (!tapCoordinates(x, y)) throw IllegalStateException("OCR word tap failed: $needle")
            }
            "tap_description" -> {
                val needle = step.optString("description").trim()
                if (needle.isEmpty() || !tapMatchingNode(needle, true)) throw IllegalStateException("Не найдено описание: $needle")
            }
            "tap_coordinates" -> {
                val x = step.optInt("x", -1)
                val y = step.optInt("y", -1)
                if (x < 0 || y < 0 || !tapCoordinates(x.toFloat(), y.toFloat())) throw IllegalStateException("Не удалось нажать координаты")
            }
            "tap_relative", "tap_point" -> {
                val x = step.optInt("x", -1)
                val y = step.optInt("y", -1)
                if (x !in 0..10000 || y !in 0..10000) throw IllegalArgumentException("x и y должны быть долями экрана 0–10000")
                val metrics = resources.displayMetrics
                val px = x * metrics.widthPixels / 10000f
                val py = y * metrics.heightPixels / 10000f
                if (!tapCoordinates(px, py)) throw IllegalStateException("Не удалось нажать относительную точку")
            }
            "swipe" -> {
                val metrics = resources.displayMetrics
                val sx = step.optInt("start_x", -1)
                val sy = step.optInt("start_y", -1)
                val ex = step.optInt("end_x", -1)
                val ey = step.optInt("end_y", -1)
                val duration = step.optLong("duration_ms", 500L).coerceIn(100L, 10000L)
                if (listOf(sx, sy, ex, ey).any { it !in 0..10000 }) throw IllegalArgumentException("Координаты swipe должны быть 0–10000")
                val path = Path().apply {
                    moveTo(sx * metrics.widthPixels / 10000f, sy * metrics.heightPixels / 10000f)
                    lineTo(ex * metrics.widthPixels / 10000f, ey * metrics.heightPixels / 10000f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                    .build()
                if (!dispatchGestureAndWait(gesture, duration + 2_000L)) throw IllegalStateException("Не удалось выполнить swipe")
            }
            "launch_app" -> {
                val requestedPackage = step.optString("package_name").trim()
                if (requestedPackage.isEmpty()) throw IllegalArgumentException("package_name обязателен")
                val prefs = getSharedPreferences("worker", MODE_PRIVATE)
                val selectedGamePackage = prefs.getString("selected_game_package", "").orEmpty().trim()
                // Existing server scenarios default to the original package; route those launches
                // to the user-selected original/clone. Explicit unrelated app launches stay intact.
                val packageName = if (
                    selectedGamePackage.isNotEmpty() &&
                    (requestedPackage == "com.global.ztmslg" || requestedPackage == selectedGamePackage)
                ) selectedGamePackage else requestedPackage
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                    ?: throw IllegalStateException("Приложение не найдено: $packageName")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            "assert_screen" -> {
                val expected = step.optString("category").trim().lowercase(java.util.Locale.ROOT)
                val allowed = setOf("city", "building", "quests", "map", "heroes", "alliance", "mail", "events", "battle")
                if (expected !in allowed) {
                    throw IllegalArgumentException("assert_screen category must be one of: ${allowed.joinToString()}")
                }
                var lastReason = "classification_missing"
                var actual = "unknown"
                // Unity screens can be between transitions for a short period. Retry only
                // unknown classifications; a confidently different screen remains a hard stop.
                for (attempt in 0 until 3) {
                    val snapshot = captureGameUiSnapshot()
                    capturedUiSnapshots.add(snapshot)
                    val classification = snapshot.optJSONObject("screen_classification")
                    actual = classification?.optString("category", "unknown") ?: "unknown"
                    lastReason = classification?.optString("reason", "classification_missing")
                        ?: "classification_missing"
                    if (actual == expected) return
                    if (actual != "unknown") {
                        throw IllegalStateException(
                            "Screen guard failed: expected=$expected actual=$actual reason=$lastReason"
                        )
                    }
                    if (attempt < 2) Thread.sleep(700L)
                }
                throw IllegalStateException(
                    "Screen guard failed: expected=$expected actual=$actual reason=$lastReason attempts=3"
                )
            }
            "capture_ui" -> {
                capturedUiSnapshots.add(captureGameUiSnapshot())
            }
            else -> throw IllegalArgumentException("Неизвестный тип шага: ${step.optString("type")}")
        }
    }

    /**
     * Captures only visible, non-editable accessibility labels from the selected game window.
     * Avoids screenshots, input fields, account credentials, and unrelated apps.
     */
    /**
     * Captures visible game accessibility labels and performs on-device OCR on a screenshot.
     * The bitmap is processed in memory only; no screenshot file is written to disk.
     */
    private fun captureGameUiSnapshot(): JSONObject {
        val root = rootInActiveWindow ?: throw IllegalStateException("Нет активного окна для capture_ui")
        val activePackage = root.packageName?.toString().orEmpty()
        val windowBounds = android.graphics.Rect()
        root.getBoundsInScreen(windowBounds)
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        val selectedPackage = prefs.getString("selected_game_package", "com.global.ztmslg")
            .orEmpty().ifBlank { "com.global.ztmslg" }
        if (activePackage != selectedPackage && activePackage != "com.global.ztmslg") {
            root.recycle()
            throw IllegalStateException("capture_ui заблокирован вне игры: $activePackage")
        }

        val labels = JSONArray()
        var visited = 0
        try {
            fun walk(node: AccessibilityNodeInfo) {
                if (visited >= 100 || labels.length() >= 60) return
                visited++
                val className = node.className?.toString().orEmpty()
                val isEditable = node.isEditable || className.contains("EditText", ignoreCase = true)
                if (!isEditable) {
                    val text = node.text?.toString()?.trim().orEmpty().take(120)
                    val description = node.contentDescription?.toString()?.trim().orEmpty().take(120)
                    if (text.isNotEmpty() || description.isNotEmpty()) {
                        val item = JSONObject().put("clickable", node.isClickable)
                        if (text.isNotEmpty()) item.put("text", text)
                        if (description.isNotEmpty()) item.put("description", description)
                        labels.put(item)
                    }
                }
                for (i in 0 until node.childCount) {
                    if (visited >= 100 || labels.length() >= 60) break
                    val child = node.getChild(i) ?: continue
                    walk(child)
                }
            }
            walk(root)
        } finally {
            root.recycle()
        }

        val ocr = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            JSONObject()
                .put("status", "unsupported")
                .put("reason", "screen_capture_requires_android_11_api_30")
        } else {
            runCatching {
                val bitmap = captureGameScreenshot()
                try {
                    recognizeScreenshot(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }.getOrElse { error ->
                JSONObject()
                    .put("status", "failed")
                    .put("error", (error.message ?: "Screenshot OCR failed").take(240))
            }
        }

        val classificationText = if (ocr.optString("status") == "ok") {
            ocr.optString("classification_text").ifBlank { ocr.optString("text") }
        } else ""
        val screenClassification = GameScreenClassifier.classify(classificationText)

        return JSONObject()
            .put("package_name", activePackage)
            .put("window_bounds", JSONObject()
                .put("left", windowBounds.left)
                .put("top", windowBounds.top)
                .put("right", windowBounds.right)
                .put("bottom", windowBounds.bottom))
            .put("captured_at_ms", System.currentTimeMillis())
            .put("node_count", visited)
            .put("labels", labels)
            .put("screenshot_ocr", ocr)
            .put("screen_classification", screenClassification)
            .put("note", if (labels.length() == 0 && ocr.optString("status") != "ok") {
                "No accessibility labels and screenshot OCR unavailable; Unity-rendered UI may not be readable."
            } else "")
    }

    @android.annotation.TargetApi(30)
    private fun captureGameScreenshot(): Bitmap {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val failure = AtomicReference<String?>(null)

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        if (hardwareBitmap == null) {
                            failure.set("Не удалось преобразовать screenshot HardwareBuffer в Bitmap")
                        } else {
                            result.set(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false))
                            hardwareBitmap.recycle()
                        }
                    } finally {
                        hardwareBuffer.close()
                    }
                } catch (error: Throwable) {
                    failure.set(error.message ?: "Screenshot conversion failed")
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                failure.set("takeScreenshot error=$errorCode")
                latch.countDown()
            }
        })

        if (!latch.await(12, TimeUnit.SECONDS)) {
            throw IllegalStateException("Тайм-аут получения снимка экрана")
        }
        return result.get() ?: throw IllegalStateException(failure.get() ?: "Screenshot bitmap is empty")
    }

    private data class OcrPass(
        val mode: String,
        val text: String,
        val lines: JSONArray,
        val words: JSONArray,
        val meanWordConfidence: Double,
        val quality: Double
    )

    private fun recognizeScreenshot(bitmap: Bitmap): JSONObject = synchronized(ocrLock) {
        val api = getOrCreateTesseract()
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val modes = listOf(
            "sparse_text" to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
            "auto" to TessBaseAPI.PageSegMode.PSM_AUTO
        )
        val passes = modes.map { (modeName, mode) ->
            api.setPageSegMode(mode)
            api.setImage(bitmap)
            try {
                val text = api.getUTF8Text().orEmpty().take(12000)
                val lines = extractOcrItems(
                    api, TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE, width, height, 250, modeName
                )
                val words = extractOcrItems(
                    api, TessBaseAPI.PageIteratorLevel.RIL_WORD, width, height, 500, modeName
                )
                val confidences = (0 until words.length()).mapNotNull { index ->
                    words.optJSONObject(index)?.optDouble("confidence", -1.0)?.takeIf { it >= 0.0 }
                }
                val meanConfidence = if (confidences.isEmpty()) 0.0 else confidences.average()
                val quality = meanConfidence +
                    words.length().coerceAtMost(40) * 0.45 +
                    text.length.coerceAtMost(3000) / 1000.0
                OcrPass(modeName, text, lines, words, meanConfidence, quality)
            } finally {
                api.clear()
            }
        }
        val best = passes.maxByOrNull { it.quality }
            ?: throw IllegalStateException("Tesseract produced no OCR passes")
        // Keep the best pass as the primary OCR transcript, but classify against
        // the bounded union so a high-confidence layout pass cannot hide labels
        // recognized only by the alternate layout.
        val classificationText = passes.joinToString("\n") { it.text }.take(24000)
        val allWords = JSONArray()
        passes.forEach { pass ->
            for (index in 0 until pass.words.length()) {
                pass.words.optJSONObject(index)?.let { allWords.put(it) }
            }
        }
        val passDiagnostics = JSONArray()
        passes.forEach { pass ->
            passDiagnostics.put(JSONObject()
                .put("mode", pass.mode)
                .put("word_count", pass.words.length())
                .put("mean_word_confidence", pass.meanWordConfidence)
                .put("quality_score", pass.quality))
        }
        JSONObject()
            .put("status", "ok")
            .put("engine", "tesseract4android_5.5.1")
            .put("language", "rus")
            .put("page_segmentation", best.mode)
            .put("passes", passDiagnostics)
            .put("processed_on_device", true)
            .put("image_width", width)
            .put("image_height", height)
            .put("text", best.text)
            .put("classification_text", classificationText)
            .put("lines", best.lines)
            .put("words", allWords)
    }

    private fun extractOcrItems(
        api: TessBaseAPI,
        level: Int,
        width: Int,
        height: Int,
        limit: Int,
        pass: String
    ): JSONArray {
        val items = JSONArray()
        val iterator = api.getResultIterator() ?: return items
        try {
            iterator.begin()
            do {
                val itemText = iterator.getUTF8Text(level)?.trim().orEmpty().take(160)
                if (itemText.isNotEmpty()) {
                    val bounds = iterator.getBoundingRect(level)
                    val item = JSONObject()
                        .put("text", itemText)
                        .put("pass", pass)
                        .put("bounds", JSONObject()
                            .put("left", (bounds.left * 10000 / width).coerceIn(0, 10000))
                            .put("top", (bounds.top * 10000 / height).coerceIn(0, 10000))
                            .put("right", (bounds.right * 10000 / width).coerceIn(0, 10000))
                            .put("bottom", (bounds.bottom * 10000 / height).coerceIn(0, 10000)))
                    val confidence = iterator.confidence(level)
                    if (confidence >= 0f) item.put("confidence", confidence.toDouble())
                    items.put(item)
                }
            } while (items.length() < limit && iterator.next(level))
        } finally {
            iterator.delete()
        }
        return items
    }

    private fun sameOcrWord(first: JSONObject, second: JSONObject?): Boolean {
        if (second == null) return false
        val aLeft = first.optInt("left", -1)
        val aTop = first.optInt("top", -1)
        val aRight = first.optInt("right", -1)
        val aBottom = first.optInt("bottom", -1)
        val bLeft = second.optInt("left", -1)
        val bTop = second.optInt("top", -1)
        val bRight = second.optInt("right", -1)
        val bBottom = second.optInt("bottom", -1)
        if (aLeft < 0 || aTop < 0 || aRight <= aLeft || aBottom <= aTop ||
            bLeft < 0 || bTop < 0 || bRight <= bLeft || bBottom <= bTop) return false
        val intersectionWidth = (minOf(aRight, bRight) - maxOf(aLeft, bLeft)).coerceAtLeast(0)
        val intersectionHeight = (minOf(aBottom, bBottom) - maxOf(aTop, bTop)).coerceAtLeast(0)
        val intersection = intersectionWidth * intersectionHeight
        val smallerArea = minOf((aRight - aLeft) * (aBottom - aTop), (bRight - bLeft) * (bBottom - bTop))
        if (smallerArea > 0 && intersection.toDouble() / smallerArea >= 0.25) return true
        val centerAX = (aLeft + aRight) / 2
        val centerAY = (aTop + aBottom) / 2
        val centerBX = (bLeft + bRight) / 2
        val centerBY = (bTop + bBottom) / 2
        val toleranceX = maxOf(aRight - aLeft, bRight - bLeft) / 3 + 35
        val toleranceY = maxOf(aBottom - aTop, bBottom - bTop) / 2 + 25
        return kotlin.math.abs(centerAX - centerBX) <= toleranceX &&
            kotlin.math.abs(centerAY - centerBY) <= toleranceY
    }

    private fun getOrCreateTesseract(): TessBaseAPI {
        tesseractApi?.let { return it }

        val dataDirectory = File(filesDir, "tesseract")
        val tessdataDirectory = File(dataDirectory, "tessdata")
        if (!tessdataDirectory.exists() && !tessdataDirectory.mkdirs()) {
            throw IllegalStateException("Cannot create private Tesseract data directory")
        }

        val russianModel = File(tessdataDirectory, "rus.traineddata")
        assets.open("tessdata/rus.traineddata").use { input ->
            FileOutputStream(russianModel, false).use { output -> input.copyTo(output) }
        }
        if (russianModel.length() < 1_000_000L) {
            throw IllegalStateException("Russian Tesseract model is missing or incomplete")
        }

        val api = TessBaseAPI()
        val initialized = runCatching {
            api.init(dataDirectory.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY)
        }.getOrDefault(false)
        if (!initialized) {
            api.recycle()
            throw IllegalStateException("Tesseract initialization failed for rus.traineddata")
        }
        tesseractApi = api
        return api
    }

    private fun tapMatchingNode(needle: String, description: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNode(root, needle, description) ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNode(node: AccessibilityNodeInfo, needle: String, description: Boolean): AccessibilityNodeInfo? {
        val own = if (description) node.contentDescription?.toString() else node.text?.toString()
        if (!own.isNullOrBlank() && own.contains(needle, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, needle, description)
            if (found != null) return found
        }
        return null
    }

    private fun tapCoordinates(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGestureAndWait(gesture, 3_000L)
    }

    // dispatchGesture() only confirms that Android accepted the gesture, not that it finished.
    // Wait for the callback so sequential scenario steps cannot race each other.
    private fun dispatchGestureAndWait(
        gesture: GestureDescription,
        timeoutMs: Long
    ): Boolean {
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, mainHandler)
        if (!accepted) return false
        return try {
            latch.await(timeoutMs.coerceIn(500L, 15_000L), TimeUnit.MILLISECONDS) && completed.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun api(token: String, body: JSONObject): JSONObject {
        val configuredWorkerId = getSharedPreferences("worker", MODE_PRIVATE)
            .getString("worker_id", "").orEmpty().trim()
        if (configuredWorkerId.isNotEmpty()) body.put("worker_id", configuredWorkerId)
        val connection = URL("https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/psurvival-worker")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Worker API HTTP $code")
            val parsed = JSONObject(raw)
            if (!parsed.optBoolean("ok")) {
                throw IllegalStateException(parsed.optString("error", "Worker API rejected the request"))
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }
}
