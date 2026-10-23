package com.v2ray.ang.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.handler.AngConfigManager
import kotlinx.coroutines.launch

class TelegramSettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("tg_import", Context.MODE_PRIVATE) }
    private lateinit var status: TextView

    private fun field(hint: String, key: String, type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1) =
        EditText(this).apply {
            this.hint = hint
            inputType = type or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            minLines = lines
            setText(prefs.getString(key, ""))
        }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { onClick() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Telegram Import"

        val apiId = field("API ID", "api_id", InputType.TYPE_CLASS_NUMBER)
        val apiHash = field("API Hash", "api_hash")
        val phone = field("شماره با کد کشور (+98...)", "phone", InputType.TYPE_CLASS_PHONE)
        val code = field("کد تلگرام", "")
        val password = field(
            "رمز دو مرحله‌ای (اگه داری)", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val channels = field("چنل‌ها، هر خط یکی (@channel)", "channels", lines = 4)
        status = TextView(this).apply { setPadding(0, 24, 0, 24) }

        fun save() = prefs.edit()
            .putString("api_id", apiId.text.toString().trim())
            .putString("api_hash", apiHash.text.toString().trim())
            .putString("phone", phone.text.toString().trim())
            .putString("channels", channels.text.toString())
            .apply()

        fun startTd(): Boolean {
            val id = apiId.text.toString().trim().toIntOrNull()
            val hash = apiHash.text.toString().trim()
            if (id == null || hash.isEmpty()) {
                say("API ID و API Hash رو پر کن")
                return false
            }
            save()
            TelegramManager.start(this, id, hash)
            return true
        }

        val sendBtn = button("۱) ارسال کد به تلگرام") {
            if (startTd()) lifecycleScope.launch {
                say("در حال ارسال...")
                try {
                    TelegramManager.sendPhone(phone.text.toString())
                    say(
                        if (TelegramManager.state.value == "ready") "قبلاً وصل شدی"
                        else "کد ارسال شد؛ داخل تلگرام ببین"
                    )
                } catch (e: Exception) {
                    say("خطا: ${e.message}")
                }
            }
        }

        val loginBtn = button("۲) ورود") {
            lifecycleScope.launch {
                say("در حال ورود...")
                try {
                    when (TelegramManager.state.value) {
                        "password" -> {
                            if (password.text.isEmpty()) {
                                say("رمز دو مرحله‌ای رو بنویس و دوباره ورود بزن")
                                return@launch
                            }
                            TelegramManager.sendPassword(password.text.toString())
                        }
                        "ready" -> {
                            say("قبلاً وارد شدی ✅")
                            return@launch
                        }
                        else -> {
                            val next = TelegramManager.sendCode(code.text.toString())
                            if (next == "password") {
                                if (password.text.isEmpty()) {
                                    say("کد درست بود؛ رمز دو مرحله‌ای رو بنویس و دوباره ورود بزن")
                                    return@launch
                                }
                                TelegramManager.sendPassword(password.text.toString())
                            }
                        }
                    }
                    say("وصل شدی ✅")
                } catch (e: Exception) {
                    say("خطا: ${e.message}")
                }
            }
        }

        val importBtn = button("۳) ایمپورت کانفیگ‌های امروز") {
            save()
            val list = channels.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) {
                say("حداقل یه چنل اضافه کن")
                return@button
            }
            lifecycleScope.launch {
                val all = LinkedHashSet<String>()
                for (ch in list) {
                    say("در حال خوندن $ch ...")
                    try {
                        all.addAll(TelegramManager.fetchToday(ch))
                    } catch (e: Exception) {
                        say("خطا در $ch: ${e.message}")
                    }
                }
                if (all.isEmpty()) {
                    say("کانفیگ امروزی پیدا نشد")
                    return@launch
                }
                val result = AngConfigManager.importBatchConfig(all.joinToString("\n"), "", true)
                say("${result.first} کانفیگ ایمپورت شد ✅ برگرد صفحه اصلی و پینگ بگیر")
                Toast.makeText(
                    this@TelegramSettingsActivity,
                    "Imported ${result.first}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            listOf<View>(
                apiId, apiHash, phone, sendBtn, code, password, loginBtn,
                channels, importBtn, status
            ).forEach { addView(it) }
        }
        setContentView(ScrollView(this).apply { addView(col) })

        val id = prefs.getString("api_id", "")?.toIntOrNull()
        val hash = prefs.getString("api_hash", "") ?: ""
        if (id != null && hash.isNotEmpty()) {
            TelegramManager.start(this, id, hash)
            lifecycleScope.launch {
                TelegramManager.state.collect {
                    if (it == "ready") say("وصل شدی ✅")
                }
            }
        }
    }

    private fun say(t: String) {
        status.text = t
    }
}
