package com.cloudgamehub.data.pan115

import android.util.Log
import com.cloudgamehub.data.prefs.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 115 目录里的一项 (文件或目录) */
data class Pan115Item(
    val cid: String,          // 目录 cid, 文件项为 ""
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val pickcode: String = "",   // 文件才有, 下载凭据
    val sha1: String = "",
)

/** 115 直链下载需要的两样东西 */
data class DirectLink(
    val url: String,
    val headers: Map<String, String>,
)

class Pan115Exception(msg: String) : IOException(msg)

/**
 * 115 网盘客户端。**这是整个 App 的地基** —— ROM 全在 115 上, 这里负责
 * 登录、列目录、拿下载直链。
 *
 * ## 两个必须记住的坑
 *
 * 1. **cid 全程用字符串**。115 的 cid 是 64 位的, 任何一步转成浮点都会丢精度,
 *    然后列目录就变成"服务器开小差"。这里从解析到拼接全程 String。
 *
 * 2. **CDN 直链要自己带 cookie**。`files/download` 返回的 `file_url` 指向的是
 *    另一个域名的 CDN, OkHttp 的 cookie jar 因为域名不匹配**不会**自动带上
 *    session cookie, 裸 GET 会 403 (`ua not match cookie`)。所以下载时
 *    显式把 jar 拼成 Cookie 头。另外 115 对 Chrome UA 特别敏感, 反而会 403,
 *    这里固定用一个普通 UA。
 *
 * ## 115 WebAPI 小抄
 *
 * | 用途 | 接口 |
 * |------|------|
 * | 验 cookie | `GET /files?aid=1&cid=0&limit=1&show_dir=1&format=json` |
 * | 列目录 | `GET /files?aid=1&cid=..&limit=..&offset=..&show_dir=1&o=user_ptime&asc=1&fc_mix=0&natsort=1&format=json` |
 * | 取路径 | `GET /files/category?cid=..` |
 * | 下载直链 | `GET /files/download?pickcode=..&_=1` |
 * | 文件信息 | `GET /files/file?pickcode=..` |
 *
 * ⚠️ 列表接口必须是 `/files` 而不是 `/files/list`, 参数也不能少, 否则报
 * "服务器开小差" (这个坑我踩过)。
 */
@Singleton
class Pan115Client @Inject constructor(
    private val prefs: PrefsStore,
) {
    private val TAG = "Pan115"

    companion object {
        const val API_BASE = "https://webapi.115.com"
        const val FILES_BASE = "https://webapi.115.com/files"

        /**
         * UA 有讲究: 不能用 Chrome UA (115 CDN 会返 403 "ua not match cookie"),
         * 也不能太像机器人。普通桌面浏览器 UA 最稳。
         */
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 下载 CDN 时用的 UA, 跟 webapi 的故意不一样 */
        const val CDN_UA = "CloudGameHub/1.3.0 (Android)"
    }

    /** 内存 cookie jar: 只在进程存活期间需要, 不落盘 (cookie 本身存在 DataStore) */
    private val jar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 下载用: 大文件 + 长超时 + Range 支持 */
    val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    // ================= cookie 管理 =================

    /** 载入保存的 cookie (App 启动时调) */
    fun restore() {
        val raw = prefs.cached115Cookie() ?: return
        applyCookieString(raw)
    }

    /** 解析并注入 "UID=..; CID=..; SEID=..; KID=.." 格式的 cookie 串 */
    fun applyCookieString(raw: String) {
        jar.clear()
        raw.split(Regex("[;\n]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=').trim()
                if (name.isNotBlank()) {
                    jar.put(name, value, ".115.com")
                }
            }
        Log.i(TAG, "cookie applied: ${jar.names().size} entries")
    }

    /** 当前 jar 压成 Cookie 头 */
    fun cookieHeader(): String = jar.asHeader()

    fun hasCookie(): Boolean = jar.names().contains("SEID")

    // ================= 调用 =================

    private fun get(url: String, client: OkHttpClient = this.client): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Pan115Exception("HTTP ${resp.code}: ${body.take(200)}")
                body
            }

    private suspend fun getAsync(url: String): String = withContext(Dispatchers.IO) { get(url) }

    private fun filesUrl(cid: String, limit: Int, offset: Int = 0): String =
        "$API_BASE/files?aid=1&cid=$cid&limit=$limit&offset=$offset" +
            "&show_dir=1&o=user_ptime&asc=1&fc_mix=0&natsort=1&format=json"

    // ================= 对外 API =================

    /** 验证 cookie 是否还有效 */
    suspend fun checkLogin(): String = withContext(Dispatchers.IO) {
        val body = get(filesUrl("0", 1))
        val j = JSONObject(body)
        if (!j.optBoolean("state", false)) {
            val msg = j.optString("message").ifBlank { j.optString("error") }.ifBlank { "未知错误" }
            throw Pan115Exception("115 登录失效: $msg")
        }
        val root = j.optJSONArray("data")
        "已连接, 根目录 ${root?.length() ?: 0} 项"
    }

    /**
     * 列目录。`cid = "0"` 是根目录。
     *
     * 解析刻意避开 `getLong` —— cid 必须是字符串, 任何数值转换都可能丢精度。
     */
    suspend fun listDir(cid: String, limit: Int = 200, offset: Int = 0): List<Pan115Item> =
        withContext(Dispatchers.IO) {
            val body = get(filesUrl(cid, limit, offset))
            val j = JSONObject(body)
            if (!j.optBoolean("state", false)) {
                val msg = j.optString("message").ifBlank { j.optString("error") }
                    .ifBlank { "服务器开小差" }
                throw Pan115Exception("列目录失败(cid=$cid): $msg")
            }
            val arr = j.optJSONArray("data") ?: return@withContext emptyList()
            val out = ArrayList<Pan115Item>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val name = it.optString("n")
                if (name.isBlank()) continue
                // py115 的判定: 有 fid = 文件, 无 fid = 目录
                val isDir = !it.has("fid")
                out += Pan115Item(
                    cid = if (isDir) it.optString("cid") else "",
                    name = name,
                    isDir = isDir,
                    size = it.optString("s").toLongOrNull() ?: 0L,
                    pickcode = it.optString("pc"),
                    sha1 = it.optString("sha").lowercase(),
                )
            }
            out
        }

    /**
     * 递归列出目录下所有文件。
     *
     * @param exts      只要这些扩展名 (不含点, 小写); null = 全要
     * @param maxDepth  目录深度上限, 防止用户误选了整个网盘把手机卡死
     * @param onProgress 每层目录回调一次, 拿来更新 UI
     */
    suspend fun walkFiles(
        rootCid: String,
        exts: Set<String>? = null,
        maxDepth: Int = 6,
        onProgress: ((filesFound: Int, currentPath: String) -> Unit)? = null,
    ): List<Pan115Item> = withContext(Dispatchers.IO) {
        val files = ArrayList<Pan115Item>()
        // 显式栈 (cid, path, depth), 避免深目录递归爆栈
        val stack = ArrayDeque<Triple<String, String, Int>>()
        stack.addLast(Triple(rootCid, "", 0))
        var guard = 0
        while (stack.isNotEmpty()) {
            if (++guard > 20000) break   // 安全阀, 防止异常网盘结构把 App 卡住
            val (cid, path, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            val items = runCatching { listDir(cid, 200) }.getOrElse { e ->
                Log.w(TAG, "skip cid=$cid: ${e.message}")
                emptyList()
            }
            for (item in items) {
                val full = if (path.isBlank()) item.name else "$path/${item.name}"
                if (item.isDir) {
                    if (item.cid.isNotBlank()) stack.addLast(Triple(item.cid, full, depth + 1))
                } else {
                    if (exts == null || extOf(item.name) in exts) files += item
                }
            }
            onProgress?.invoke(files.size, path.ifBlank { "根目录" })
        }
        files
    }

    /** 取 cid 的祖先路径, 给面包屑用 */
    suspend fun pathOf(cid: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (cid == "0") return@withContext listOf("0" to "根目录")
        runCatching {
            val j = JSONObject(get("$API_BASE/files/category?cid=$cid"))
            if (j.optBoolean("state", false)) {
                val paths = j.optJSONObject("data")?.optJSONArray("paths")
                val out = ArrayList<Pair<String, String>>()
                if (paths != null) {
                    for (i in 0 until paths.length()) {
                        val p = paths.optJSONObject(i) ?: continue
                        out += p.optString("cid") to p.optString("name")
                    }
                }
                out + (cid to "当前目录")
            } else emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * 拿 ROM 的下载直链 (115 的 302 目标)。
     *
     * 调完 `files/download` 之后, jar 里就带上了本次的 session cookie;
     * [headers] 把它们显式交给调用方, 由手机自己 GET CDN —— 流量不经过任何中间人。
     */
    suspend fun directLink(pickcode: String): DirectLink = withContext(Dispatchers.IO) {
        if (pickcode.isBlank()) throw Pan115Exception("缺少 pickcode, 这个文件可能不支持直接下载")
        val body = get("$FILES_BASE/download?pickcode=$pickcode&_=1")
        val j = JSONObject(body)
        if (!j.optBoolean("state", false)) {
            val msg = j.optString("message").ifBlank { j.optString("error") }.ifBlank { "未知错误" }
            throw Pan115Exception("115 返回错误: $msg (cookie 大概过期了, 重新登录一次)")
        }
        val url = j.optString("file_url").ifBlank {
            j.optJSONObject("data")?.optJSONArray("list")?.optJSONObject(0)?.optString("url").orEmpty()
        }
        if (url.isBlank()) throw Pan115Exception("115 没返回下载直链, 换个节点再试")

        val headers = HashMap<String, String>()
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        headers["User-Agent"] = CDN_UA
        DirectLink(url, headers)
    }

    /** 文件元信息 (拿真实文件名和大小, 处理 pickcode 下载时 Content-Disposition 的怪编码) */
    suspend fun fileInfo(pickcode: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        val j = JSONObject(get("$FILES_BASE/file?pickcode=$pickcode"))
        val o = j.optJSONArray("data")?.optJSONObject(0) ?: return@withContext "" to 0L
        o.optString("file_name") to (o.optString("file_size").toLongOrNull() ?: 0L)
    }

    // ================= 工具 =================

    companion object {
        fun extOf(filename: String): String {
            val dot = filename.lastIndexOf('.')
            return if (dot >= 0) filename.substring(dot + 1).lowercase() else ""
        }

        fun stemOf(filename: String): String =
            if (filename.contains('.')) filename.substringBeforeLast('.') else filename
    }
}

/**
 * 极简内存 cookie jar。
 *
 * 为什么不用 OkHttp 自带的 [CookieJar]: 自带那个会**按域名匹配**才发 cookie,
 * 而 115 的 CDN 直链在另一个域上, cookie 发不出去就 403。我们需要的是
 * "把 jar 里所有东西拼成一条 Cookie 头手动带上", 所以自己实现一个。
 */
class MemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, String>()

    fun put(name: String, value: String, domain: String) {
        store[name] = value
    }

    fun names(): Set<String> = store.keys.toSet()

    fun clear() = store.clear()

    fun asHeader(): String = store.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun snapshot(): String = asHeader()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c -> store[c.name] = c.value }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
}
