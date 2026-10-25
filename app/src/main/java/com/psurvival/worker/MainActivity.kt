package com.psurvival.worker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var workerId: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView

    private val gamePackage = "com.global.ztmslg"

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
                    .apply()
                status.text = "Настройки сохранены. Теперь включи службу доступности ниже."
            }
        }
        root.addView(save, matchWidth())

        root.addView(TextView(this).apply {
            text = "Игровой аккаунт"
            textSize = 20f
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "Вход выполняется в самой игре. Worker не запрашивает и не сохраняет пароль Google или игровой пароль."
            textSize = 14f
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Проверить, установлена ли игра"
            setOnClickListener { status.text = checkGameInstalled() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Открыть игру — вход через Google"
            setOnClickListener {
                val result = launchGame()
                status.text = if (result == null) {
                    "Игра открыта. На её экране выбери штатный вход через Google и подтверди нужный аккаунт."
                } else result
            }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Открыть игру — вход по email"
            setOnClickListener {
                val result = launchGame()
                status.text = if (result == null) {
                    "Игра открыта. На её экране выбери вход по email и введи данные непосредственно в игре."
                } else result
            }
        }, matchWidth())

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
        setContentView(root)
    }

    private fun checkGameInstalled(): String {
        return try {
            packageManager.getPackageInfo(gamePackage, 0)
            "Игра обнаружена: $gamePackage"
        } catch (_: PackageManager.NameNotFoundException) {
            "Игра не обнаружена. Установи нужную версию Puzzles & Survival или проверь идентификатор пакета."
        } catch (error: Exception) {
            "Не удалось проверить игру: ${error.message}"
        }
    }

    private fun launchGame(): String? {
        val launch = packageManager.getLaunchIntentForPackage(gamePackage)
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
