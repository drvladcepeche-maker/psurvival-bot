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
            text = "P&S Worker"
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
        val save = Button(this).apply {
            text = "Сохранить настройки"
            setOnClickListener {
                prefs.edit()
                    .putString("worker_id", workerId.text.toString().trim())
                    .putString("token", token.text.toString().trim())
                    .putString("selected_game_package", selectedGamePackage())
                    .apply()
                status.text = "Настройки сохранены. Теперь включи службу доступности ниже."
            }
        }
        root.addView(save, matchWidth())

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
                    prefs.edit().putString("selected_game_package", target.packageName).apply()
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
                status.text = "Найдено подходящих вариантов: ${gameTargets.size}. Если клон не появился, Android может изолировать его в отдельном профиле."
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Проверить, установлен ли выбранный экземпляр"
            setOnClickListener { status.text = checkGameInstalled() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Открыть выбранную игру — вход через Google"
            setOnClickListener {
                val result = launchGame()
                status.text = result ?: "Игра открыта. На её экране выбери штатный вход через Google и подтверди нужный аккаунт."
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Открыть выбранную игру — вход по email"
            setOnClickListener {
                val result = launchGame()
                status.text = result ?: "Игра открыта. На её экране выбери вход по email и введи данные непосредственно в игре."
            }
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "Запись действий в сценарий"
            textSize = 20f
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "Запись сохраняет нажатия, которые Android передаёт службе доступности, в координаты относительно экрана. Запускай запись, перейди в игру и выполняй нужные действия вручную, затем останови запись и скопируй JSON. Пароли и поля ввода не записываются. Если игра рисует интерфейс Unity без доступных узлов, Android может не сообщать о нажатиях — тогда сценарий останется пустым."
            textSize = 14f
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Начать новую запись"
            setOnClickListener {
                prefs.edit()
                    .putBoolean("record_game_actions", true)
                    .putString("recorded_game_steps", "[]")
                    .remove("record_last_event_time")
                    .apply()
                status.text = "Запись включена. Перейди в выбранную игру и выполни нужные действия."
                refreshRecordedSteps()
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Остановить запись"
            setOnClickListener {
                prefs.edit().putBoolean("record_game_actions", false).remove("record_last_event_time").apply()
                status.text = "Запись остановлена."
                refreshRecordedSteps()
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
                status.text = "JSON сценария скопирован. Его можно передать боту как daily_steps после проверки."
                refreshRecordedSteps()
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
                "Записанных шагов пока нет. Учти: не все игровые интерфейсы передают нажатия в AccessibilityService."
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
        // Keep the local function available to the buttons created above through a property callback.
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
            text = "Исполнитель запускается только после включения службы доступности."
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
            "Выбранный экземпляр не обнаружен: $packageName. Обнови список и проверь, виден ли клон Android."
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
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
