package com.nasgame.data.play

import android.content.Context
import com.nasgame.data.db.NasDb
import com.nasgame.data.download.DownloadManager
import com.nasgame.data.model.Game
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.emulator.EmulatorLauncher
import com.nasgame.emulator.RetroArchManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「游玩」的总指挥。**没有服务器了, 链路就三步**:
 *
 * ```
 * 点游玩
 *   ├─ 手机本地有 ROM? ── 是 ──────────────→ 唤起 RA
 *   └─ 没有 → 115 直链下载到本地 (断点续传)
 *                 ↓
 *          RA 缺核心? → 自动装 (libretro buildbot)
 *                 ↓
 *            唤起 RA, 记一次游玩
 * ```
 */
@Singleton
class PlayCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: PrefsStore,
    private val downloadMgr: DownloadManager,
    private val ra: RetroArchManager,
    private val db: NasDb,
) {

    sealed class Result {
        data class Launched(val pkg: String, val via: String) : Result()
        /** 正在下载, 下完自动继续 */
        object Downloading : Result()
        object AlreadyDownloading : Result()
        object NoLocalRom : Result()
        data class NoEmulator(val hint: String) : Result()
        data class MissingCore(val core: String) : Result()
        data class UnreachablePath(val reason: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** 需要用户去装 RetroArch 时回调 (UI 弹个按钮) */
    var onNeedRetroArch: (() -> Unit)? = null

    /**
     * 点「游玩」的完整入口。
     *
     * @param onProgress 下载进度回调
     */
    suspend fun play(context: Context, game: Game): Result {
        val ctx = context.applicationContext

        // ---- 1. 本地有就直接开 ----
        val handle = downloadMgr.localHandle(game)
        if (handle != null) {
            val r = launch(ctx, game, handle)
            if (r is Result.Launched) {
                db.markPlayed(game.id)
                return r
            }
            return r
        }

        // ---- 2. 本地没有 → 从 115 下 ----
        if (game.pickcode.isBlank()) {
            return Result.Error("这个游戏没有 115 文件信息, 重新扫描一次试试")
        }
        if (game.romSize > 20L * 1024 * 1024 * 1024) {
            return Result.Error("这个 ROM 有 ${DownloadManager.human(game.romSize)}, 手机可能放不下")
        }
        // 下的事交给 DownloadManager, 它自己管队列和续传
        if (!downloadMgr.start(game)) return Result.AlreadyDownloading
        return Result.Downloading
    }

    /** 只启动已经下好的 ROM (下载完成后调用) */
    suspend fun launchLocal(context: Context, game: Game): Result {
        val ctx = context.applicationContext
        val handle = downloadMgr.localHandle(game)
            ?: return Result.NoLocalRom
        val r = launch(ctx, game, handle)
        if (r is Result.Launched) db.markPlayed(game.id)
        return r
    }

    private suspend fun launch(ctx: Context, game: Game, handle: com.nasgame.emulator.RomHandle): Result =
        withContext(Dispatchers.IO) {
            val plat = game.platformCode

            // 1. 选模拟器
            val pkg = EmulatorLauncher.resolvePackage(
                ctx, plat, prefs.getEmulatorPkg(plat), prefs.globalEmulatorPkg()
            )
            if (pkg == null) {
                onNeedRetroArch?.invoke()
                return@withContext Result.NoEmulator(
                    if (ra.hasAnyRetroArch()) {
                        "RetroArch 装了但唤不起来, 先手动打开一次让它申请存储权限"
                    } else {
                        "手机上还没装 RetroArch —— 装上以后核心我帮你自动下好"
                    }
                )
            }

            // 2. RA 专属: 真实路径 + 核心
            var corePath: String? = null
            if (EmulatorLauncher.isRetroArch(pkg)) {
                if (!handle.hasRealPath) {
                    return@withContext Result.UnreachablePath(
                        "ROM 放在 App 私有目录里, RetroArch 读不到. " +
                            "到「设置 → ROM 存放路径」换成一个公共目录 (比如 /sdcard/RetroArch/roms)"
                    )
                }
                corePath = try {
                    val cached = ra.locateCore(plat)
                    when {
                        cached != null -> cached
                        !prefs.raAutoInstallCore() ->
                            throw NeedManualCore(ra.resolveCore(plat) ?: ra.noCoreHint(plat) ?: "无核心")
                        else -> ra.ensureCore(plat)
                    }
                } catch (e: NeedManualCore) {
                    return@withContext Result.MissingCore(e.core)
                } catch (e: Exception) {
                    return@withContext Result.Error(
                        "核心准备失败: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }

            // 3. 唤起
            val configFile = if (EmulatorLauncher.isRetroArch(pkg)) ra.raConfigFile(pkg) else null
            val res = EmulatorLauncher.launch(ctx, pkg, handle, corePath, configFile)
            when (res) {
                is EmulatorLauncher.Result.Ok -> Result.Launched(res.pkg, res.via)
                is EmulatorLauncher.Result.Failed -> {
                    val fallback = EmulatorLauncher.launchChooser(ctx, handle)
                    if (fallback is EmulatorLauncher.Result.Ok) Result.Launched(fallback.pkg, fallback.via)
                    else Result.Error(res.reason)
                }
            }
        }

    class NeedManualCore(val core: String) : Exception("需要手动装核心: $core")

    /** 给 UI 用的模拟器包名 */
    suspend fun emulatorPackage(game: Game): String? {
        val plat = game.platformCode
        return EmulatorLauncher.resolvePackage(
            appContext, plat, prefs.getEmulatorPkg(plat), prefs.globalEmulatorPkg()
        )
    }

    fun retroArchInstalled(): Boolean = ra.hasAnyRetroArch()
    fun retroArchManager(): RetroArchManager = ra
}
