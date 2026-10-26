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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkerAccessibilityService : AccessibilityService() {
    private val executor = Executors.newScheduledThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeJobId: String? = null
    @Volatile private var lastHeartbeatAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        executor.scheduleWithFixedDelay({ pollOrHeartbeat() }, 2, 10, TimeUnit.SECONDS)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
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
            val payload = job.optJSONObject("payload") ?: JSONObject()
            val steps = payload.optJSONArray("steps") ?: JSONArray()
            if (steps.length() == 0) throw IllegalArgumentException("Задание не содержит steps; шаблон действий не настроен")
            val result = JSONObject().put("executed_steps", steps.length()).put("task_type", job.optString("task_type"))
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: throw IllegalArgumentException("Некорректный шаг #$i")
                executeStep(step)
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
            "launch_app" -> {
                val packageName = step.optString("package_name").trim()
                if (packageName.isEmpty()) throw IllegalArgumentException("package_name обязателен")
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                    ?: throw IllegalStateException("Приложение не найдено: $packageName")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            "capture_ui" -> {
                // A no-op marker for a future structured UI snapshot step; do not collect account data.
            }
            else -> throw IllegalArgumentException("Неизвестный тип шага: ${step.optString("type")}")
        }
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
        return dispatchGesture(gesture, null, null)
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
