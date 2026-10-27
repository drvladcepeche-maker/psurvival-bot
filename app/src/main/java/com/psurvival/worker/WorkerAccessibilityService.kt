package com.psurvival.worker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkerAccessibilityService : AccessibilityService() {
    private val executor = Executors.newScheduledThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
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
            val label = (source.text?.toString() ?: source.contentDescription?.toString())
                ?.trim()?.take(80).orEmpty()
            val step = JSONObject()
                .put("type", "tap_relative")
                .put("x", x)
                .put("y", y)
            if (label.isNotEmpty()) step.put("label", label)
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
                    .put("retryable", error !is IllegalArgumentException))
            }
        } finally {
            activeJobId = null
            lastHeartbeatAt = 0L
        }
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
    private fun captureGameUiSnapshot(): JSONObject {
        val root = rootInActiveWindow ?: throw IllegalStateException("Нет активного окна для capture_ui")
        val activePackage = root.packageName?.toString().orEmpty()
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        val selectedPackage = prefs.getString("selected_game_package", "com.global.ztmslg")
            .orEmpty().ifBlank { "com.global.ztmslg" }
        if (activePackage != selectedPackage && activePackage != "com.global.ztmslg") {
            throw IllegalStateException("capture_ui заблокирован вне игры: $activePackage")
        }

        val labels = JSONArray()
        var visited = 0
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
        return JSONObject()
            .put("package_name", activePackage)
            .put("captured_at_ms", System.currentTimeMillis())
            .put("node_count", visited)
            .put("labels", labels)
            .put("note", if (labels.length() == 0) "No accessible labels; Unity-rendered UI may not expose text nodes." else "")
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
