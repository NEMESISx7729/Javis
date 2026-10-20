package com.myra.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var commandInput: EditText
    private val VOICE_REQ_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val prefs = getSharedPreferences("MyraPrefs", Context.MODE_PRIVATE)

        // High-contrast solid black background UI
        val rootLayout = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 80)
        }

        val title = TextView(this).apply {
            text = "MYRA AI"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Android 11 Voice & Screen Automation"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 48)
        }

        // Gemini API Key Card
        apiKeyInput = EditText(this).apply {
            hint = "Paste your Gemini API Key here"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("gemini_key", ""))
            background = createCardDrawable("#141414", "#333333")
            setPadding(36, 32, 36, 32)
        }

        val saveKeyBtn = createStyledButton("Save Gemini API Key", "#1E1E1E", "#FFFFFF").apply {
            setOnClickListener {
                prefs.edit().putString("gemini_key", apiKeyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "API Key Saved!", Toast.LENGTH_SHORT).show()
            }
        }

        val accessibilityBtn = createStyledButton("1. Enable Accessibility Automation", "#141414", "#00E676").apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val voiceBtn = createStyledButton("🎙️ Tap & Speak to Myra", "#FFFFFF", "#000000").apply {
            setOnClickListener { startVoiceInput() }
        }

        commandInput = EditText(this).apply {
            hint = "Or type a command (e.g., Open YouTube)"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            background = createCardDrawable("#141414", "#333333")
            setPadding(36, 32, 36, 32)
        }

        val sendBtn = createStyledButton("Run Text Command", "#1E1E1E", "#FFFFFF").apply {
            setOnClickListener {
                val cmd = commandInput.text.toString().trim()
                if (cmd.isNotEmpty()) processUserCommand(cmd)
            }
        }

        statusText = TextView(this).apply {
            text = "Status: Ready. Paste your API key and tap Speak."
            textSize = 15f
            setTextColor(Color.WHITE)
            background = createCardDrawable("#0D0D0D", "#262626")
            setPadding(40, 40, 40, 40)
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(apiKeyInput)
        container.addView(addSpace(20))
        container.addView(saveKeyBtn)
        container.addView(addSpace(32))
        container.addView(accessibilityBtn)
        container.addView(addSpace(32))
        container.addView(voiceBtn)
        container.addView(addSpace(32))
        container.addView(commandInput)
        container.addView(addSpace(20))
        container.addView(sendBtn)
        container.addView(addSpace(40))
        container.addView(statusText)

        rootLayout.addView(container)
        setContentView(rootLayout)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening, Boss...")
        }
        try {
            startActivityForResult(intent, VOICE_REQ_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQ_CODE && resultCode == RESULT_OK && data != null) {
            val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                commandInput.setText(spokenText)
                processUserCommand(spokenText)
            }
        }
    }

    private fun processUserCommand(command: String) {
        val apiKey = apiKeyInput.text.toString().trim()
        if (apiKey.isEmpty()) {
            updateStatus("⚠️ Please paste your Gemini API Key first!")
            return
        }

        updateStatus("You: \"$command\"\n\nThinking...")

        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                val systemPrompt = """
                    You are Myra, a smart Android voice assistant. 
                    Respond ONLY in raw JSON format with these exact 3 keys:
                    {
                      "reply": "Short spoken response to the user (match their language, e.g. English or Hinglish)",
                      "action": "NONE or OPEN_APP or CLICK_TEXT or HOME or BACK or NOTIFICATIONS",
                      "target": "Name of the app to open or button text to click, else empty string"
                    }
                """.trimIndent()

                val requestJson = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", command)))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                OutputStreamWriter(conn.outputStream).use { it.write(requestJson.toString()) }

                val responseCode = conn.responseCode
                val responseBody = if (responseCode == 200) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP Error $responseCode"
                }

                if (responseCode != 200) {
                    runOnUiThread { updateStatus("API Error:\n$responseBody") }
                    return@thread
                }

                val rootObj = JSONObject(responseBody)
                val modelText = rootObj
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val parsedAction = JSONObject(modelText)
                val reply = parsedAction.optString("reply", "Done.")
                val action = parsedAction.optString("action", "NONE")
                val target = parsedAction.optString("target", "")

                runOnUiThread {
                    updateStatus("You: \"$command\"\n\nMyra: \"$reply\"\n\nAction: $action ($target)")
                    speakOut(reply)
                    executeAutomation(action, target)
                }

            } catch (e: Exception) {
                runOnUiThread { updateStatus("Error: ${e.message}") }
            }
        }
    }

    private fun executeAutomation(action: String, target: String) {
        when (action.uppercase()) {
            "OPEN_APP" -> openAppByName(target)
            "CLICK_TEXT" -> {
                val svc = AutomationService.instance
                if (svc != null) {
                    svc.clickTextOnScreen(target)
                } else {
                    Toast.makeText(this, "Enable Accessibility Service first!", Toast.LENGTH_LONG).show()
                }
            }
            "HOME", "BACK", "NOTIFICATIONS" -> {
                AutomationService.instance?.triggerGlobalAction(action)
            }
        }
    }

    private fun openAppByName(appName: String) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.contains(appName, ignoreCase = true)) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
            }
        }
        Toast.makeText(this, "Could not find installed app: $appName", Toast.LENGTH_SHORT).show()
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MyraTTS")
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }

    private fun createCardDrawable(bgColor: String, strokeColor: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColor))
            cornerRadius = 28f
            setStroke(3, Color.parseColor(strokeColor))
        }
    }

    private fun createStyledButton(label: String, bgHex: String, textHex: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.parseColor(textHex))
            background = createCardDrawable(bgHex, "#333333")
            setPadding(36, 36, 36, 36)
        }
    }

    private fun addSpace(heightPx: Int): Space {
        return Space(this).apply { minimumHeight = heightPx }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
