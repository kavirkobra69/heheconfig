package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.handler.AngConfigManager // اگه ارور داد: com.v2ray.ang.util.AngConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import androidx.core.content.FileProvider
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class TelegramSettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("tg_import", Context.MODE_PRIVATE) }
    private val channels = mutableListOf<String>()
    private var lang = "fa"
    private var mode: String? = null // null = home, "github" = GitHub only, "telegram" = Telegram account
    private var range = 0
    private var updateInProgress = false
    private var pingRunInProgress = false
    private val log = StringBuilder()
    private val githubImportedGuids = mutableListOf<String>()
    private val githubConfigByGuid = linkedMapOf<String, String>()

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var channelList: LinearLayout
    private val backupPassword by lazy { edit("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) }
    private val RC_EXPORT = 7101
    private val RC_IMPORT = 7102

    private fun t(k: String): String = STR[lang]?.get(k) ?: k
    private fun tf(k: String, vararg a: Any?): String = String.format(t(k), *a)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun edit(key: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            inputType = type
            if (key.isNotEmpty()) setText(prefs.getString(key, ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(166, 166, 190))
            textSize = 14f
            background = rounded(Color.rgb(37, 35, 55), 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

    private fun btn(onClick: () -> Unit) = Button(this).apply {
        setOnClickListener { onClick() }
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(124, 58, 237), Color.rgb(219, 39, 119))
        ).apply { cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        elevation = dp(4).toFloat()
        minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5); bottomMargin = dp(5) }
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 19f; setTextColor(Color.rgb(245, 238, 255))
        setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD)); setPadding(0, dp(24), 0, dp(10))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(Color.rgb(195, 192, 215))
        setPadding(0, dp(6), 0, dp(6))
    }

    private val apiId by lazy { edit("api_id", InputType.TYPE_CLASS_NUMBER) }
    private val apiHash by lazy { edit("api_hash") }
    private val phone by lazy { edit("phone", InputType.TYPE_CLASS_PHONE) }
    private val code by lazy { edit("") }
    private val password by lazy {
        edit("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }
    private val workerUrl by lazy { edit("worker_url") }
    private val githubToken by lazy { edit("github_token", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = "GitHub token (Contents read/write)" } }
    private val githubJsonUrl by lazy { edit("github_json_url").apply {
        if (text.isNullOrBlank()) setText("https://raw.githubusercontent.com/kavirkobra69/heheconfig/main/configs.json")
    } }
    private val newChannel by lazy { edit("") }

    // ---------- دکمه‌ها ----------
    private val githubModeBtn by lazy { btn { mode = "github"; render() } }
    private val telModeBtn by lazy { btn { mode = "telegram"; val id = prefs.getString("api_id", "")?.toIntOrNull(); val hash = prefs.getString("api_hash", "") ?: ""; if (id != null && hash.isNotBlank()) TelegramManager.start(this, id, hash); render() } }
    private val homeBtn by lazy { btn { mode = null; render() } }
    private val settingsBtn by lazy { btn { mode = "settings"; render() } }
    private val updateBtn by lazy { btn { checkAndInstallUpdate() } }
    private val adminModeBtn by lazy { btn { mode = "admin"; render() } }
    private val adminLoginBtn by lazy { btn { loginGitHubAdmin() } }
    private val adminReposBtn by lazy { btn { loadGitHubRepositories() } }
    private val adminRepairBtn by lazy { btn { repairSelectedConfigFile() } }
    private val adminConfigPath by lazy { edit("admin_config_path").apply { hint = "configs.json / config.json path" } }
    private val adminRepoLabel by lazy { label("Repository not selected") }

    private val langBtn by lazy {
        btn {
            lang = if (lang == "fa") "en" else "fa"
            prefs.edit().putString("lang", lang).apply()
            render()
        }
    }

    private val sendBtn by lazy {
        btn {
            if (startTd()) lifecycleScope.launch {
                say(t("sending"))
                try {
                    TelegramManager.sendPhone(phone.text.toString())
                    say(if (TelegramManager.state.value == "ready") t("already") else t("code_sent"))
                } catch (e: Exception) { say(tf("error", e.message)) }
            }
        }
    }

    private val loginBtn by lazy {
        btn {
            lifecycleScope.launch {
                say(t("logging_in"))
                try {
                    val s = TelegramManager.sendCode(code.text.toString())
                    if (s == "password") {
                        if (password.text.isEmpty()) { say(t("need_pw")); return@launch }
                        TelegramManager.sendPassword(password.text.toString())
                    }
                    say(t("logged_in"))
                } catch (e: Exception) { say(tf("error", e.message)) }
            }
        }
    }

    private val logoutBtn by lazy {
        btn {
            lifecycleScope.launch {
                TelegramManager.logout()
                prefs.edit().remove("api_id").remove("api_hash").remove("phone").apply()
                apiId.setText(""); apiHash.setText(""); phone.setText("")
                render()
            }
        }
    }

    private val saveWorkerBtn by lazy {
        btn { savePrefs(); say(t("saved")) }
    }

    private val exportBackupBtn by lazy { btn { startBackupExport() } }
    private val importBackupBtn by lazy { btn { startBackupImport() } }
    private val addBtn by lazy { btn { addChannel() } }
    private val githubImportBtn by lazy { btn { importFromGitHub() } }
    private val publishTelegramConfigsBtn by lazy { btn { importTelegramConfigsToGitHubJson() } }
    private val testRealDelaysBtn by lazy { btn { confirmAndTestGitHubConfigs() } }
    private val pingSettingsBtn by lazy { btn { showPingSettings() } }

    private val rangeBtn by lazy {
        btn { range = (range + 1) % 3; savePrefs(); render() }
    }

    private val importBtn by lazy {
        btn {
            savePrefs()
            if (channels.isEmpty()) { say(t("add_first")); return@btn }
            val worker = workerUrl.text.toString().trim()
            if (worker.isEmpty() && TelegramManager.state.value != "ready") {
                say(t("need_login_or_worker")); return@btn
            }
            val since = TelegramManager.sinceFor(range)
            lifecycleScope.launch {
                val all = LinkedHashSet<String>()
                log.clear()
                for (ch in channels.toList()) {
                    say(log.toString() + tf("reading", ch))
                    try {
                        val r = get(worker, ch, since)
                        all.addAll(r.configs)
                        log.append(
                            if (r.scanned >= 0) tf("ch_result_diag", ch, r.configs.size, r.scanned, r.inRange)
                            else tf("ch_result", ch, r.configs.size)
                        ).append("\n")
                    } catch (e: Exception) {
                        log.append(tf("ch_error", ch, errText(e))).append("\n")
                    }
                    say(log.toString())
                }
                if (all.isEmpty()) { say(log.toString() + t("none_found")); return@launch }
                try {
                    importIntoV2ray(all.joinToString("\n"))
                    say(log.toString() + tf("imported", all.size))
                } catch (e: Exception) { say(log.toString() + tf("import_error", e.message)) }
            }
        }
    }

    private suspend fun get(worker: String, ch: String, since: Long): TelegramManager.FetchResult {
        if (worker.isNotEmpty()) {
            try { return TelegramManager.fetchViaWorker(worker, ch, since) }
            catch (e: Exception) { if (TelegramManager.state.value != "ready") throw e }
        }
        return TelegramManager.fetchToday(ch, since.toInt())
    }

    private fun errText(e: Exception): String = when {
        e.message == "NOT_JSON" -> t("err_not_json")
        e.message == "no_preview" -> t("err_no_preview")
        e.message == "forbidden" -> t("err_key")
        else -> e.message ?: "?"
    }

    private fun startTd(): Boolean {
        val id = apiId.text.toString().trim().toIntOrNull()
        val hash = apiHash.text.toString().trim()
        if (id == null || hash.isEmpty()) { say(t("fill_api")); return false }
        savePrefs()
        TelegramManager.start(this, id, hash)
        return true
    }

    // ---------- چنل‌ها ----------
    private fun normalize(raw: String): String {
        var s = raw.trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("t.me/s/").removePrefix("t.me/").removePrefix("@")
        s = s.substringBefore('/').substringBefore('?')
        return s
    }

    private fun addChannel() {
        val ch = normalize(newChannel.text.toString())
        if (ch.isEmpty() || channels.any { it.equals(ch, true) }) { newChannel.setText(""); return }
        channels.add(ch)
        newChannel.setText("")
        savePrefs()
        refreshChannels()
        if (mode == "github") syncChannelsToGitHub()
    }

    private fun refreshChannels() {
        channelList.removeAllViews()
        if (channels.isEmpty()) channelList.addView(label(t("no_channels")))
        channels.toList().forEach { ch ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.addView(TextView(this).apply {
                text = if (ch.toLongOrNull() != null) "Channel ID: $ch" else "@$ch"
                textSize = 14f
                setTextColor(Color.rgb(232, 224, 255))
                setPadding(dp(8), dp(5), dp(8), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(btn { channels.remove(ch); savePrefs(); refreshChannels(); if (mode == "github") syncChannelsToGitHub(ch) }
                .apply {
                    text = t("remove")
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                        topMargin = 0; bottomMargin = 0; marginStart = dp(4)
                    }
                    setPadding(dp(10), 0, dp(10), 0)
                    minimumHeight = dp(40)
                })
            channelList.addView(row)
        }
    }

    // ---------- صفحه ----------
    private fun render() {
        root.removeAllViews()
        root.layoutDirection =
            if (lang == "fa") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        langBtn.text = if (lang == "fa") "English" else "فارسی"
        apiId.hint = "API ID"
        apiHash.hint = "API Hash"
        phone.hint = t("phone_hint")
        code.hint = t("code_hint")
        password.hint = t("pw_hint")
        workerUrl.hint = t("worker_hint")
        githubJsonUrl.hint = t("github_url_hint")
        githubToken.hint = t("github_token_hint")
        newChannel.hint = t("channel_hint")
        sendBtn.text = t("send_code")
        loginBtn.text = t("login")
        logoutBtn.text = t("logout")
        saveWorkerBtn.text = t("worker_save")
        addBtn.text = t("add_channel")
        rangeBtn.text = if (lang == "fa") "بازه: " + listOf("امروز", "۲۴ ساعت اخیر", "۳ روز اخیر")[range.coerceIn(0, 2)] else tf("range_label", t("range$range"))
        importBtn.text = t("import_btn")
        githubImportBtn.text = t("github_import_btn")
        publishTelegramConfigsBtn.text = if (lang == "fa") "ایمپورت کانفیگ‌ها از تلگرام" else "Import configs from Telegram"
        testRealDelaysBtn.text = t("test_real_delays")
        backupPassword.hint = t("backup_password")
        exportBackupBtn.text = t("backup_export")
        importBackupBtn.text = t("backup_import")

        val st = TelegramManager.state.value
        val hasCreds = !prefs.getString("api_id", "").isNullOrEmpty() &&
                !prefs.getString("api_hash", "").isNullOrEmpty()

        if (mode == null) {
            root.addView(title(t("home_title")))
            root.addView(label(t("home_description")))
            githubModeBtn.text = "GitHub"
            telModeBtn.text = "TelAccount"
            adminModeBtn.text = "Admin / Repository Manager"
            root.addView(githubModeBtn)
            root.addView(telModeBtn)
            root.addView(adminModeBtn)
            settingsBtn.text = if (lang == "fa") "⚙ تنظیمات" else "⚙ Settings"
            root.addView(settingsBtn)
            root.addView(status)
            return
        }

        homeBtn.text = t("back_home")
        root.addView(homeBtn)

        if (mode == "settings") {
            root.addView(title(if (lang == "fa") "تنظیمات برنامه" else "App settings"))
            root.addView(langBtn)
            updateBtn.text = t("check_update")
            root.addView(updateBtn)
            root.addView(title(if (lang == "fa") "تنظیمات GitHub" else "GitHub settings"))
            root.addView(label(t("github_url_hint")))
            root.addView(githubJsonUrl)
            root.addView(label(t("github_token_hint")))
            root.addView(githubToken)
            root.addView(rangeBtn)
            root.addView(title(if (lang == "fa") "تنظیمات تلگرام / Worker" else "Telegram / Worker settings"))
            root.addView(workerUrl)
            root.addView(saveWorkerBtn)
            root.addView(status)
            return
        }

        if (mode == "admin") {
            root.addView(title("GitHub Admin"))
            root.addView(label("Enter a GitHub token; it will be saved on this device."))
            root.addView(githubToken)
            adminLoginBtn.text = "Save token and sign in"
            root.addView(adminLoginBtn)
            adminReposBtn.text = "Load my repositories"
            root.addView(adminReposBtn)
            root.addView(adminRepoLabel)
            root.addView(label("Select a repository. The app will find configs.json or config.json automatically; no path typing is needed."))
            adminConfigPath.isFocusable = false
            adminConfigPath.isFocusableInTouchMode = false
            adminConfigPath.isClickable = false
            root.addView(adminConfigPath)
            if (githubTokenValue().isNotBlank() && prefs.getString("admin_config_path", "").isNullOrBlank() && prefs.getString("admin_config_discovery_attempted_repo", "") != selectedRepository()) {
                prefs.edit().putString("admin_config_discovery_attempted_repo", selectedRepository()).apply()
                discoverConfigPath(selectedRepository())
            }
            adminRepairBtn.text = "Validate / repair config JSON"
            root.addView(adminRepairBtn)
            root.addView(status)
            return
        }

        if (mode == "telegram") {
            root.addView(title(t("tg_title")))
            when {
                st == "ready" -> {
                    root.addView(label(t("connected")))
                    root.addView(logoutBtn)
                }
                st == "init" && hasCreds -> root.addView(label(t("connecting")))
                else -> listOf<View>(apiId, apiHash, phone, sendBtn, code, password, loginBtn)
                    .forEach { root.addView(it) }
            }
            root.addView(title(t("worker_title")))
            root.addView(workerUrl)
            root.addView(saveWorkerBtn)
        }

        if (mode != "settings") {
            root.addView(title(t("channels_title")))
            root.addView(newChannel)
            root.addView(addBtn)
            root.addView(channelList)
            refreshChannels()
        }

        if (mode == "github") {
            root.addView(title(t("github_title")))
            root.addView(label(t("github_description")))
            root.addView(publishTelegramConfigsBtn)
            root.addView(githubImportBtn)
            root.addView(testRealDelaysBtn)
            pingSettingsBtn.text = if (lang == "fa") "تنظیمات تست پینگ" else "Ping test settings"
            root.addView(pingSettingsBtn)
        } else {
            root.addView(title(t("import_title")))
            root.addView(rangeBtn)
            root.addView(importBtn)
            root.addView(title(t("backup_title")))
            root.addView(label(t("backup_warning")))
            root.addView(backupPassword)
            root.addView(exportBackupBtn)
            root.addView(importBackupBtn)
        }
        root.addView(status)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Telegram Import"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(18, 17, 29), Color.rgb(30, 20, 45), Color.rgb(18, 17, 29))
            )
        }
        status = TextView(this).apply { setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = 14f; setTextColor(Color.rgb(232, 224, 255)); background = rounded(Color.rgb(35, 28, 52), 14) }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(root) })

        lang = prefs.getString("lang", "fa") ?: "fa"
        range = prefs.getInt("range", 0)
        channels.addAll(prefs.getString("channels_v2", "")!!.lines().filter { it.isNotBlank() })
        githubToken.setText(prefs.getString("github_token", "") ?: "")
        adminConfigPath.setText(prefs.getString("admin_config_path", "") ?: "")
        adminRepoLabel.text = "Selected repository: " + selectedRepository()
        githubImportedGuids.addAll((prefs.getString("github_imported_guids", "") ?: "").lines().filter { it.isNotBlank() })
        try { val saved = JSONObject(prefs.getString("github_config_by_guid", "{}") ?: "{}"); saved.keys().forEach { k -> githubConfigByGuid[k] = saved.optString(k) } } catch (_: Exception) {}

        // Telegram authentication starts only after the user selects TelAccount.
        lifecycleScope.launch { TelegramManager.state.collect { render() } }
    }

    override fun onPause() { super.onPause(); savePrefs() }

    private fun startBackupExport() {
        val pw = backupPassword.text.toString()
        if (pw.length < 10) { say(t("backup_password_short")); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "telegram-account-backup.tgbackup")
        }
        pendingBackupPassword = pw
        startActivityForResult(intent, RC_EXPORT)
    }

    private fun startBackupImport() {
        val pw = backupPassword.text.toString()
        if (pw.length < 10) { say(t("backup_password_short")); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        pendingBackupPassword = pw
        startActivityForResult(intent, RC_IMPORT)
    }

    private var pendingBackupPassword = ""

    @Deprecated("Deprecated by Android; retained for compatibility with this Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val pw = pendingBackupPassword
        pendingBackupPassword = ""
        lifecycleScope.launch {
            try {
                when (requestCode) {
                    RC_EXPORT -> {
                        val out = contentResolver.openOutputStream(uri) ?: error("Cannot open output")
                        out.use { SessionBackup.export(this@TelegramSettingsActivity, it, pw, prefs) }
                        say(t("backup_exported"))
                    }
                    RC_IMPORT -> {
                        val input = contentResolver.openInputStream(uri) ?: error("Cannot open backup")
                        val restored = input.use { SessionBackup.extract(this@TelegramSettingsActivity, it, pw) }
                        TelegramManager.closeClient()
                        SessionBackup.commit(this@TelegramSettingsActivity, restored, prefs)
                        val id = prefs.getString("api_id", "")?.toIntOrNull()
                        val hash = prefs.getString("api_hash", "") ?: ""
                        if (id != null && hash.isNotBlank()) TelegramManager.start(this@TelegramSettingsActivity, id, hash)
                        apiId.setText(prefs.getString("api_id", ""))
                        apiHash.setText(prefs.getString("api_hash", ""))
                        phone.setText(prefs.getString("phone", ""))
                        workerUrl.setText(prefs.getString("worker_url", ""))
                        githubJsonUrl.setText(prefs.getString("github_json_url", "https://raw.githubusercontent.com/kavirkobra69/heheconfig/main/configs.json"))
                        channels.clear()
                        channels.addAll((prefs.getString("channels_v2", "") ?: "").lines().filter { it.isNotBlank() })
                        range = prefs.getInt("range", 0)
                        lang = prefs.getString("lang", "fa") ?: "fa"
                        refreshChannels()
                        render()
                        say(t("backup_imported"))
                    }
                }
            } catch (e: Exception) {
                say(tf("error", e.message ?: "backup_failed"))
            }
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun checkAndInstallUpdate() {
        if (updateInProgress) { say("بررسی/آپدیت در حال اجراست…"); return }
        updateInProgress = true
        updateBtn.isEnabled = false
        lifecycleScope.launch {
            say(t("update_checking"))
            try {
                val result = withContext(Dispatchers.IO) {
                    val c = (URL("https://api.github.com/repos/kavirkobra69/heheconfig/releases?per_page=10&t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "HeheConfig-Android-Updater")
                        setRequestProperty("Cache-Control", "no-cache, no-store")
                        setRequestProperty("Pragma", "no-cache")
                    }
                    val releases = try {
                        if (c.responseCode !in 200..299) throw Exception("GitHub HTTP ${c.responseCode}")
                        JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                    } finally { c.disconnect() }

                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                    var latestVersion = ""
                    var assets = JSONArray()
                    var foundRelease = false
                    for (i in 0 until releases.length()) {
                        val candidate = releases.optJSONObject(i) ?: continue
                        if (candidate.optBoolean("draft", false) || candidate.optBoolean("prerelease", false)) continue
                        val tag = candidate.optString("tag_name", "").removePrefix("v").trim()
                        val candidateAssets = candidate.optJSONArray("assets") ?: JSONArray()
                        val hasApk = (0 until candidateAssets.length()).any { n ->
                            candidateAssets.optJSONObject(n)?.optString("name")?.endsWith(".apk", true) == true
                        }
                        if (tag.isNotBlank() && hasApk && (!foundRelease || compareVersions(tag, latestVersion) > 0)) {
                            latestVersion = tag
                            assets = candidateAssets
                            foundRelease = true
                        }
                    }
                    if (!foundRelease) throw Exception("هیچ Release پایدار دارای APK پیدا نشد.")
                    if (compareVersions(latestVersion, currentVersion) <= 0)
                        return@withContext Triple<File?, String, String>(null, currentVersion, latestVersion)
                    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "universal"
                    val candidates = (0 until assets.length()).mapNotNull { i ->
                        assets.optJSONObject(i)?.let { it.optString("name") to it.optString("browser_download_url") }
                    }.filter { it.first.endsWith(".apk", true) && it.second.startsWith("https://") }
                    val preferred = when {
                        abi.contains("arm64", true) -> listOf("arm64-v8a", "universal")
                        abi.contains("armeabi", true) -> listOf("armeabi-v7a", "universal")
                        abi.contains("x86_64", true) -> listOf("x86_64", "universal")
                        abi.contains("x86", true) -> listOf("x86", "universal")
                        else -> listOf("universal")
                    }
                    val chosen = preferred.firstNotNullOfOrNull { suffix ->
                        candidates.firstOrNull { it.first.contains(suffix, true) }
                    } ?: throw Exception("برای معماری این گوشی APK مناسب در Release پیدا نشد.")
                    val assetName = chosen.first
                    val downloadUrl = chosen.second
                    val outDir = File(cacheDir, "updates").apply { mkdirs() }
                    val out = File(outDir, assetName)
                    if (out.exists()) out.delete()
                    val d = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 120000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "HeheConfig-Android-Updater")
                    }
                    try {
                        if (d.responseCode !in 200..299) throw Exception("APK download HTTP ${d.responseCode}")
                        d.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                        if (!out.isFile || out.length() < 1_000_000L)
                            throw Exception("APK ناقص یا دانلود خراب است؛ دوباره تلاش کن.")
                        val header = out.inputStream().use { input -> ByteArray(2).also { if (input.read(it) != 2) throw Exception("فایل دانلودشده ناقص است.") } }
                        if (header[0] != 0x50.toByte() || header[1] != 0x4b.toByte())
                            throw Exception("فایل دانلودشده APK/ZIP معتبر نیست؛ لینک Release را بررسی کن.")
                    } finally { d.disconnect() }
                    Triple(out, currentVersion, latestVersion)
                }
                val apk = result.first
                if (apk == null) {
                    say(if (compareVersions(result.second, result.third) == 0)
                        "برنامه به‌روز است · نسخه ${result.second}"
                    else "نسخه نصب‌شده (${result.second}) از آخرین نسخه (${result.third}) جدیدتر است.")
                    return@launch
                }
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    say(t("update_unknown_sources"))
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(this@TelegramSettingsActivity, "$packageName.fileprovider", apk)
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
                say("نسخه ${result.third} دانلود شد؛ نصب را در پنجره اندروید تأیید کن.")
                startActivity(intent)
            } catch (e: Exception) {
                say("آپدیت ناموفق بود: ${e.message ?: "خطای نامشخص"}")
            } finally {
                updateInProgress = false
                updateBtn.isEnabled = true
            }
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("api_id", apiId.text.toString().trim())
            .putString("api_hash", apiHash.text.toString().trim())
            .putString("phone", phone.text.toString().trim())
            .putString("worker_url", workerUrl.text.toString().trim())
            .putString("github_json_url", githubJsonUrl.text.toString().trim())
            .putString("github_token", githubToken.text.toString().trim())
            .putString("channels_v2", channels.joinToString("\n"))
            .putInt("range", range)
            .apply()
    }

    private fun say(s: String) { status.text = s }

    private suspend fun resolveConfigPathForRepo(repo: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val metadata = JSONObject(githubApi("https://api.github.com/repos/" + repo))
        val branch = metadata.optString("default_branch", "main")
        val encodedBranch = java.net.URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val tree = JSONObject(githubApi("https://api.github.com/repos/" + repo + "/git/trees/" + encodedBranch + "?recursive=1"))
            .optJSONArray("tree") ?: JSONArray()
        val candidates = mutableListOf<String>()
        for (i in 0 until tree.length()) {
            val item = tree.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue
            val path = item.optString("path")
            val name = path.substringAfterLast("/")
            if (name.equals("configs.json", true) || name.equals("config.json", true)) candidates.add(path)
        }
        val selected = candidates.sortedWith(compareBy<String>(
            { if (it == "configs.json") 0 else if (it == "config.json") 1 else 2 },
            { it.count { ch -> ch == '/' } }, { it }
        )).firstOrNull() ?: "config.json"
        selected to branch
    }

    /**
     * Runs the repository's Update Telegram configs workflow on GitHub Actions.
     * No Telegram session is opened on the phone; the workflow's TG_* secrets are used.
     */
    private fun importTelegramConfigsToGitHubJson() {
        savePrefs()
        if (githubTokenValue().isBlank()) { say("اول در بخش Admin توکن GitHub را ذخیره کن."); return }
        if (channels.isEmpty()) { say(t("add_first")); return }
        val repo = selectedRepository()
        lifecycleScope.launch {
            try {
                say("در حال ارسال فهرست کانال‌ها و اجرای GitHub Actions…")
                val outcome = withContext(Dispatchers.IO) {
                    val (old, sha) = try { githubReadText("telegram_channels.txt") } catch (e: Exception) {
                        if (e.message?.contains("HTTP 404") == true) "" to null else throw e
                    }
                    val comments = old.lines().filter { it.trim().startsWith("#") || it.isBlank() }
                    val mergedChannels = (comments + channels.map { it.trim() }.filter { it.isNotBlank() })
                        .distinctBy { it.removePrefix("@").lowercase() }
                    val channelText = mergedChannels.joinToString("\n").trimEnd() + "\n"
                    if (old != channelText) githubWriteText("telegram_channels.txt", channelText, sha, "Update Telegram channels from Android")
                    val startedAt = System.currentTimeMillis() / 1000L
                    dispatchTelegramWorkflow()
                    var runId: Long? = null
                    var completed = false
                    var conclusion = ""
                    repeat(90) {
                        val runs = JSONObject(githubApi("https://api.github.com/repos/" + repo + "/actions/workflows/update-configs.yml/runs?event=workflow_dispatch&per_page=10"))
                            .optJSONArray("workflow_runs") ?: JSONArray()
                        for (i in 0 until runs.length()) {
                            val run = runs.optJSONObject(i) ?: continue
                            val createdEpoch = try { java.time.Instant.parse(run.optString("created_at")).epochSecond } catch (_: Exception) { 0L }
                            if (createdEpoch >= startedAt - 5) {
                                runId = run.optLong("id").takeIf { it > 0 }
                                if (run.optString("status") == "completed") {
                                    completed = true
                                    conclusion = run.optString("conclusion")
                                }
                                break
                            }
                        }
                        if (completed) return@withContext Triple(true, conclusion, runId)
                        Thread.sleep(4000)
                    }
                    Triple(false, "timeout", runId)
                }
                if (!outcome.first) {
                    say("GitHub Actions اجرا شد، اما هنوز تمام نشده. چند دقیقه دیگر دوباره بزن؛ وضعیت اجرا را هم می‌توانی در Actions ببینی.")
                    return@launch
                }
                if (outcome.second != "success") {
                    say("اجرای Update Telegram configs ناموفق بود (" + outcome.second + "). لاگ Actions را بررسی کن؛ معمولاً TG_API_ID / TG_API_HASH / TG_SESSION یا دسترسی کانال مشکل دارد.")
                    return@launch
                }
                val rawUrl = "https://raw.githubusercontent.com/" + repo + "/main/configs.json"
                githubJsonUrl.setText(rawUrl)
                prefs.edit().putString("github_json_url", rawUrl).apply()
                say("کانفیگ‌ها در GitHub به‌روزرسانی شدند؛ در حال دریافت از configs.json…")
                importFromGitHub()
            } catch (e: Exception) {
                say("اجرای GitHub Actions یا دریافت کانفیگ‌ها ناموفق بود: " + (e.message ?: "unknown") +
                    "\nمطمئن شو توکن GitHub دسترسی Contents: Read/Write و Actions: Write دارد.")
            }
        }
    }

    private fun dispatchTelegramWorkflow() {
        val token = githubTokenValue()
        val repo = selectedRepository()
        val url = URL("https://api.github.com/repos/" + repo + "/actions/workflows/update-configs.yml/dispatches")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "heheconfig-android")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use {
                it.write(JSONObject().put("ref", "main").toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw Exception("GitHub Actions dispatch HTTP $code: $body")
            }
        } finally { connection.disconnect() }
    }

    private fun importFromGitHub() {
        val source = githubJsonUrl.text.toString().trim()
        if (!source.startsWith("https://")) { say(t("github_url_invalid")); return }
        savePrefs()
        lifecycleScope.launch {
            say(t("github_loading"))
            try {
                val configs = withContext(Dispatchers.IO) {
                    val fetchUrl = source + (if (source.contains("?")) "&" else "?") + "t=" + System.currentTimeMillis()
                    val connection = (URL(fetchUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000
                        setRequestProperty("User-Agent", "heheconfig-android")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Cache-Control", "no-cache")
                        setRequestProperty("Pragma", "no-cache")
                    }
                    try {
                        if (connection.responseCode !in 200..299) throw Exception("HTTP ${connection.responseCode}")
                        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                        val values = try {
                            val array = JSONArray(body)
                            (0 until array.length()).map { array.optString(it) }
                        } catch (_: Exception) {
                            val obj = JSONObject(body)
                            val array = obj.optJSONArray("configs") ?: obj.optJSONArray("proxies")
                                ?: obj.optJSONArray("data") ?: throw Exception("JSON has no config array")
                            (0 until array.length()).map { array.optString(it) }
                        }
                        val found = LinkedHashSet<String>()
                        val pattern = Regex("""(?i)^(vmess|vless|trojan|ss|hysteria2|hy2|tuic)://\S+$""")
                        for (raw in values) {
                            val value = raw.trim()
                            if (pattern.matches(value)) found.add(value)
                        }
                        found.toList()
                    } finally { connection.disconnect() }
                }
                if (configs.isEmpty()) { say(t("github_empty")); return@launch }
                val newIds = linkedSetOf<String>()
                githubConfigByGuid.clear()
                for ((index, config) in configs.withIndex()) {
                    say(tf("github_import_progress", index + 1, configs.size))
                    val before = MmkvManager.decodeAllServerList().toSet()
                    importIntoV2ray(config)
                    val after = MmkvManager.decodeAllServerList().toSet()
                    (after - before).forEach { guid -> newIds.add(guid); githubConfigByGuid[guid] = config }
                }
                githubImportedGuids.clear(); githubImportedGuids.addAll(newIds)
                prefs.edit().putString("github_imported_guids", newIds.joinToString("\n"))
                    .putString("github_config_by_guid", JSONObject(githubConfigByGuid as Map<*, *>).toString()).apply()
                say(tf("github_imported", configs.size, newIds.size))
            } catch (e: Exception) { say(tf("error", e.message ?: "github_import_failed")) }
        }
    }

    private fun showPingSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val maxPing = edit("").apply {
            hint = if (lang == "fa") "حداکثر پینگ (میلی‌ثانیه)، مثلاً 200" else "Maximum ping in ms, e.g. 200"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("ping_max_ms", 200).toString())
        }
        val batchSize = edit("").apply {
            hint = if (lang == "fa") "تعداد کانفیگ در هر دسته (1 تا 50)" else "Configs per batch (1 to 50)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("ping_batch_size", 10).toString())
        }
        val removeBad = CheckBox(this).apply {
            text = if (lang == "fa") "حذف خودکار کانفیگ‌های بالاتر از حد پینگ یا بدون پاسخ" else "Automatically remove over-limit or non-responding configs"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("ping_auto_remove", true)
        }
        panel.addView(label(if (lang == "fa") "حداکثر پینگ مجاز" else "Maximum allowed ping"))
        panel.addView(maxPing)
        panel.addView(label(if (lang == "fa") "تعداد کانفیگ در هر دسته (برای کنترل سرعت)" else "Configs per batch (controls speed)"))
        panel.addView(batchSize)
        panel.addView(removeBad)
        AlertDialog.Builder(this)
            .setTitle(if (lang == "fa") "تنظیمات تست پینگ" else "Ping test settings")
            .setView(panel)
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(if (lang == "fa") "ذخیره تنظیمات" else "Save settings") { _, _ ->
                val limit = maxPing.text.toString().toIntOrNull()
                val batch = batchSize.text.toString().toIntOrNull()
                if (limit == null || limit !in 1..60000) {
                    say(if (lang == "fa") "حد پینگ باید بین 1 تا 60000 میلی‌ثانیه باشد." else "Ping limit must be 1–60000 ms.")
                    return@setPositiveButton
                }
                if (batch == null || batch !in 1..50) {
                    say(if (lang == "fa") "اندازه دسته باید بین 1 تا 50 باشد." else "Batch size must be between 1 and 50.")
                    return@setPositiveButton
                }
                prefs.edit().putInt("ping_max_ms", limit).putInt("ping_batch_size", batch)
                    .putBoolean("ping_auto_remove", removeBad.isChecked).apply()
                say(if (lang == "fa") "ذخیره شد: حد $limit ms، دسته $batch، حذف خودکار ${if (removeBad.isChecked) "روشن" else "خاموش"}." else "Saved: limit $limit ms, batch $batch, auto-remove ${if (removeBad.isChecked) "on" else "off"}.")
            }
            .show()
    }

    private fun confirmAndTestGitHubConfigs() {
        val ids = githubImportedGuids.toList()
        if (ids.isEmpty()) { say(t("test_import_first")); return }
        if (pingRunInProgress) { say(if (lang == "fa") "تست پینگ در حال اجراست." else "A ping test is already running."); return }
        val limit = prefs.getInt("ping_max_ms", 200)
        val batch = prefs.getInt("ping_batch_size", 10)
        val remove = prefs.getBoolean("ping_auto_remove", true)
        val message = if (lang == "fa")
            "حد پینگ: $limit ms\nاندازه هر دسته: $batch\nحذف خودکار: ${if (remove) "روشن" else "خاموش"}\n\nکانفیگ‌های بالاتر از حد یا بدون پاسخ ناموفق محسوب می‌شوند."
        else "Maximum ping: $limit ms\nBatch size: $batch\nAuto-remove: ${if (remove) "ON" else "OFF"}\n\nConfigs over the limit or without a response are considered failed."
        AlertDialog.Builder(this)
            .setTitle(t("test_real_delays"))
            .setMessage(message)
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(t("test_start")) { _, _ -> testGitHubConfigs(ids) }
            .show()
    }

    private fun testGitHubConfigs(ids: List<String>) {
        val targets = ids.distinct().filter { MmkvManager.decodeServerConfig(it) != null }
        if (targets.isEmpty()) { say(t("test_import_first")); return }
        pingRunInProgress = true
        val successful = ConcurrentHashMap.newKeySet<String>()
        val limit = prefs.getInt("ping_max_ms", 200)
        val batchSize = prefs.getInt("ping_batch_size", 10).coerceIn(1, 50)
        val autoRemove = prefs.getBoolean("ping_auto_remove", true)
        say("شروع تست ${targets.size} کانفیگ؛ حد پینگ $limit ms، دسته $batchSize ...")
        fun runBatch(offset: Int) {
            if (offset >= targets.size) {
                runOnUiThread {
                    pingRunInProgress = false
                    val failed = targets.filterNot { successful.contains(it) }
                    if (autoRemove && failed.isNotEmpty()) {
                        cleanupFailedConfigs(failed)
                    } else {
                        val suffix = if (autoRemove) "چیزی برای حذف نبود." else "حذف خودکار خاموش بود؛ کانفیگ‌ها حفظ شدند."
                        say("تست تمام شد: ${successful.size} سالم، ${failed.size} ناموفق. $suffix")
                    }
                }
                return
            }
            val batch = targets.subList(offset, minOf(offset + batchSize, targets.size))
            try {
                RealPingWorkerService(this, batch) { event ->
                    when (event) {
                        is RealPingEvent.Result -> if (event.delayMillis >= 0L && event.delayMillis <= limit) successful.add(event.guid)
                        is RealPingEvent.Progress -> runOnUiThread {
                            say("تست ${minOf(offset + batch.size, targets.size)}/${targets.size}؛ سالم ${successful.size}، حد $limit ms")
                        }
                        is RealPingEvent.Finish -> runOnUiThread { runBatch(offset + batch.size) }
                    }
                }.start()
            } catch (e: Exception) {
                runOnUiThread {
                    pingRunInProgress = false
                    say("تست پینگ ناموفق شد: " + (e.message ?: "unknown error"))
                }
            }
        }
        runBatch(0)
    }

    private fun githubTokenValue(): String = (githubToken.text.toString().trim().ifEmpty { prefs.getString("github_token", "") ?: "" })

    private fun selectedRepository(): String = prefs.getString("admin_repo_full_name", "kavirkobra69/heheconfig") ?: "kavirkobra69/heheconfig"

    private fun githubRequest(path: String, method: String, body: JSONObject? = null): JSONObject {
        val token = githubTokenValue()
        if (token.isBlank()) throw Exception("GitHub token را وارد کن")
        val url = URL("https://api.github.com/repos/" + selectedRepository() + "/contents/" + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "heheconfig-android")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("GitHub API HTTP $code: $text")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun githubApi(urlText: String): String {
        val token = githubTokenValue()
        if (token.isBlank()) throw Exception("Enter a GitHub token first")
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "heheconfig-android-admin")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("GitHub API HTTP " + code + ": " + text)
            return text
        } finally { connection.disconnect() }
    }

    private fun loginGitHubAdmin() {
        val token = githubToken.text.toString().trim()
        if (token.isBlank()) { say("Enter your GitHub token first."); return }
        prefs.edit().putString("github_token", token).apply()
        lifecycleScope.launch {
            try {
                val user = withContext(Dispatchers.IO) { JSONObject(githubApi("https://api.github.com/user")) }
                say("Signed in as @" + user.optString("login") + ". Token saved on this device.")
            } catch (e: Exception) { say("Sign-in failed: " + (e.message ?: "unknown error")) }
        }
    }

    private fun loadGitHubRepositories() {
        val token = githubToken.text.toString().trim()
        if (token.isNotBlank()) prefs.edit().putString("github_token", token).apply()
        if (githubTokenValue().isBlank()) { say("Enter and save your GitHub token first."); return }
        lifecycleScope.launch {
            try {
                val repos = withContext(Dispatchers.IO) {
                    val array = JSONArray(githubApi("https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member"))
                    val names = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val full = item.optString("full_name")
                        if (full.isNotBlank()) names.add(full)
                    }
                    names
                }
                if (repos.isEmpty()) { say("No repositories returned. Check token permissions."); return@launch }
                val current = selectedRepository()
                AlertDialog.Builder(this@TelegramSettingsActivity)
                    .setTitle("Select repository")
                    .setSingleChoiceItems(repos.toTypedArray(), repos.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                        val repo = repos[which]
                        prefs.edit().putString("admin_repo_full_name", repo).remove("admin_config_path").putString("admin_config_discovery_attempted_repo", repo).apply()
                        adminConfigPath.setText("")
                        adminRepoLabel.text = "Selected repository: " + repo
                        dialog.dismiss()
                        discoverConfigPath(repo)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                adminRepoLabel.text = "Current repository: " + current
                say("Loaded " + repos.size + " repositories.")
            } catch (e: Exception) { say("Could not load repositories: " + (e.message ?: "unknown error")) }
        }
    }

    private fun discoverConfigPath(repo: String) {
        lifecycleScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val metadata = JSONObject(githubApi("https://api.github.com/repos/" + repo))
                    val branch = metadata.optString("default_branch", "main")
                    val tree = JSONObject(githubApi("https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1")).optJSONArray("tree") ?: JSONArray()
                    val candidates = mutableListOf<String>()
                    for (i in 0 until tree.length()) {
                        val item = tree.optJSONObject(i) ?: continue
                        if (item.optString("type") != "blob") continue
                        val p = item.optString("path")
                        val name = p.substringAfterLast("/")
                        if (name.equals("configs.json", true) || name.equals("config.json", true)) candidates.add(p)
                    }
                    candidates.sortedWith(compareBy<String>({ if (it == "configs.json") 0 else if (it == "config.json") 1 else 2 }, { it.count { c -> c == '/' } }, { it })).firstOrNull()
                        ?: throw Exception("No configs.json or config.json found.")
                }
                val metadata = withContext(Dispatchers.IO) { JSONObject(githubApi("https://api.github.com/repos/" + repo)) }
                val branch = metadata.optString("default_branch", "main")
                prefs.edit().putString("admin_config_path", path)
                    .putString("admin_config_discovery_attempted_repo", repo)
                    .putString("github_json_url", "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path).apply()
                adminConfigPath.setText(path)
                githubJsonUrl.setText("https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path)
                say("Found config file: " + path + " (branch: " + branch + ")")
            } catch (e: Exception) { say("Could not find config file: " + (e.message ?: "unknown error")) }
        }
    }

    private fun repairSelectedConfigFile() {
        val path = adminConfigPath.text.toString().trim()
        if (path.isBlank()) { say("Select a repository and config file first."); return }
        prefs.edit().putString("admin_config_path", path).apply()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val file = githubRequest(path, "GET")
                    val raw = String(Base64.decode(file.optString("content").replace("\n", "").replace("\r", ""), Base64.DEFAULT), Charsets.UTF_8)
                    val sha = file.getString("sha")
                    val strictPattern = Regex("(?i)^(vmess|vless|trojan|ss|hysteria2|hy2|tuic)://\\S+$")
                    try {
                        val parsed = JSONArray(raw)
                        val cleaned = JSONArray()
                        val seen = linkedSetOf<String>()
                        for (i in 0 until parsed.length()) {
                            val value = parsed.optString(i).trim()
                            if (strictPattern.matches(value) && seen.add(value)) cleaned.put(value)
                        }
                        if (cleaned.length() == parsed.length() && raw.trim() == cleaned.toString(2)) {
                            return@withContext "JSON is valid; " + cleaned.length() + " configs found."
                        }
                        runOnUiThread {
                            AlertDialog.Builder(this@TelegramSettingsActivity)
                                .setTitle("Normalize config JSON?")
                                .setMessage("Found " + cleaned.length() + " valid unique links. Remove invalid/duplicate entries and format the file?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Repair") { _, _ ->
                                    lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { githubWriteText(path, cleaned.toString(2) + "\n", sha, "Normalize config JSON from Android admin") }
                                            say("Config JSON normalized: " + cleaned.length() + " valid configs.")
                                        } catch (e: Exception) { say("Save failed: " + (e.message ?: "unknown error")) }
                                    }
                                }.show()
                        }
                        "Valid JSON checked. Confirm the proposed cleanup in the dialog."
                    } catch (_: Exception) {
                        val loose = Regex("(?i)(vmess|vless|trojan|ss|hysteria2|hy2|tuic)://[^\\s<>\\\"']+")
                        val found = loose.findAll(raw).map { it.value.trim().trimEnd(',', '.', ';', ')', ']') }.distinct().toList()
                        if (found.isEmpty()) throw Exception("Malformed JSON; no recognizable config links recovered. File left unchanged.")
                        val repaired = JSONArray()
                        found.forEach { repaired.put(it) }
                        runOnUiThread {
                            AlertDialog.Builder(this@TelegramSettingsActivity)
                                .setTitle("Repair malformed config JSON?")
                                .setMessage("JSON parsing failed. Recovered " + found.size + " possible links. Replace the file with a clean JSON array?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Repair file") { _, _ ->
                                    lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { githubWriteText(path, repaired.toString(2) + "\n", sha, "Repair malformed config JSON from Android admin") }
                                            say("Repaired config JSON; saved " + found.size + " links.")
                                        } catch (e: Exception) { say("Save failed: " + (e.message ?: "unknown error")) }
                                    }
                                }.show()
                        }
                        "Recovered " + found.size + " candidates. Confirm before saving."
                    }
                }
                say(result)
            } catch (e: Exception) { say("Config check/repair failed: " + (e.message ?: "unknown error")) }
        }
    }

    private fun githubReadText(path: String): Pair<String, String> {
        val json = githubRequest(path, "GET")
        val content = String(Base64.decode(json.getString("content").replace("\n", "").replace("\r", ""), Base64.DEFAULT), Charsets.UTF_8)
        return content to json.getString("sha")
    }

    private fun githubWriteText(path: String, content: String, sha: String?, message: String) {
        val body = JSONObject().put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        githubRequest(path, "PUT", body)
    }

    private fun syncChannelsToGitHub(removedChannel: String? = null) {
        if (githubTokenValue().isBlank()) { say(t("channels_saved_local")); return }
        val desired = channels.joinToString("\n", postfix = if (channels.isNotEmpty()) "\n" else "")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val (old, sha) = githubReadText("telegram_channels.txt")
                    val comments = old.lines().filter { it.trim().startsWith("#") || it.isBlank() }
                    val remoteChannels = old.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                        .filterNot { removedChannel != null && it.removePrefix("@").equals(removedChannel.removePrefix("@"), true) }
                    val mergedChannels = (remoteChannels + desired.lines().filter { it.isNotBlank() }).distinctBy { it.removePrefix("@").lowercase() }
                    val merged = (comments + mergedChannels).distinct().joinToString("\n").trimEnd() + "\n"
                    if (old == merged) return@withContext "کانال‌ها از قبل همگام بودند"
                    githubWriteText("telegram_channels.txt", merged, sha, "Update Telegram channels from Android")
                    "کانال‌ها در GitHub همگام شدند ✅"
                }
                say(result)
            } catch (e: Exception) { say(tf("error", e.message ?: "channel_sync_failed")) }
        }
    }

    private fun cleanupFailedConfigs(failedGuids: List<String>) {
        val failed = failedGuids.distinct()
        val failedUris = failed.mapNotNull { githubConfigByGuid[it] }.toSet()
        lifecycleScope.launch {
            var remoteRemoved = 0
            var remoteError: String? = null
            if (githubTokenValue().isNotBlank() && failedUris.isNotEmpty()) {
                try {
                    remoteRemoved = withContext(Dispatchers.IO) {
                        val (old, sha) = githubReadText("configs.json")
                        val values = try {
                            val arr = JSONArray(old)
                            (0 until arr.length()).map { arr.optString(it).trim() }
                        } catch (_: Exception) {
                            val obj = JSONObject(old)
                            val arr = obj.optJSONArray("configs") ?: obj.optJSONArray("proxies")
                                ?: obj.optJSONArray("data") ?: throw Exception("configs.json ساختار شناخته‌شده‌ای ندارد")
                            (0 until arr.length()).map { arr.optString(it).trim() }
                        }
                        val kept = JSONArray()
                        var removed = 0
                        values.forEach { value -> if (value in failedUris) removed++ else kept.put(value) }
                        if (removed > 0) githubWriteText("configs.json", kept.toString(2) + "\n", sha, "Remove failed configs after mobile ping test")
                        removed
                    }
                } catch (e: Exception) { remoteError = e.message ?: "GitHub sync failed" }
            }
            failed.forEach { MmkvManager.removeServer(it) }
            githubImportedGuids.removeAll(failed.toSet())
            failed.forEach { githubConfigByGuid.remove(it) }
            prefs.edit()
                .putString("github_imported_guids", githubImportedGuids.joinToString("\n"))
                .putString("github_config_by_guid", JSONObject(githubConfigByGuid as Map<*, *>).toString())
                .apply()
            say(if (remoteError != null) {
                "کانفیگ‌های ناموفق از گوشی حذف شدند؛ همگام‌سازی GitHub ناموفق بود: " + remoteError
            } else if (githubTokenValue().isBlank() || failedUris.isEmpty()) {
                "تست تمام شد؛ " + failed.size + " کانفیگ ناموفق از گوشی حذف شد. برای حذف از GitHub توکن و نگاشت کانفیگ لازم است."
            } else {
                "تست تمام شد؛ " + failed.size + " کانفیگ ناموفق از گوشی و " + remoteRemoved + " کانفیگ از configs.json حذف شد."
            })
        }
    }

    /** Import the config list through v2rayNG's own parser and profile storage. */
    private fun importIntoV2ray(text: String): Pair<Int, Int> =
        AngConfigManager.importBatchConfig(text, "", true)
}

private val STR: Map<String, Map<String, String>> = mapOf(
    "fa" to mapOf(
        "home_title" to "روش دریافت کانفیگ را انتخاب کن",
        "home_description" to "در GitHub کانفیگ‌ها از مخزن گرفته می‌شوند و نیازی به ورود تلگرام نیست. در TelAccount با حساب خودت وارد می‌شوی.",
        "back_home" to "بازگشت به انتخاب روش",
        "check_update" to "بررسی و نصب آپدیت برنامه",
        "update_checking" to "در حال بررسی نسخه جدید...",
        "update_none" to "نسخه جدیدی پیدا نشد یا APK در Release موجود نیست.",
        "update_downloading" to "نسخه جدید پیدا شد؛ در حال دانلود...",
        "update_install" to "دانلود تمام شد؛ نصب را از پنجره اندروید تأیید کن.",
        "update_unknown_sources" to "برای نصب آپدیت، اجازه نصب برنامه از این منبع را فعال کن و دوباره بزن.",
        "update_error" to "آپدیت ناموفق بود: %s",
        "tg_title" to "حساب تلگرام",
        "connected" to "وصل شده ✅",
        "connecting" to "در حال اتصال...",
        "logout" to "خروج از حساب",
        "phone_hint" to "شماره با کد کشور (+98...)",
        "send_code" to "۱) ارسال کد به تلگرام",
        "code_hint" to "کد تلگرام",
        "pw_hint" to "رمز دو مرحله‌ای (اگه داری)",
        "login" to "۲) ورود",
        "worker_title" to "ورکر کلادفلر (بدون VPN، فقط چنل‌های عمومی)",
        "worker_hint" to "آدرس ورکر (https://xxx.workers.dev/?key=...)",
        "worker_save" to "ذخیره ورکر",
        "saved" to "ذخیره شد ✅",
        "channels_title" to "چنل‌ها",
        "channel_hint" to "@channel یا لینک t.me",
        "add_channel" to "افزودن چنل",
        "no_channels" to "هنوز چنلی اضافه نکردی",
        "remove" to "حذف",
        "github_title" to "کانفیگ‌های GitHub",
        "github_description" to "توکن GitHub با دسترسی Contents: Read/Write وارد کن. کانال‌ها و کانفیگ‌های تست‌شده با مخزن همگام می‌شوند.",
        "github_token_hint" to "GitHub token (Contents read/write)",
        "channels_saved_local" to "کانال‌ها ذخیره شدند؛ برای همگام‌سازی با GitHub توکن وارد کن.",
        "github_import_progress" to "در حال واردکردن کانفیگ %d از %d ...",
        "github_cleanup_title" to "پاک‌سازی GitHub",
        "github_cleanup_confirm" to "%d کانفیگ موفق و %d ناموفق شدند. آیا کانفیگ‌های ناموفق از configs.json گیت‌هاب حذف شوند؟",
        "github_cleanup_yes" to "حذف ناموفق‌ها از GitHub",
        "cleanup_cancelled" to "پاک‌سازی لغو شد؛ چیزی از GitHub حذف نشد.",
        "no_github_configs_to_remove" to "کانفیگ ناموفق به کانفیگ‌های GitHub نگاشت نشد؛ برای ایمنی چیزی حذف نشد.",
        "github_cleanup_done" to "تمام شد: %d پروفایل ناموفق از گوشی حذف و %d کانفیگ از GitHub پاک شد.",
        "github_url_hint" to "آدرس JSON گیت‌هاب",
        "github_import_btn" to "دریافت کانفیگ‌ها از GitHub",
        "github_url_invalid" to "آدرس باید با https:// شروع شود",
        "github_loading" to "در حال دریافت configs.json ...",
        "github_empty" to "کانفیگ معتبری در JSON پیدا نشد",
        "github_imported" to "%d کانفیگ شناسایی و %d مورد به برنامه اضافه شد ✅",
        "test_real_delays" to "Test real delays",
        "test_confirm_message" to "همه کانفیگ‌های تازه‌واردشده از GitHub تست می‌شوند. کانفیگ‌هایی که تست واقعی‌شان شکست بخورد حذف خواهند شد.",
        "cancel" to "لغو",
        "test_start" to "شروع تست",
        "test_import_first" to "اول کانفیگ‌ها را از GitHub وارد کن",
        "test_started" to "در حال تست واقعی %d کانفیگ...",
        "test_progress" to "تست ادامه دارد؛ %d کانفیگ موفق از %d بررسی‌شده",
        "test_finished" to "تست تمام شد: %d کانفیگ سالم نگه داشته شد؛ %d کانفیگ ناموفق حذف شد.",
        "import_title" to "ایمپورت تلگرام",
        "range_label" to "بازه: %s",
        "range0" to "امروز",
        "range1" to "۲۴ ساعت اخیر",
        "range2" to "۳ روز اخیر",
        "import_btn" to "ایمپورت کانفیگ‌ها",
        "backup_title" to "پشتیبان حساب تلگرام",
        "backup_warning" to "فایل پشتیبان رمزگذاری می‌شود؛ رمز را فراموش نکن و فایل را محرمانه نگه دار.",
        "backup_password" to "رمز پشتیبان (حداقل ۱۰ کاراکتر)",
        "backup_password_short" to "برای پشتیبان رمز حداقل ۱۰ کاراکتری وارد کن",
        "backup_export" to "Export حساب و نشست",
        "backup_import" to "Import حساب و نشست",
        "backup_exported" to "پشتیبان رمزگذاری‌شده ذخیره شد ✅",
        "backup_imported" to "پشتیبان بازیابی شد؛ وضعیت اتصال را بررسی کن",
        "fill_api" to "API ID و API Hash رو پر کن",
        "sending" to "در حال ارسال...",
        "already" to "قبلاً وصل شدی",
        "code_sent" to "کد ارسال شد؛ داخل تلگرام ببین",
        "logging_in" to "در حال ورود...",
        "need_pw" to "رمز دو مرحله‌ای رو بنویس و دوباره ورود بزن",
        "logged_in" to "وصل شدی ✅",
        "error" to "خطا: %s",
        "add_first" to "اول یه چنل اضافه کن",
        "need_login_or_worker" to "یا لاگین کن یا آدرس ورکر بذار",
        "reading" to "در حال خوندن @%s ...",
        "ch_result" to "@%s: %d کانفیگ",
        "ch_result_diag" to "@%s: %d کانفیگ (%d پست اسکن شد، %d پست توی بازه)",
        "ch_error" to "خطا در @%s: %s",
        "none_found" to "کانفیگی پیدا نشد. بازه رو بزرگ‌تر کن (۲۴ ساعت یا ۳ روز) و دوباره امتحان کن.",
        "imported" to "%d کانفیگ ایمپورت شد ✅ برگرد صفحه اصلی v2rayNG و پینگ بگیر",
        "import_error" to "خطا در ایمپورت: %s",
        "err_not_json" to "جواب ورکر درست نیست؛ کد جدید worker.js رو توی ورکرت بذار و Deploy بزن",
        "err_no_preview" to "این چنل پیش‌نمایش عمومی نداره (فقط با حساب تلگرام کار می‌کنه)",
        "err_key" to "رمز (key) آدرس ورکر با رمز توی کد ورکر یکی نیست"
    ),
    "en" to mapOf(
        "home_title" to "Choose how to get configs",
        "home_description" to "GitHub mode imports from the repository without logging into Telegram. TelAccount mode uses your own Telegram account.",
        "back_home" to "Back to method selection",
        "check_update" to "Check for and install app update",
        "update_checking" to "Checking for a new version...",
        "update_none" to "No new release found or no APK asset is available.",
        "update_downloading" to "A new version was found; downloading...",
        "update_install" to "Download complete. Confirm installation in Android.",
        "update_unknown_sources" to "Allow installs from this source, then tap update again.",
        "update_error" to "Update failed: %s",
        "tg_title" to "Telegram account",
        "connected" to "Connected ✅",
        "connecting" to "Connecting...",
        "logout" to "Log out",
        "phone_hint" to "Phone with country code (+98...)",
        "send_code" to "1) Send code to Telegram",
        "code_hint" to "Telegram code",
        "pw_hint" to "2-step password (if any)",
        "login" to "2) Log in",
        "worker_title" to "Cloudflare Worker (no VPN, public channels only)",
        "worker_hint" to "Worker URL (https://xxx.workers.dev/?key=...)",
        "worker_save" to "Save worker",
        "saved" to "Saved ✅",
        "channels_title" to "Channels",
        "channel_hint" to "@channel or t.me link",
        "add_channel" to "Add channel",
        "no_channels" to "No channels yet",
        "remove" to "Remove",
        "github_title" to "GitHub configs",
        "github_description" to "Enter a GitHub token with Contents read/write access. Channels and tested configs sync with the repository.",
        "github_token_hint" to "GitHub token (Contents read/write)",
        "channels_saved_local" to "Channels saved locally. Enter a GitHub token to sync them.",
        "github_import_progress" to "Importing config %d of %d...",
        "github_cleanup_title" to "GitHub cleanup",
        "github_cleanup_confirm" to "%d configs succeeded and %d failed. Remove failed configs from GitHub configs.json?",
        "github_cleanup_yes" to "Remove failed from GitHub",
        "cleanup_cancelled" to "Cleanup cancelled; nothing was removed from GitHub.",
        "no_github_configs_to_remove" to "Failed profiles could not be mapped to GitHub config entries; nothing was deleted for safety.",
        "github_cleanup_done" to "Done: removed %d failed profiles locally and %d configs from GitHub.",
        "github_url_hint" to "GitHub JSON URL",
        "github_import_btn" to "Import configs from GitHub",
        "github_url_invalid" to "URL must start with https://",
        "github_loading" to "Downloading configs.json...",
        "github_empty" to "No valid configs found in JSON",
        "github_imported" to "%d configs parsed; %d new profiles added ✅",
        "test_real_delays" to "Test real delays",
        "test_confirm_message" to "All configs just imported from GitHub will be tested. Imports that fail the real delay test will be removed.",
        "cancel" to "Cancel",
        "test_start" to "Start test",
        "test_import_first" to "Import configs from GitHub first",
        "test_started" to "Testing %d configs with real delay...",
        "test_progress" to "Test running; %d configs succeeded out of %d checked",
        "test_finished" to "Finished: kept %d working configs; removed %d failed configs.",
        "import_title" to "Telegram import",
        "range_label" to "Range: %s",
        "range0" to "Today",
        "range1" to "Last 24 hours",
        "range2" to "Last 3 days",
        "import_btn" to "Import configs",
        "backup_title" to "Telegram account backup",
        "backup_warning" to "Backup is encrypted. Keep the file private and do not forget its password.",
        "backup_password" to "Backup password (at least 10 characters)",
        "backup_password_short" to "Enter a backup password with at least 10 characters",
        "backup_export" to "Export account and session",
        "backup_import" to "Import account and session",
        "backup_exported" to "Encrypted backup saved ✅",
        "backup_imported" to "Backup restored; check connection status",
        "fill_api" to "Fill in API ID and API Hash",
        "sending" to "Sending...",
        "already" to "Already logged in",
        "code_sent" to "Code sent; check Telegram",
        "logging_in" to "Logging in...",
        "need_pw" to "Enter your 2-step password and tap Log in again",
        "logged_in" to "Logged in ✅",
        "error" to "Error: %s",
        "add_first" to "Add a channel first",
        "need_login_or_worker" to "Log in or set a worker URL",
        "reading" to "Reading @%s ...",
        "ch_result" to "@%s: %d configs",
        "ch_result_diag" to "@%s: %d configs (%d posts scanned, %d in range)",
        "ch_error" to "Error in @%s: %s",
        "none_found" to "No configs found. Try a wider range (24h or 3 days).",
        "imported" to "Imported %d configs ✅ Go back to the v2rayNG main screen and test ping",
        "import_error" to "Import error: %s",
        "err_not_json" to "Worker reply is not valid; paste the new worker.js into your Worker and Deploy",
        "err_no_preview" to "This channel has no public preview (works only with a Telegram account)",
        "err_key" to "The key in the Worker URL doesn't match the key in the Worker code"
    )
)
