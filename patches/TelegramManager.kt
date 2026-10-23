package com.v2ray.ang.ui

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Authorization states: init, phone, code, password, ready, other. */
object TelegramManager {
    val state = MutableStateFlow("init")

    private var client: Client? = null
    private var apiId = 0
    private var apiHash = ""
    private var dir = ""

    private val LINK = Regex("""(?:vmess|vless|trojan|ss|hysteria2|hy2|tuic)://[^\s<>"'`]+""")

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

    /** Returns "ready" or "password". */
    suspend fun sendCode(code: String): String {
        call(TdApi.CheckAuthenticationCode(code.trim()))
        return await("ready", "password")
    }

    suspend fun sendPassword(pw: String) {
        call(TdApi.CheckAuthenticationPassword(pw))
        await("ready")
    }

    private fun textOf(m: TdApi.Message): String = when (val c = m.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessageAnimation -> c.caption.text
        else -> ""
    }

    private fun startOfTodaySec(): Int {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return (c.timeInMillis / 1000).toInt()
    }

    suspend fun fetchToday(username: String): List<String> {
        val chat = call(TdApi.SearchPublicChat(username.trim().removePrefix("@")))
        val since = startOfTodaySec()
        val out = LinkedHashSet<String>()
        var from = 0L
        var empties = 0
        var pages = 0
        var stop = false
        while (!stop && pages++ < 30) {
            val msgs = call(TdApi.GetChatHistory(chat.id, from, 0, 100, false)).messages
            if (msgs.isEmpty()) {
                if (++empties >= 3) break
                delay(500)
                continue
            }
            for (m in msgs) {
                if (m.date < since) {
                    stop = true
                    break
                }
                LINK.findAll(textOf(m)).forEach { out.add(it.value.trimEnd('.', ',', ';', ')')) }
            }
            from = msgs.last().id
        }
        return out.toList()
    }
}
