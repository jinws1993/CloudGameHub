package com.nasgame.ui.screen

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.nasgame.data.api.Game
import com.nasgame.data.download.DownloadManager
import com.nasgame.data.download.DownloadProgress
import com.nasgame.data.download.RomSource
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.NasGameRepo
import com.nasgame.emulator.EmulatorLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val repo: NasGameRepo,
    private val prefs: PrefsStore,
    private val downloadMgr: DownloadManager,
) : ViewModel() {
    private val _game = MutableStateFlow<Game?>(null)
    val game = _game.asStateFlow()
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress = _progress.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()
    private val _isLocal = MutableStateFlow(false)
    val isLocal = _isLocal.asStateFlow()
    private val _romSource = MutableStateFlow(RomSource.UNKNOWN)
    val romSource = _romSource.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            try {
                val g = repo.game(id)
                _game.value = g
                _isLocal.value = downloadMgr.isLocal(g)
                _romSource.value = downloadMgr.probeSource(repo.apiService, g)
            } catch (e: Exception) {
                _msg.value = e.message
            }
        }
    }

    fun serverUrl(): String? = kotlinx.coroutines.runBlocking { prefs.currentServer() }

    fun toggleFav() {
        val g = _game.value ?: return
        viewModelScope.launch {
            try { _game.value = g.copy(favorite = repo.toggleFav(g.id.toLong())) } catch (_: Exception) {}
        }
    }

    /** 核心: 下载 + 启动 (智能选择路径) */
    fun downloadAndLaunch(ctx: Context) {
        val g = _game.value ?: return
        viewModelScope.launch {
            _busy.value = true
            _progress.value = null
            try {
                val platCode = g.platform?.code ?: "misc"
                val romFile = downloadMgr.romCacheDir(platCode).resolve(g.romFilename)

                // 1. 本地已有完整 ROM → 直接启动
                if (downloadMgr.isLocal(g)) {
                    launchEmulator(ctx, platCode, romFile, g)
                    return@launch
                }

                // 2. 下载 (走 115 CDN 或服务器本地流)
                _msg.value = "开始下载: ${g.romFilename}"
                downloadMgr.startDownload(repo.apiService, g) { file ->
                    if (file != null && file.exists()) {
                        // 下载完成 → 启动模拟器
                        viewModelScope.launch {
                            _isLocal.value = true
                            launchEmulator(ctx, platCode, file, g)
                        }
                    }
                }

                // 3. 监听下载进度 (轮询 active map)
                launch {
                    downloadMgr.active.collect { map ->
                        _progress.value = map[g.id.toLong()]
                    }
                }
            } catch (e: Exception) {
                _msg.value = "失败: ${e.message}"
            } finally {
                // 注意: 不要置 false, 因为下载是后台跑的. 详情页退出后下载继续
            }
        }
    }

    /** 只下载, 不启动 (供用户提前批量) */
    fun downloadOnly() {
        val g = _game.value ?: return
        viewModelScope.launch {
            try {
                if (!downloadMgr.isLocal(g)) {
                    _msg.value = "已加入下载队列: ${g.romFilename}"
                    downloadMgr.startDownload(repo.apiService, g)
                    launch {
                        downloadMgr.active.collect { map ->
                            _progress.value = map[g.id.toLong()]
                        }
                    }
                } else {
                    _msg.value = "本地已有, 无需下载"
                }
            } catch (e: Exception) {
                _msg.value = "失败: ${e.message}"
            }
        }
    }

    /** 删除本地 ROM */
    fun deleteLocal() {
        val g = _game.value ?: return
        val f = downloadMgr.romCacheDir(g.platform?.code ?: "misc").resolve(g.romFilename)
        if (f.exists()) {
            f.delete()
            _isLocal.value = false
            _msg.value = "已删除本地 ROM"
        } else {
            _msg.value = "本地无 ROM"
        }
    }

    /** 直接启动本地 ROM (不重新下载) */
    fun launchLocal(ctx: Context) {
        val g = _game.value ?: return
        val platCode = g.platform?.code ?: "misc"
        val romFile = downloadMgr.romCacheDir(platCode).resolve(g.romFilename)
        if (!romFile.exists()) {
            _msg.value = "本地无 ROM, 请先下载"
            return
        }
        viewModelScope.launch {
            launchEmulator(ctx, platCode, romFile, g)
        }
    }

    private suspend fun launchEmulator(ctx: Context, platCode: String, romFile: File, g: Game) {
        val pkg = prefs.getEmulatorPkg(platCode)
        if (pkg.isNullOrBlank()) {
            _msg.value = "请先在设置中配置 [$platCode] 的模拟器包名"
            return
        }

        val resolvedPkg = EmulatorLauncher.resolvePackage(ctx, platCode, pkg)
        if (resolvedPkg == null) {
            _msg.value = "未找到模拟器 [$pkg], 请检查是否已安装或在设置页修改"
            return
        }

        val launched = EmulatorLauncher.launch(ctx, resolvedPkg, romFile.absolutePath)
        if (launched) {
            try { repo.playLocal(g.id.toLong()) } catch (_: Exception) {}
            _msg.value = "已启动 $resolvedPkg"
        } else {
            // fallback: 通用 VIEW intent
            try {
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(romFile), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                _msg.value = "已用通用方式启动"
            } catch (_: ActivityNotFoundException) {
                _msg.value = "未找到可启动的模拟器"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    onStream: () -> Unit,
    vm: GameDetailViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    LaunchedEffect(gameId) { vm.load(gameId) }
    val g by vm.game.collectAsState()
    val progress by vm.progress.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.msg.collectAsState()
    val isLocal by vm.isLocal.collectAsState()
    val romSource by vm.romSource.collectAsState()

    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(g?.titleZh ?: g?.titleEn ?: g?.titleRaw ?: "游戏详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    g?.let { game ->
                        IconButton(onClick = vm::toggleFav) {
                            Icon(
                                if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                "收藏",
                                tint = if (game.favorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        val game = g
        if (game == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            // 封面 + 元数据
            Row(Modifier.padding(16.dp)) {
                Box(
                    Modifier.width(140.dp).aspectRatio(3f / 4f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    if (game.cover.isNotBlank() && vm.serverUrl() != null) {
                        AsyncImage(
                            model = joinUrl(vm.serverUrl()!!, game.cover),
                            null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(game.platform?.code ?: "?", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                        Text(
                            game.titleZh.ifBlank { game.titleEn },
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (game.titleEn.isNotBlank() && game.titleZh.isNotBlank()) {
                            Text(game.titleEn, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("🎮 ${game.platform?.name ?: "?"}", fontSize = 12.sp)
                        if (game.releaseDate.isNotBlank()) Text("📅 ${game.releaseDate.take(4)}", fontSize = 12.sp)
                        if (game.developer.isNotBlank()) Text("🎬 ${game.developer}", fontSize = 12.sp)
                        if (game.publisher.isNotBlank()) Text("📦 ${game.publisher}", fontSize = 12.sp)
                        if (game.genre.isNotBlank()) Text("🎯 ${game.genre}", fontSize = 12.sp)
                        if (game.rating > 0) Text("⭐ ${"%.1f".format(game.rating)}", fontSize = 12.sp)
                        if (game.romSize > 0) Text("💾 ${humanSize(game.romSize)}", fontSize = 12.sp)
                    }
            }

            // ROM 状态条
            RomStatusBar(
                isLocal = isLocal,
                romSource = romSource,
                cloudSource = game.cloudSource,
                romSize = game.romSize,
            )

            // 进度条
            val p = progress
            if (p != null && !p.done_) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        if (p.error != null) "❌ ${p.error}" else "📥 下载中: ${p.formattedSize} (${p.formattedSpeed})",
                        fontSize = 12.sp,
                        color = if (p.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { p.percent },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    )
                }
            }

            // 描述
            if (game.description.isNotBlank()) {
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        game.description,
                        Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 主要操作
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (isLocal) {
                    Button(
                        onClick = { vm.launchLocal(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始游戏 (本地 ROM)", fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = vm::deleteLocal,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("删除本地 ROM")
                    }
                } else {
                    Button(
                        onClick = { vm.downloadAndLaunch(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (romSource) {
                                RomSource.LOCAL -> "下载并启动"
                                RomSource.REMOTE_115 -> "从 115 下载并启动"
                                else -> "下载并启动"
                            },
                            fontSize = 15.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = vm::downloadOnly,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("仅下载 (后台)")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStream, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Default.Cast, null)
                Spacer(Modifier.width(4.dp))
                Text("远程串流 (实验)")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun RomStatusBar(
    isLocal: Boolean,
    romSource: RomSource,
    cloudSource: String,
    romSize: Long,
) {
    val (text, icon, color) = when {
        isLocal -> Triple("本地已有 ROM, 可立即启动", Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary)
        romSource == RomSource.REMOTE_115 -> Triple("115 网盘, 走 302 CDN 加速下载", Icons.Default.CloudDownload, Color(0xFFFF9800))
        romSource == RomSource.LOCAL -> Triple("服务器本地流式下载", Icons.Default.Storage, MaterialTheme.colorScheme.tertiary)
        else -> Triple("暂无 ROM 文件", Icons.Default.Error, MaterialTheme.colorScheme.error)
    }

    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(text, fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
                if (romSize > 0 && !isLocal) {
                    Text(
                        "预计下载: ${humanSize(romSize)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

private fun humanSize(n: Long): String {
    if (n <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
/** Join base + path safely (handle trailing/leading slashes). */
fun joinUrl(base: String, path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val b = base.trimEnd('/')
    val p = if (path.startsWith("/")) path else "/$path"
    return b + p
}
