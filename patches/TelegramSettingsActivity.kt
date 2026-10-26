package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.handler.AngConfigManager
import kotlinx.coroutines.launch

class TelegramSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("tg_import", Context.MODE_PRIVATE) }
    private val channels = mutableListOf<String>()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var channelList: LinearLayout

    private val apiId by lazy { field("API ID", "api_id", InputType.TYPE_CLASS_NUMBER) }
    private val apiHash by lazy { field("API Hash", "api_hash") }
    private val phone by lazy { field("شماره با کد کشور (+98...)", "phone", InputType.TYPE_CLASS_PHONE) }
    private val code by lazy { field("کد تلگرام", "") }
    private val password by lazy { field("رمز دو مرحله‌ای (اگه داری)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) }
    private val workerUrl by lazy { field("آدرس ورکر (https://xxx.workers.dev/?key=...)", "worker_url") }
    private val newChannel by lazy { field("@channel یا لینک t.me", "") }

    private fun field(hint: String, key: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply { this.hint = hint; inputType = type; setText(if (key.isEmpty()) "" else prefs.getString(key, "")) }
    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { onClick() } }
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, 48, 0, 12)
    }
    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 8, 0, 8) }

    private val sendBtn by lazy {
        button("۱) ارسال کد به تلگرام") {
            if (startTd()) lifecycleScope.launch {
                say("در حال ارسال...")
                try {
                    TelegramManager.sendPhone(phone.text.toString())
                    say(if (TelegramManager.state.value == "ready") "قبلاً وصل شدی" else "کد ارسال شد؛ داخل تلگرام ببین")
                } catch (e: Exception) { say("خطا: ${e.message}") }
            }
        }
    }
    private val loginBtn by lazy {
        button("۲) ورود") {
            lifecycleScope.launch {
                say("در حال ورود...")
                try {
                    val s = TelegramManager.sendCode(code.text.toString())
                    if (s == "password") {
                        if (password.text.isEmpty()) { say("رمز دو مرحله‌ای رو بنویس و دوباره ورود بزن"); return@launch }
                        TelegramManager.sendPassword(password.text.toString())
                    }
                    say("وصل شدی ✅")
                } catch (e: Exception) { say("خطا: ${e.message}") }
            }
        }
    }
    private fun startTd(): Boolean {
        val id = apiId.text.toString().trim().toIntOrNull()
        val hash = apiHash.text.toString().trim()
        if (id == null || hash.isEmpty()) { say("API ID و API Hash رو پر کن"); return false }
        savePrefs()
        TelegramManager.start(this, id, hash)
        return true
    }
    private fun normalize(raw: String): String {
        var s = raw.trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("t.me/s/").removePrefix("t.me/").removePrefix("@")
        return s.substringBefore('/').substringBefore('?')
    }
    private fun addChannel() {
        val ch = normalize(newChannel.text.toString())
        if (ch.isEmpty() || channels.any { it.equals(ch, true) }) { newChannel.setText(""); return }
        channels.add(ch); newChannel.setText(""); savePrefs(); refreshChannels()
    }
    private fun refreshChannels() {
        channelList.removeAllViews()
        if (channels.isEmpty()) channelList.addView(label("هنوز چنلی اضافه نکردی"))
        channels.toList().forEach { ch ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = "@$ch"; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(button("حذف") { channels.remove(ch); savePrefs(); refreshChannels() })
            channelList.addView(row)
        }
    }
    private val importBtn by lazy {
        button("ایمپورت کانفیگ‌های امروز") {
            savePrefs()
            if (channels.isEmpty()) { say("اول یه چنل اضافه کن"); return@button }
            val worker = workerUrl.text.toString().trim()
            if (worker.isEmpty() && TelegramManager.state.value != "ready") {
                say("یا لاگین کن یا آدرس ورکر بذار"); return@button
            }
            lifecycleScope.launch {
                val all = LinkedHashSet<String>()
                for (ch in channels.toList()) {
                    say("در حال خوندن @$ch ...")
                    try {
                        val list = get(worker, ch)
                        all.addAll(list); say("@$ch: ${list.size} کانفیگ")
                    } catch (e: Exception) { say("خطا در @$ch: ${e.message}") }
                }
                if (all.isEmpty()) { say("کانفیگ امروزی پیدا نشد"); return@launch }
                try {
                    importIntoV2ray(all.joinToString("\n"))
                    say("${all.size} کانفیگ ایمپورت شد ✅ برگرد صفحه اصلی v2rayNG و پینگ بگیر")
                } catch (e: Exception) { say("خطا در ایمپورت: ${e.message}") }
            }
        }
    }
    private suspend fun get(worker: String, ch: String): List<String> {
        if (worker.isNotEmpty()) {
            try { return TelegramManager.fetchViaWorker(worker, ch) }
            catch (e: Exception) { if (TelegramManager.state.value != "ready") throw e }
        }
        return TelegramManager.fetchToday(ch)
    }
    private fun render() {
        root.removeAllViews()
        val st = TelegramManager.state.value
        val hasCreds = !prefs.getString("api_id", "").isNullOrEmpty() && !prefs.getString("api_hash", "").isNullOrEmpty()
        root.addView(title("حساب تلگرام"))
        when {
            st == "ready" -> {
                root.addView(label("وصل شده ✅"))
                root.addView(button("خروج از حساب") {
                    lifecycleScope.launch {
                        TelegramManager.logout()
                        prefs.edit().remove("api_id").remove("api_hash").remove("phone").apply()
                        apiId.setText(""); apiHash.setText(""); phone.setText(""); render()
                    }
                })
            }
            st == "init" && hasCreds -> root.addView(label("در حال اتصال..."))
            else -> listOf<View>(apiId, apiHash, phone, sendBtn, code, password, loginBtn).forEach { root.addView(it) }
        }
        root.addView(title("ورکر کلادفلر (کار بدون VPN، فقط چنل‌های عمومی)"))
        root.addView(workerUrl)
        root.addView(title("چنل‌ها")); root.addView(newChannel)
        root.addView(button("افزودن چنل") { addChannel() }); root.addView(channelList)
        root.addView(title("ایمپورت")); root.addView(importBtn); root.addView(status)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Telegram Import"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }
        status = TextView(this).apply { setPadding(0, 24, 0, 24) }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(root) })
        channels.addAll(prefs.getString("channels_v2", "")!!.lines().filter { it.isNotBlank() })
        refreshChannels()
        val id = prefs.getString("api_id", "")?.toIntOrNull()
        val hash = prefs.getString("api_hash", "") ?: ""
        if (id != null && hash.isNotEmpty()) TelegramManager.start(this, id, hash)
        lifecycleScope.launch { TelegramManager.state.collect { render() } }
    }
    override fun onPause() { super.onPause(); savePrefs() }
    private fun savePrefs() {
        prefs.edit().putString("api_id", apiId.text.toString().trim())
            .putString("api_hash", apiHash.text.toString().trim())
            .putString("phone", phone.text.toString().trim())
            .putString("worker_url", workerUrl.text.toString().trim())
            .putString("channels_v2", channels.joinToString("\n")).apply()
    }
    private fun say(t: String) { status.text = t }
    private fun importIntoV2ray(text: String) {
        val r: Any? = AngConfigManager.importBatchConfig(text, "", true)
        println("import result: $r")
    }
}