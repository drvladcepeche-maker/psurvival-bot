package com.psurvival.worker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

private data class GameTarget(val label: String, val packageName: String) {
    override fun toString(): String = "$label  ·  $packageName"
}

class MainActivity : Activity() {
    private lateinit var workerId: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var gameTargetSpinner: Spinner
    private lateinit var gameAdapter: ArrayAdapter<GameTarget>
    private var gameTargets: List<GameTarget> = emptyList()

    private val defaultGamePackage = "com.global.ztmslg"
    private var recordedStepsRefresh: (() -> Unit)? = null

    private fun refreshRecordedSteps() { recordedStepsRefresh?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("worker", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }
        root.addView(TextView(this).apply {
            text = "P&S Worker 0.2.1"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Вставь Worker ID и токен из личного чата Telegram. Токен хранится локально в настройках приложения."
            textSize = 14f
        })
        workerId = EditText(this).apply {
            hint = "Worker ID"
            setSingleLine(true)
            setText(prefs.getString("worker_id", ""))
        }
        root.addView(workerId, matchWidth())
        token = EditText(this).apply {
            hint = "Worker token"
            setSingleLine(true)
            setText(prefs.getString("token", ""))
        }
        root.addView(token, matchWidth())
        root.addView(Button(this).apply {
            text = "Сохранить настройки"
            setOnClickListener {
                prefs.edit()
                    .putString("worker_id", workerId.text.toString().trim())
                    .putString("token", token.text.toString().trim())
                    .putString("selected_game_package", selectedGamePackage())
                    .commit()
                status.text = "Настройки сохранены. Теперь включи службу доступности ниже."
            }
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "Экземпляр игры"
            textSize = 20f
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "Выбери оригинал или клон. Выбор сохраняется и используется также для удалённых заданий запуска игры."
            textSize = 14f
        }, matchWidth())

        gameTargetSpinner = Spinner(this)
        gameTargets = discoverGameTargets()
        gameAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, gameTargets).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        gameTargetSpinner.adapter = gameAdapter
        val savedTarget = prefs.getString("selected_game_package", defaultGamePackage).orEmpty()
        val selectedIndex = gameTargets.indexOfFirst { it.packageName == savedTarget }
            .takeIf { it >= 0 }
            ?: gameTargets.indexOfFirst { it.packageName == defaultGamePackage }.coerceAtLeast(0)
        gameTargetSpinner.setSelection(selectedIndex)
        root.addView(gameTargetSpinner, matchWidth())
        gameTargetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                gameTargets.getOrNull(position)?.let { target ->
                    prefs.edit().putString("selected_game_package", target.packageName).commit()
                    status.text = "Выбран экземпляр игры: ${target.label} (${target.packageName})"
                }
            }
        }
        root.addView(Button(this).apply {
            text = "Обновить список оригинала и клона"
            setOnClickListener {
                val previous = selectedGamePackage()
                gameTargets = discoverGameTargets()
                gameAdapter.clear()
                gameAdapter.addAll(gameTargets)
                val index = gameTargets.indexOfFirst { it.packageName == previous }
                    .takeIf { it >= 0 }
                    ?: gameTargets.indexOfFirst { it.packageName == defaultGamePackage }.coerceAtLeast(0)
                gameTargetSpinner.setSelection(index)
                status.text = "Найдено подходящих вариантов: ${gameTargets.size}"
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Проверить, установлен ли выбранный экземпляр"
            setOnClickListener { status.text = checkGameInstalled() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Открыть выбранную игру"
            setOnClickListener {
                val result = launchGame()
                status.text = result ?: "Игра открыта."
            }
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "Запись действий в сценарий"
            textSize = 20f
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "Версия 0.2.1: при записи должен появиться голубоватый оверлей и красный REC сверху. Если REC нет — служба доступности не перезапущена после установки APK."
            textSize = 14f
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Начать новую запись"
            setOnClickListener {
                prefs.edit()
                    .putBoolean("record_game_actions", true)
                    .putString("recorded_game_steps", "[]")
                    .putLong("record_diag_total", 0L)
                    .putInt("record_diag_last_type", 0)
                    .putInt("record_diag_overlay_taps", 0)
                    .putString("record_diag_last_package", "")
                    .putString("record_diag_overlay_status", "waiting_for_service")
                    .putLong("record_diag_last_at", 0L)
                    .putLong("recording_started_at", System.currentTimeMillis())
                    .remove("record_last_event_time")
                    .commit()
                status.text = "Запись включена. В течение 1–2 сек должен появиться REC. Если нет — выключи/включи службу доступности и Force Stop приложения."
                refreshRecordedSteps()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Остановить запись"
            setOnClickListener {
                prefs.edit()
                    .putBoolean("record_game_actions", false)
                    .remove("record_last_event_time")
                    .commit()
                status.text = "Запись остановлена."
                refreshRecordedSteps()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Диагностика записи"
            setOnClickListener {
                val connectedAt = prefs.getLong("accessibility_service_connected_at", 0L)
                val startedAt = prefs.getLong("recording_started_at", 0L)
                val eventTotal = prefs.getLong("record_diag_total", 0L)
                val overlayTaps = prefs.getInt("record_diag_overlay_taps", 0)
                val overlayStatus = prefs.getString("record_diag_overlay_status", "нет").orEmpty()
                val recordingOn = prefs.getBoolean("record_game_actions", false)
                val lastType = prefs.getInt("record_diag_last_type", 0)
                val lastPackage = prefs.getString("record_diag_last_package", "").orEmpty()
                val lastAt = prefs.getLong("record_diag_last_at", 0L)
                val eventName = when (lastType) {
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
                    else -> if (lastType == 0) "нет" else "тип=$lastType"
                }
                status.text = "Версия UI: 0.2.1\n" +
                    "Запись сейчас: " + (if (recordingOn) "ВКЛ" else "ВЫКЛ") +
                    "\nСтатус оверлея: $overlayStatus" +
                    "\nСлужба подключалась: " +
                    (if (connectedAt > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(connectedAt)) else "нет") +
                    "\nНачало записи: " +
                    (if (startedAt > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(startedAt)) else "нет") +
                    "\nСобытий accessibility: $eventTotal" +
                    "\nТапов через оверлей: $overlayTaps" +
                    "\nПоследнее событие: $eventName" +
                    "\nПакет: " + (lastPackage.ifBlank { "нет" }) +
                    "\nВремя последнего события: " +
                    (if (lastAt > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastAt)) else "нет")
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Обновить JSON сценария"
            setOnClickListener { refreshRecordedSteps() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Скопировать JSON сценария"
            setOnClickListener {
                val json = prefs.getString("recorded_game_steps", "[]").orEmpty()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("P&S Worker recorded scenario", json))
                status.text = "JSON сценария скопирован."
                refreshRecordedSteps()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Скопировать команду для Telegram"
            setOnClickListener {
                val json = prefs.getString("recorded_game_steps", "[]").orEmpty()
                val steps = runCatching { org.json.JSONArray(json) }.getOrNull()
                if (steps == null || steps.length() == 0) {
                    status.text = "Сценарий пуст: сначала запиши игровые действия."
                } else {
                    val command = "/set daily_steps $json"
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("P&S Worker Telegram command", command))
                    status.text = "Команда /set daily_steps скопирована."
                }
            }
        }, matchWidth())
        val recordingOutput = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "Записанных шагов пока нет."
        }
        root.addView(recordingOutput, matchWidth())
        fun updateRecordingOutput() {
            val raw = prefs.getString("recorded_game_steps", "[]").orEmpty()
            val steps = runCatching { org.json.JSONArray(raw) }.getOrNull()
            recordingOutput.text = if (steps == null || steps.length() == 0) {
                "Записанных шагов пока нет. Нужен красный REC сверху и лёгкий голубой оверлей."
            } else {
                "Записано шагов: ${steps.length()}\n" + runCatching {
                    org.json.JSONArray(raw).let { arr ->
                        val pretty = org.json.JSONArray()
                        for (i in 0 until arr.length()) pretty.put(arr.getJSONObject(i))
                        pretty.toString(2)
                    }
                }.getOrDefault(raw)
            }
        }
        recordedStepsRefresh = { updateRecordingOutput() }
        updateRecordingOutput()

        root.addView(Button(this).apply {
            text = "Открыть настройки службы доступности"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Проверить доступность API"
            setOnClickListener {
                Thread {
                    val result = runCatching {
                        val connection = URL("https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/psurvival-worker")
                            .openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        val code = connection.responseCode
                        connection.disconnect()
                        "API ответил HTTP $code"
                    }.getOrElse { "Ошибка API: ${it.message}" }
                    runOnUiThread { status.text = result }
                }.start()
            }
        }, matchWidth())
        status = TextView(this).apply {
            text = "После установки нового APK обязательно: выключи службу доступности → Force Stop приложения → включи службу снова."
            textSize = 14f
        }
        root.addView(status, matchWidth())
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scrollView)
    }

    private fun discoverGameTargets(): List<GameTarget> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                val label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty()
                val normalized = label.lowercase(java.util.Locale.ROOT)
                val isGame = packageName == defaultGamePackage ||
                    packageName.contains("ztmslg", ignoreCase = true) ||
                    (normalized.contains("puzzle") && normalized.contains("survival")) ||
                    (normalized.contains("пазл") && normalized.contains("выжив"))
                if (isGame) GameTarget(label.ifBlank { packageName }, packageName) else null
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<GameTarget> { it.packageName != defaultGamePackage }.thenBy { it.label })
            .ifEmpty { listOf(GameTarget("Puzzles & Survival (пакет по умолчанию)", defaultGamePackage)) }
    }

    private fun selectedGamePackage(): String {
        return gameTargets.getOrNull(gameTargetSpinner.selectedItemPosition)?.packageName
            ?: getSharedPreferences("worker", MODE_PRIVATE)
                .getString("selected_game_package", defaultGamePackage).orEmpty()
    }

    private fun checkGameInstalled(): String {
        val packageName = selectedGamePackage()
        return try {
            packageManager.getPackageInfo(packageName, 0)
            "Выбранный экземпляр обнаружен: $packageName"
        } catch (_: PackageManager.NameNotFoundException) {
            "Выбранный экземпляр не обнаружен: $packageName"
        } catch (error: Exception) {
            "Не удалось проверить игру: ${error.message}"
        }
    }

    private fun launchGame(): String? {
        val packageName = selectedGamePackage()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: return checkGameInstalled()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launch)
            null
        } catch (error: Exception) {
            "Не удалось открыть игру: ${error.message}"
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
