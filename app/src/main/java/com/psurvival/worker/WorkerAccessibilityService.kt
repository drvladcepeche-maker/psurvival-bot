package com.psurvival.worker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkerAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_SYNC_RECORDING = "com.psurvival.worker.SYNC_RECORDING"
    }

    private val executor = Executors.newScheduledThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeJobId: String? = null
    @Volatile private var lastHeartbeatAt = 0L
    private val capturedUiSnapshots = mutableListOf<JSONObject>()
    private val recordingLock = Any()

    private var recordingRoot: FrameLayout? = null
    private var recordingBadge: TextView? = null
    private var lastRecordedTouchAt = 0L
    private var overlayTouchDownX = 0f
    private var overlayTouchDownY = 0f
    private var overlayTouchDownAt = 0L
    private var receiverRegistered = false

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SYNC_RECORDING) return
            setOverlayStatus("broadcast_received")
            mainHandler.post { syncRecordingOverlayNow() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        getSharedPreferences("worker", MODE_PRIVATE).edit()
            .putLong("accessibility_service_connected_at", System.currentTimeMillis())
            .putString("record_diag_overlay_status", "service_connected")
            .commit()
        registerRecordingReceiver()
        // If user already pressed Start recording before service was ready, show overlay now.
        mainHandler.post { syncRecordingOverlayNow() }
        executor.scheduleWithFixedDelay({ pollOrHeartbeat() }, 2, 10, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({
            mainHandler.post { syncRecordingOverlayNow() }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun registerRecordingReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_SYNC_RECORDING)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(recordingReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(recordingReceiver, filter)
            }
            receiverRegistered = true
        } catch (error: Throwable) {
            setOverlayStatus("receiver_error: ${(error.message ?: error.javaClass.simpleName).take(80)}")
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        if (!prefs.getBoolean("record_game_actions", false)) return
        val selectedPackage = prefs.getString("selected_game_package", "com.global.ztmslg")
            .orEmpty().ifBlank { "com.global.ztmslg" }
        val activePackage = event.packageName?.toString().orEmpty()
        if (activePackage != selectedPackage && activePackage != "com.global.ztmslg") return

        synchronized(recordingLock) {
            val total = prefs.getLong("record_diag_total", 0L) + 1L
            prefs.edit()
                .putLong("record_diag_total", total)
                .putInt("record_diag_last_type", event.eventType)
                .putString("record_diag_last_package", activePackage)
                .putLong("record_diag_last_at", System.currentTimeMillis())
                .apply()
        }

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
        val textLabel = source.text?.toString()?.trim()?.take(120).orEmpty()
        val descriptionLabel = source.contentDescription?.toString()?.trim()?.take(120).orEmpty()
        val step = when {
            textLabel.isNotEmpty() -> JSONObject().put("type", "tap_text").put("text", textLabel)
            descriptionLabel.isNotEmpty() -> JSONObject().put("type", "tap_description").put("description", descriptionLabel)
            else -> JSONObject().put("type", "tap_relative").put("x", x).put("y", y)
        }
        appendRecordedStep(step, event.eventTime)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(recordingReceiver) }
            receiverRegistered = false
        }
        mainHandler.post { removeRecordingOverlay("service_destroyed") }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun syncRecordingOverlayNow() {
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        val enabled = prefs.getBoolean("record_game_actions", false)
        if (enabled) ensureRecordingOverlay() else removeRecordingOverlay("recording_off")
        updateRecordingBadge()
    }

    private fun ensureRecordingOverlay() {
        if (recordingRoot != null) {
            setOverlayStatus("shown")
            return
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x3300AAFF)
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, event -> handleOverlayTouch(event) }
        }
        val badge = TextView(this).apply {
            text = "REC · 0"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(36, 22, 36, 22)
            setBackgroundColor(0xEEB71C1C.toInt())
        }
        val badgeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 80
        }
        root.addView(badge, badgeParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(root, params)
            recordingRoot = root
            recordingBadge = badge
            setOverlayStatus("shown")
            updateRecordingBadge()
        } catch (error: Throwable) {
            recordingRoot = null
            recordingBadge = null
            setOverlayStatus("error: ${(error.message ?: error.javaClass.simpleName).take(120)}")
        }
    }

    private fun removeRecordingOverlay(reason: String) {
        val root = recordingRoot
        if (root != null) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            runCatching { windowManager.removeView(root) }
            recordingRoot = null
            recordingBadge = null
        }
        setOverlayStatus(reason)
    }

    private fun setOverlayStatus(status: String) {
        getSharedPreferences("worker", MODE_PRIVATE).edit()
            .putString("record_diag_overlay_status", status)
            .putLong("record_diag_overlay_status_at", System.currentTimeMillis())
            .commit()
    }

    private fun handleOverlayTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overlayTouchDownX = event.rawX
                overlayTouchDownY = event.rawY
                overlayTouchDownAt = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val upX = event.rawX
                val upY = event.rawY
                val dt = System.currentTimeMillis() - overlayTouchDownAt
                val moved = kotlin.math.hypot(
                    (upX - overlayTouchDownX).toDouble(),
                    (upY - overlayTouchDownY).toDouble(),
                )
                if (dt <= 1200L && moved <= 64.0) {
                    recordOverlayTap(upX, upY)
                }
                dispatchTap(upX, upY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun recordOverlayTap(rawX: Float, rawY: Float) {
        val now = System.currentTimeMillis()
        if (now - lastRecordedTouchAt < 200L) return
        lastRecordedTouchAt = now

        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        val x = (rawX * 10000f / metrics.widthPixels).toInt().coerceIn(0, 10000)
        val y = (rawY * 10000f / metrics.heightPixels).toInt().coerceIn(0, 10000)
        val step = JSONObject()
            .put("type", "tap_relative")
            .put("x", x)
            .put("y", y)
        appendRecordedStep(step, now)
        mainHandler.post { updateRecordingBadge() }
    }

    private fun appendRecordedStep(step: JSONObject, recordedAt: Long) {
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        if (!prefs.getBoolean("record_game_actions", false)) return
        synchronized(recordingLock) {
            val steps = runCatching {
                JSONArray(prefs.getString("recorded_game_steps", "[]").orEmpty())
            }.getOrDefault(JSONArray())
            if (steps.length() >= 500) return

            if (steps.length() > 0) {
                val last = steps.optJSONObject(steps.length() - 1)
                if (last != null &&
                    last.optString("type") == step.optString("type") &&
                    last.optInt("x", -1) == step.optInt("x", -2) &&
                    last.optInt("y", -1) == step.optInt("y", -2)
                ) {
                    return
                }
            }

            val previous = prefs.getLong("record_last_event_time", 0L)
            val gap = if (previous > 0L && recordedAt > previous) recordedAt - previous else 0L
            if (gap >= 700L) {
                steps.put(JSONObject().put("type", "wait").put("duration_ms", gap.coerceIn(700L, 10_000L)))
            }
            steps.put(step)
            prefs.edit()
                .putString("recorded_game_steps", steps.toString())
                .putLong("record_last_event_time", recordedAt)
                .putLong("record_last_saved_at", System.currentTimeMillis())
                .putInt("record_diag_overlay_taps", prefs.getInt("record_diag_overlay_taps", 0) + 1)
                .commit()
        }
    }

    private fun updateRecordingBadge() {
        val badge = recordingBadge ?: return
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        val steps = runCatching {
            JSONArray(prefs.getString("recorded_game_steps", "[]").orEmpty()).length()
        }.getOrDefault(0)
        badge.text = "REC · $steps"
    }

    private fun dispatchTap(rawX: Float, rawY: Float) {
        if (Build.VERSION.SDK_INT < 24) return
        mainHandler.postDelayed({ tapCoordinates(rawX, rawY) }, 40L)
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
            mainHandler.post { removeRecordingOverlay("job_running") }
            getSharedPreferences("worker", MODE_PRIVATE).edit()
                .putBoolean("record_game_actions", false)
                .commit()

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
                api(
                    token,
                    JSONObject()
                        .put("action", "fail")
                        .put("job_id", jobId)
                        .put("error", (error.message ?: "Executor error").take(500))
                        .put("retryable", error !is IllegalArgumentException),
                )
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
                if (needle.isEmpty() || !tapMatchingNode(needle, false)) {
                    throw IllegalStateException("Не найден кликабельный текст: $needle")
                }
            }
            "tap_description" -> {
                val needle = step.optString("description").trim()
                if (needle.isEmpty() || !tapMatchingNode(needle, true)) {
                    throw IllegalStateException("Не найдено описание: $needle")
                }
            }
            "tap_coordinates" -> {
                val x = step.optInt("x", -1)
                val y = step.optInt("y", -1)
                if (x < 0 || y < 0 || !tapCoordinates(x.toFloat(), y.toFloat())) {
                    throw IllegalStateException("Не удалось нажать координаты")
                }
            }
            "tap_relative", "tap_point" -> {
                val x = step.optInt("x", -1)
                val y = step.optInt("y", -1)
                if (x !in 0..10000 || y !in 0..10000) {
                    throw IllegalArgumentException("x и y должны быть долями экрана 0–10000")
                }
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
                val duration = step.optLong("duration_ms", 500L).coerceIn(100L, 10_000L)
                if (listOf(sx, sy, ex, ey).any { it !in 0..10000 }) {
                    throw IllegalArgumentException("Координаты swipe должны быть 0–10000")
                }
                val path = Path().apply {
                    moveTo(sx * metrics.widthPixels / 10000f, sy * metrics.heightPixels / 10000f)
                    lineTo(ex * metrics.widthPixels / 10000f, ey * metrics.heightPixels / 10000f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                    .build()
                if (!dispatchGestureAndWait(gesture, duration + 2_000L)) {
                    throw IllegalStateException("Не удалось выполнить swipe")
                }
            }
            "launch_app" -> {
                val requestedPackage = step.optString("package_name").trim()
                if (requestedPackage.isEmpty()) throw IllegalArgumentException("package_name обязателен")
                val prefs = getSharedPreferences("worker", MODE_PRIVATE)
                val selectedGamePackage = prefs.getString("selected_game_package", "").orEmpty().trim()
                val packageName = if (
                    selectedGamePackage.isNotEmpty() &&
                    (requestedPackage == "com.global.ztmslg" || requestedPackage == selectedGamePackage)
                ) selectedGamePackage else requestedPackage
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                    ?: throw IllegalStateException("Приложение не найдено: $packageName")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            "capture_ui" -> capturedUiSnapshots.add(captureGameUiSnapshot())
            else -> throw IllegalArgumentException("Неизвестный тип шага: ${step.optString("type")}")
        }
    }

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
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGestureAndWait(gesture, 3_000L)
    }

    private fun dispatchGestureAndWait(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            mainHandler,
        )
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
