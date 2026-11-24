package com.jarvis.beta

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.beta.assistant.AssistantAccessibilityService
import com.jarvis.beta.overlay.PetOverlayService
import com.jarvis.beta.overlay.SnowOverlayService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "jarvis_prefs"
        const val KEY_PREMIUM = "is_premium"
        const val KEY_VOICE = "voice_index"
        const val KEY_PET = "pet_index"
        const val KEY_SNOW = "snow_on"
        const val PREMIUM_CODE = "JARVIS-BETA-2026"
        const val RC_AUDIO = 101

        // 2 бесплатных + 2 под Premium (Beta)
        val PETS = listOf(
            "🤖 Робот Джарвис" to false,
            "👻 Призрак" to false,
            "🐱 Кот Оксид" to true,
            "🐟 Рыба Неон" to true
        )
    }

    private data class VoiceOption(val label: String, val voice: Voice?, val premium: Boolean)

    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var switchSnow: SwitchCompat
    private lateinit var spinnerVoice: Spinner
    private lateinit var spinnerPet: Spinner

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var voiceOptions: List<VoiceOption> = emptyList()
    private var updatingSpinners = false

    private fun isPremium(): Boolean = prefs.getBoolean(KEY_PREMIUM, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        tvStatus = findViewById(R.id.tvStatus)
        switchSnow = findViewById(R.id.switchSnow)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        spinnerPet = findViewById(R.id.spinnerPet)

        findViewById<Button>(R.id.btnMic).setOnClickListener { toggleListening() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnPremium).setOnClickListener { showPremiumDialog() }
        findViewById<Button>(R.id.btnBug).setOnClickListener { sendBugReport() }

        switchSnow.isChecked = prefs.getBoolean(KEY_SNOW, false)
        switchSnow.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(KEY_SNOW, on).apply()
            if (on && !Settings.canDrawOverlays(this)) {
                switchSnow.isChecked = false
                requestOverlayPermission()
                return@setOnCheckedChangeListener
            }
            val i = Intent(this, SnowOverlayService::class.java)
            if (on) {
                runCatching { startService(i) }
                    .onFailure { switchSnow.isChecked = false }
            } else stopService(i)
        }

        spinnerPet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updatingSpinners) return
                if (PETS[pos].second && !isPremium()) {
                    lockedMessage("Этот персонаж доступен в Premium (Beta)")
                    updatingSpinners = true
                    spinnerPet.setSelection(prefs.getInt(KEY_PET, 0))
                    updatingSpinners = false
                    return
                }
                prefs.edit().putInt(KEY_PET, pos).apply()
                startPet(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        initTts()
        initRecognizer()

        if (AssistantAccessibilityService.instance == null) {
            tvStatus.text = "⚠ Включите службу доступности для голосового управления"
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                toast("TTS-движок недоступен на устройстве")
                return@TextToSpeech
            }
            val all = (tts?.voices ?: emptySet()).toList()
                .sortedWith(compareBy({ it.locale.language != "ru" }, { it.name }))
            val picked = all.take(4)
            voiceOptions = if (picked.size >= 2) {
                picked.mapIndexed { i, v ->
                    VoiceOption(
                        label = v.name + if (i >= 2) "  🔒Premium" else "",
                        voice = v,
                        premium = i >= 2
                    )
                }
            } else {
                // Заглушки, если в системе мало голосов
                listOf(
                    VoiceOption("Голос по умолчанию", null, premium = false),
                    VoiceOption("Альтернативный голос", null, premium = false),
                    VoiceOption("Премиум-голос A  🔒Premium", null, premium = true),
                    VoiceOption("Премиум-голос B  🔒Premium", null, premium = true)
                )
            }
            setupVoiceSpinner()
            applyVoice(prefs.getInt(KEY_VOICE, 0))
        }
    }

    private fun setupVoiceSpinner() {
        updatingSpinners = true
        spinnerVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            voiceOptions.map { it.label }
        )
        spinnerVoice.setSelection(prefs.getInt(KEY_VOICE, 0).coerceAtMost(voiceOptions.lastIndex))
        updatingSpinners = false
        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updatingSpinners) return
                val opt = voiceOptions.getOrNull(pos) ?: return
                if (opt.premium && !isPremium()) {
                    lockedMessage("Этот голос доступен в Premium (Beta)")
                    updatingSpinners = true
                    spinnerVoice.setSelection(prefs.getInt(KEY_VOICE, 0))
                    updatingSpinners = false
                    return
                }
                prefs.edit().putInt(KEY_VOICE, pos).apply()
                applyVoice(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun applyVoice(index: Int) {
        val opt = voiceOptions.getOrNull(index) ?: return
        tts?.let { engine ->
            opt.voice?.let { v ->
                runCatching {
                    engine.voice = v
                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f)
                }
            } ?: runCatching {
                engine.setLanguage(if (opt.premium) Locale.US else Locale("ru", "RU"))
            }
        }
    }

    private fun setupPetSpinner() {
        updatingSpinners = true
        spinnerPet.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            PETS.map { it.first }
        )
        spinnerPet.setSelection(prefs.getInt(KEY_PET, 0).coerceAtMost(PETS.lastIndex))
        updatingSpinners = false
    }

    private fun startPet(index: Int) {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        stopService(Intent(this, PetOverlayService::class.java))
        runCatching {
            startService(
                Intent(this, PetOverlayService::class.java)
                    .putExtra(PetOverlayService.EXTRA_TYPE, index)
            )
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Распознавание речи недоступно на устройстве")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { tvStatus.text = "Слушаю…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    tvStatus.text = "Ошибка распознавания (код $error)"
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) {
                        tvStatus.text = "Не расслышал, повторите"
                        return
                    }
                    tvStatus.text = "Вы: $text"
                    val (handled, reply) = CommandExecutor.execute(this@MainActivity, text)
                    if (!handled) tvStatus.text = reply
                    speak(reply)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO
            )
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("ru", "RU"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer?.startListening(i) }
            .onFailure { speak("Распознавание речи недоступно") }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) toggleListening()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utt")
    }

    private fun lockedMessage(msg: String) {
        toast(msg)
        speak(msg)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else toast("Разрешение на оверлей уже выдано")
    }

    private fun showPremiumDialog() {
        val edit = EditText(this).apply { hint = "Введите Beta-код" }
        AlertDialog.Builder(this)
            .setTitle("Premium (Beta)")
            .setView(edit)
            .setPositiveButton("Активировать") { _, _ ->
                if (edit.text.toString().trim().equals(PREMIUM_CODE, ignoreCase = true)) {
                    prefs.edit().putBoolean(KEY_PREMIUM, true).apply()
                    setupVoiceSpinner()
                    setupPetSpinner()
                    toast("Premium активирован — все голоса и персонажи открыты")
                    speak("Премиум статус активирован")
                } else toast("Неверный код")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun sendBugReport() {
        val body = """
            |Опишите ошибку (что делали, что произошло):
            |
            |------------------------
            |Приложение: Jarvis Beta ${packageManager.getPackageInfo(packageName, 0).versionName}
            |Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
            |Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            |Premium: ${isPremium()}
        """.trimMargin()
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:jarvis.beta.dev@gmail.com")
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Bug report — Jarvis Beta")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Нет почтового приложения")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        switchSnow.isChecked = prefs.getBoolean(KEY_SNOW, false)
        setupPetSpinner()
        if (AssistantAccessibilityService.instance != null) {
            tvStatus.text = "Готов к работе"
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
