package com.v2ray.ang.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** حالت‌ها: init, phone, code, password, ready, other */
object TelegramManager {
    val state = MutableStateFlow("init")

    /** scanned/inRange برای حالت حساب تلگرام -1 هستن */
    data class FetchResult(val configs: List<String>, val scanned: Int, val inRange: Int)

    private var client: Client? = null
    private var apiId = 0
    private var apiHash = ""
    private var dir = ""

    private val LINK = Regex("""(?:vmess|vless|trojan|ss|hysteria2|hy2|tuic)://[^\s<>"'`]+""")

    /** شروع بازه (ثانیه یونیکس): 0 = امروز، 1 = ۲۴ ساعت اخیر، 2 = ۳ روز اخیر */
    fun sinceFor(range: Int): Long {
        val now = System.currentTimeMillis() / 1000
        return when (range) {
            1 -> now - 86_400
            2 -> now - 3 * 86_400
            else -> {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                c.timeInMillis / 1000
            }
        }
    }

    fun start(ctx: Context, apiId: Int, apiHash: String) {
        if (client != null) return
        this.apiId = apiId
        this.apiHash = apiHash
        dir = ctx.filesDir.absolutePath + "/tdlib"
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create(Client.ResultHandler { obj ->
            if (obj is TdApi.UpdateAuthorizationState) onAuth(obj.authorizationState)
        }, null, null)
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = TdApi.SetTdlibParameters()
                p.databaseDirectory = dir
                p.databaseEncryptionKey = ByteArray(0)
                p.useMessageDatabase = true
                p.useChatInfoDatabase = true
                p.useFileDatabase = false
                p.useSecretChats = false
                p.apiId = apiId
                p.apiHash = apiHash
                p.systemLanguageCode = "en"
                p.deviceModel = "Android"
                p.systemVersion = "1.0"
                p.applicationVersion = "1.0"
                client?.send(p, null)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> state.value = "phone"
            is TdApi.AuthorizationStateWaitCode -> state.value = "code"
            is TdApi.AuthorizationStateWaitPassword -> state.value = "password"
            is TdApi.AuthorizationStateReady -> state.value = "ready"
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing -> state.value = "closing"
            is TdApi.AuthorizationStateClosed -> state.value = "closed"
            else -> state.value = "other"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client!!.send(f) { r ->
                if (r is TdApi.Error) cont.resumeWithException(Exception(r.message))
                else cont.resume(r as T)
            }
        }

    private suspend fun await(vararg states: String): String =
        withTimeout(25_000) { state.first { it in states } }

    suspend fun sendPhone(phone: String) {
        if (await("phone", "ready", "code", "password") == "phone")
            call(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    }

    /** برمی‌گردونه "ready" یا "password" */
    suspend fun sendCode(code: String): String {
        call(TdApi.CheckAuthenticationCode(code.trim()))
        return await("ready", "password")
    }

    suspend fun sendPassword(pw: String) {
        call(TdApi.CheckAuthenticationPassword(pw))
        await("ready")
    }

    suspend fun logout() {
        try { call(TdApi.LogOut()) } catch (_: Exception) { }
        delay(1500)
        client = null
        state.value = "init"
    }

    /** Close TDLib before replacing its on-disk database during backup restore. */
    suspend fun closeClient() {
        val active = client ?: return
        try {
            active.send(TdApi.Close(), null)
            withTimeout(8_000) { state.first { it == "closed" } }
        } catch (_: Exception) {
            // Continue cleanup; the app will recreate the client after restore.
        } finally {
            client = null
            state.value = "init"
        }
    }

    private fun textOf(m: TdApi.Message): String = when (val c = m.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessageAnimation -> c.caption.text
        else -> ""
    }

    /** حالت حساب تلگرام (نیاز به دسترسی به تلگرام) */
    suspend fun fetchToday(username: String, sinceSec: Int): FetchResult {
        val chat = call(TdApi.SearchPublicChat(username.trim().removePrefix("@")))
        val out = LinkedHashSet<String>()
        var from = 0L
        var empties = 0
        var pages = 0
        var stop = false
        while (!stop && pages++ < 30) {
            val msgs = call(TdApi.GetChatHistory(chat.id, from, 0, 100, false)).messages
            if (msgs.isEmpty()) {
                if (++empties >= 3) break
                delay(500); continue
            }
            for (m in msgs) {
                if (m.date < sinceSec) { stop = true; break }
                LINK.findAll(textOf(m)).forEach { out.add(it.value.trimEnd('.', ',', ';', ')')) }
            }
            from = msgs.last().id
        }
        return FetchResult(out.toList(), -1, -1)
    }

    /** حالت ورکر کلادفلر (فقط چنل عمومی؛ ورکر خودش کانفیگ‌ها رو درمیاره و JSON می‌ده) */
    suspend fun fetchViaWorker(base: String, channel: String, sinceSec: Long): FetchResult =
        withContext(Dispatchers.IO) {
            val name = channel.trim().removePrefix("@")
            val sep = if (base.contains("?")) "&" else "?"
            val url = base + sep + "channel=" + URLEncoder.encode(name, "UTF-8") + "&since=" + sinceSec
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 20000
            con.readTimeout = 60000
            val code = con.responseCode
            val body = (if (code in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 403) throw Exception("forbidden")
            if (code != 200) throw Exception("Worker HTTP $code")
            val j = try { JSONObject(body) } catch (e: Exception) { throw Exception("NOT_JSON") }
            if (!j.optBoolean("ok", false)) throw Exception(j.optString("error", "worker_error"))
            val arr = j.getJSONArray("configs")
            val list = (0 until arr.length()).map { arr.getString(it) }
            FetchResult(list, j.optInt("posts_scanned"), j.optInt("posts_in_range"))
        }
}
