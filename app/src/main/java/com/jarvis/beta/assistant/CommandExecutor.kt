package com.jarvis.beta

import android.content.Context
import android.content.Intent
import com.jarvis.beta.assistant.AssistantAccessibilityService
import com.jarvis.beta.overlay.SnowOverlayService

/**
 * Парсер голосовых команд. Возвращает Pair(обработано, фраза для озвучки).
 */
object CommandExecutor {

    private data class AppTarget(val keys: List<String>, val pkg: String?, val label: String)

    private val apps = listOf(
        AppTarget(listOf("телеграм", "telegram"), "org.telegram.messenger", "телеграм"),
        AppTarget(listOf("ватсап", "whatsapp"), "com.whatsapp", "whatsapp"),
        AppTarget(listOf("ютуб", "ютюб", "youtube"), "com.google.android.youtube", "youtube"),
        AppTarget(listOf("настройки"), null, "настройки"),
        AppTarget(listOf("калькулятор"), "com.google.android.calculator", "калькулятор"),
        AppTarget(listOf("камер"), "com.google.android.GoogleCamera", "камера"),
        AppTarget(listOf("карты", "гугл карты"), "com.google.android.apps.maps", "карты"),
        AppTarget(listOf("браузер", "хром", "chrome"), "com.android.chrome", "браузер"),
        AppTarget(listOf("будильник", "часы"), "com.google.android.deskclock", "часы")
    )

    fun execute(ctx: Context, raw: String): Pair<Boolean, String> {
        val s = raw.lowercase().replace("ё", "е")

        // Снег — работает и без службы доступности
        if (s.contains("снег")) {
            val prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            val on = !prefs.getBoolean(MainActivity.KEY_SNOW, false)
            prefs.edit().putBoolean(MainActivity.KEY_SNOW, on).apply()
            val i = Intent(ctx, SnowOverlayService::class.java)
            runCatching { if (on) ctx.startService(i) else ctx.stopService(i) }
            return true to if (on) "Включаю снег" else "Выключаю снег"
        }

        val svc = AssistantAccessibilityService.instance
            ?: return false to "Служба доступности не активна. Включите её в настройках приложения."

        // Звонки
        if (s.contains("принять") || (s.contains("ответь") && s.contains("звонок"))) {
            svc.answerCall()
            return true to "Принимаю звонок"
        }
        if (s.contains("сброс") || s.contains("отклон") || s.contains("отбой")) {
            svc.rejectCall()
            return true to "Сбрасываю звонок"
        }

        // Шторка быстрых настроек / Wi-Fi / Bluetooth
        if (s.contains("шторк") || s.contains("быстрых настроек")
            || s.contains("быстрые настройки")
            || s.contains("wi-fi") || s.contains("wifi") || s.contains("вай-фай")
            || s.contains("bluetooth") || s.contains("блютуз")
        ) {
            svc.openQuickSettings()
            return true to "Открываю шторку быстрых настроек. Выберите нужный переключатель."
        }

        // Главный экран / недавние
        if (s.contains("домой") || s.contains("главный")
            || s.contains("закрой приложение") || s.contains("сверни") || s.contains("назад")
        ) {
            svc.goHome()
            return true to "Возвращаюсь на главный экран"
        }
        if (s.contains("недавн") || s.contains("задачи") || s.contains("запущенные")) {
            svc.openRecents()
            return true to "Открываю список недавних приложений"
        }

        // Открыть приложение
        val openVerb = s.contains("открой") || s.contains("запусти") || s.contains("включи")
        if (openVerb) {
            for (app in apps) {
                if (app.keys.any { s.contains(it) }) {
                    val ok = svc.openApp(app.pkg, app.label)
                    return if (ok) true to "Открываю ${app.keys.first()}"
                    else false to "Не удалось найти приложение ${app.keys.first()}"
                }
            }
        }

        return false to "Команда не распознана: «$raw». Попробуйте: «Открой Телеграм», " +
                "«Домой», «Шторка», «Принять звонок», «Сбросить», «Снег»."
    }
}
