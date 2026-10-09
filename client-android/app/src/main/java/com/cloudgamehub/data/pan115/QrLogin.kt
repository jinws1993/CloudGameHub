package com.cloudgamehub.data.pan115

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 115 扫码登录。
 *
 * 流程 (115 网页端的公开协议):
 * 1. `GET https://qrcodeapi.115.com/api/1.0/web/1.0/token` 拿到 uid/time/sign
 * 2. 把 `https://115.com/scan/dg-<uid>` 渲染成二维码, 用户用 115 App 扫
 * 3. 轮询 `GET https://qrcodeapi.115.com/get/status/?uid=&time=&sign=`
 *    - `code 90038` = 还没扫
 *    - `code 90039` = 扫了还没在手机上确认
 *    - `code 0`     = 确认成功, `data.cookies` 是真正的 cookie 串
 *
 * 两种登录方式并存: 扫码体验好但要多一步, Cookie 粘贴适合已经有 cookie 的人。
 */
@Singleton
class QrLoginService @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    enum class Status { WAITING, SCANNED, CONFIRMED, EXPIRED, FAILED }

    data class QrSession(
        val uid: String,
        val time: String,
        val sign: String,
        val qrUrl: String,
    )

    /** 第一步: 申请一个二维码 */
    suspend fun createSession(): QrSession = withContext(Dispatchers.IO) {
        val body = client.newCall(
            Request.Builder().url(TOKEN_URL).header("User-Agent", Pan115Client.UA).build()
        ).execute().use { it.body?.string().orEmpty() }
        val j = JSONObject(body)
        if (!j.optBoolean("state", false)) {
            throw Pan115Exception("115 二维码申请失败: ${j.optString("message")}")
        }
        val d = j.getJSONObject("data")
        val uid = d.optString("uid")
        QrSession(
            uid = uid,
            time = d.optString("time"),
            sign = d.optString("sign"),
            qrUrl = "https://115.com/scan/dg-$uid",
        )
    }

    /**
     * 第二步: 轮询状态。
     * @return 状态; [CONFIRMED] 时 [Poll.cookie] 是可用的 cookie 串
     */
    data class Poll(val status: Status, val cookie: String = "", val message: String = "")

    suspend fun poll(session: QrSession): Poll = withContext(Dispatchers.IO) {
        val url = "$STATUS_URL?uid=${session.uid}&time=${session.time}&sign=${session.sign}"
        val body = client.newCall(
            Request.Builder().url(url).header("User-Agent", Pan115Client.UA).build()
        ).execute().use { resp ->
            // 115 有时会把 cookie 直接塞在响应的 Set-Cookie 里
            val raw = resp.body?.string().orEmpty()
            val headerCookies = resp.headers.values("set-cookie")
                .mapNotNull { it.substringBefore(';').trim() }
                .filter { it.contains('=') }
            raw to headerCookies
        }
        val (raw, headerCookies) = body
        val j = try { JSONObject(raw) } catch (_: Exception) {
            return@withContext Poll(Status.FAILED, message = "返回格式不对: ${raw.take(120)}")
        }
        val code = j.optInt("code", -1)
        val data = j.optJSONObject("data")

        return@withContext when {
            code == QR_EXPIRED -> Poll(Status.EXPIRED, message = "二维码过期, 请刷新")
            code == 90038 -> Poll(Status.WAITING)
            code == 90039 -> Poll(Status.SCANNED)
            code == 0 -> {
                val cookies = data?.optString("cookies")
                    ?.takeIf { it.isNotBlank() && it.contains('=') }
                    ?: headerCookies.joinToString("; ")
                if (cookies.isBlank()) {
                    Poll(Status.FAILED, message = "确认成功但没拿到 cookie, 试试 Cookie 登录方式")
                } else {
                    Poll(Status.CONFIRMED, cookie = cookies)
                }
            }
            else -> Poll(
                Status.FAILED,
                message = j.optString("message").ifBlank { "code=$code ${raw.take(120)}" },
            )
        }
    }

    /** 轮询直到确认 / 过期, 每 2 秒问一次, 最多 3 分钟 */
    suspend fun awaitLogin(
        session: QrSession,
        onTick: (Status) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val p = poll(session)
            when (p.status) {
                Status.CONFIRMED -> return@withContext p.cookie
                Status.EXPIRED -> throw Pan115Exception(p.message.ifBlank { "二维码过期" })
                Status.FAILED -> throw Pan115Exception(p.message.ifBlank { "登录失败" })
                else -> onTick(p.status)
            }
            delay(2000)
        }
        throw Pan115Exception("扫码超时, 请重新获取二维码")
    }

    companion object {
        const val TOKEN_URL = "https://qrcodeapi.115.com/api/1.0/web/1.0/token"
        const val STATUS_URL = "https://qrcodeapi.115.com/get/status/"
        const val QR_EXPIRED = 10009
    }
}

/**
 * 把文本渲染成二维码位图。
 *
 * 之所以自己写而不是用现成 UI 库: ZXing core 就一个类的事, 拉整个 zxing-android
 * 依赖不划算。二维码内容是一段普通 URL, 用 0.7 的纠错等级就够 (115 App 扫码
 * 环境不一定理想)。
 */
object QrRenderer {

    fun render(text: String, sizePx: Int = 720): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    } catch (_: Exception) {
        null
    }
}
