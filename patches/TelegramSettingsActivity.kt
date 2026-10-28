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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class TelegramSettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("tg_import", Context.MODE_PRIVATE) }
    private val channels = mutableListOf<String>()
    private var lang = "fa"
    private var range = 0
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
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(111, 66, 193), 14)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        elevation = dp(2).toFloat()
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD); setPadding(0, dp(26), 0, dp(10))
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
    private val testRealDelaysBtn by lazy { btn { confirmAndTestGitHubConfigs() } }

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
        syncChannelsToGitHub()
    }

    private fun refreshChannels() {
        channelList.removeAllViews()
        if (channels.isEmpty()) channelList.addView(label(t("no_channels")))
        channels.toList().forEach { ch ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = "@$ch"; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(btn { channels.remove(ch); savePrefs(); refreshChannels(); syncChannelsToGitHub(ch) }
                .apply { text = t("remove") })
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
        rangeBtn.text = tf("range_label", t("range$range"))
        importBtn.text = t("import_btn")
        githubImportBtn.text = t("github_import_btn")
        testRealDelaysBtn.text = t("test_real_delays")
        backupPassword.hint = t("backup_password")
        exportBackupBtn.text = t("backup_export")
        importBackupBtn.text = t("backup_import")

        val st = TelegramManager.state.value
        val hasCreds = !prefs.getString("api_id", "").isNullOrEmpty() &&
                !prefs.getString("api_hash", "").isNullOrEmpty()

        root.addView(langBtn)

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

        root.addView(title(t("channels_title")))
        root.addView(newChannel)
        root.addView(addBtn)
        root.addView(channelList)
        refreshChannels()

        root.addView(title(t("github_title")))
        root.addView(label(t("github_description")))
        root.addView(githubJsonUrl)
        root.addView(githubToken)
        root.addView(githubImportBtn)
        root.addView(testRealDelaysBtn)

        root.addView(title(t("import_title")))
        root.addView(rangeBtn)
        root.addView(importBtn)

        root.addView(title(t("backup_title")))
        root.addView(label(t("backup_warning")))
        root.addView(backupPassword)
        root.addView(exportBackupBtn)
        root.addView(importBackupBtn)
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
        status = TextView(this).apply { setPadding(0, 24, 0, 24) }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(root) })

        lang = prefs.getString("lang", "fa") ?: "fa"
        range = prefs.getInt("range", 0)
        channels.addAll(prefs.getString("channels_v2", "")!!.lines().filter { it.isNotBlank() })
        githubImportedGuids.addAll((prefs.getString("github_imported_guids", "") ?: "").lines().filter { it.isNotBlank() })
        try { val saved = JSONObject(prefs.getString("github_config_by_guid", "{}") ?: "{}"); saved.keys().forEach { k -> githubConfigByGuid[k] = saved.optString(k) } } catch (_: Exception) {}

        // اگه قبلاً لاگین کردی خودکار وصل می‌شه
        val id = prefs.getString("api_id", "")?.toIntOrNull()
        val hash = prefs.getString("api_hash", "") ?: ""
        if (id != null && hash.isNotEmpty()) TelegramManager.start(this, id, hash)

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

    private fun importFromGitHub() {
        val source = githubJsonUrl.text.toString().trim()
        if (!source.startsWith("https://")) { say(t("github_url_invalid")); return }
        savePrefs()
        lifecycleScope.launch {
            say(t("github_loading"))
            try {
                val configs = withContext(Dispatchers.IO) {
                    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000
                        setRequestProperty("User-Agent", "heheconfig-android")
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        if (connection.responseCode !in 200..299) throw Exception("HTTP ${connection.responseCode}")
                        val array = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        val found = LinkedHashSet<String>()
                        val pattern = Regex("""(?i)^(vmess|vless|trojan|ss|hysteria2|hy2|tuic)://\\S+$""")
                        for (i in 0 until array.length()) { val value = array.optString(i).trim(); if (pattern.matches(value)) found.add(value) }
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

    private fun confirmAndTestGitHubConfigs() {
        val ids = githubImportedGuids.toList()
        if (ids.isEmpty()) { say(t("test_import_first")); return }
        AlertDialog.Builder(this)
            .setTitle(t("test_real_delays"))
            .setMessage(t("test_confirm_message"))
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(t("test_start")) { _, _ -> testGitHubConfigs(ids) }
            .show()
    }

    private fun testGitHubConfigs(ids: List<String>) {
        val targets = ids.distinct().filter { MmkvManager.decodeServerConfig(it) != null }
        if (targets.isEmpty()) { say(t("test_import_first")); return }
        val successful = ConcurrentHashMap.newKeySet<String>()
        say(tf("test_started", targets.size))
        try {
            RealPingWorkerService(this, targets) { event ->
                when (event) {
                    is RealPingEvent.Result -> if (event.delayMillis >= 0L) successful.add(event.guid)
                    is RealPingEvent.Progress -> runOnUiThread { say(tf("test_progress", successful.size, targets.size)) }
                    is RealPingEvent.Finish -> runOnUiThread {
                        val failed = targets.filterNot { successful.contains(it) }
                        if (failed.isEmpty()) { say(tf("test_finished", successful.size, 0)); return@runOnUiThread }
                        AlertDialog.Builder(this)
                            .setTitle(t("github_cleanup_title"))
                            .setMessage(tf("github_cleanup_confirm", successful.size, failed.size))
                            .setNegativeButton(t("cancel")) { _, _ -> say(t("cleanup_cancelled")) }
                            .setPositiveButton(t("github_cleanup_yes")) { _, _ -> publishWorkingConfigsToGitHub(failed) }
                            .show()
                    }
                }
            }.start()
        } catch (e: Exception) { say(tf("error", e.message ?: "test_failed")) }
    }

    private fun githubTokenValue(): String = (githubToken.text.toString().trim().ifEmpty { prefs.getString("github_token", "") ?: "" })

    private fun githubRequest(path: String, method: String, body: JSONObject? = null): JSONObject {
        val token = githubTokenValue()
        if (token.isBlank()) throw Exception("GitHub token را وارد کن")
        val url = URL("https://api.github.com/repos/kavirkobra69/heheconfig/contents/$path")
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

    private fun githubReadText(path: String): Pair<String, String> {
        val json = githubRequest(path, "GET")
        val content = String(Base64.decode(json.getString("content").replace("\n", "").replace("\r", ""), Base64.DEFAULT), Charsets.UTF_8)
        return content to json.getString("sha")
    }

    private fun githubWriteText(path: String, content: String, sha: String, message: String) {
        val body = JSONObject().put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("sha", sha).put("branch", "main")
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

    private fun publishWorkingConfigsToGitHub(failedGuids: List<String>) {
        val failedUris = failedGuids.mapNotNull { githubConfigByGuid[it] }.toSet()
        if (failedUris.isEmpty()) { say(t("no_github_configs_to_remove")); return }
        lifecycleScope.launch {
            try {
                val remaining = withContext(Dispatchers.IO) {
                    val (old, sha) = githubReadText("configs.json")
                    val array = JSONArray(old)
                    val kept = JSONArray()
                    var removed = 0
                    for (i in 0 until array.length()) {
                        val value = array.optString(i).trim()
                        if (value in failedUris) removed++ else kept.put(value)
                    }
                    if (removed > 0) githubWriteText("configs.json", kept.toString(2) + "\n", sha, "Remove failed configs after mobile ping test")
                    removed
                }
                failedGuids.forEach { MmkvManager.removeServer(it) }
                githubImportedGuids.removeAll(failedGuids.toSet())
                failedGuids.forEach { githubConfigByGuid.remove(it) }
                prefs.edit().putString("github_imported_guids", githubImportedGuids.joinToString("\n"))
                    .putString("github_config_by_guid", JSONObject(githubConfigByGuid as Map<*, *>).toString()).apply()
                say(tf("github_cleanup_done", failedGuids.size, remaining))
            } catch (e: Exception) { say(tf("error", e.message ?: "github_cleanup_failed")) }
        }
    }

    /** Import the config list through v2rayNG's own parser and profile storage. */
    private fun importIntoV2ray(text: String): Pair<Int, Int> =
        AngConfigManager.importBatchConfig(text, "", true)
}

private val STR: Map<String, Map<String, String>> = mapOf(
    "fa" to mapOf(
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
