package com.nasgame.ui.screen

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nasgame.data.db.NasDb
import com.nasgame.data.download.DownloadManager
import com.nasgame.data.media.MediaStore
import com.nasgame.data.model.Game
import com.nasgame.data.play.PlayCoordinator
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.LibraryRepo
import com.nasgame.emulator.EmulatorLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val repo: LibraryRepo,
    private val db: NasDb,
    private val media: MediaStore,
    private val prefs: PrefsStore,
    private val play: PlayCoordinator,
    private val downloadMgr: DownloadManager,
) : ViewModel() {

    private val _game = MutableStateFlow<Game?>(null)
    val game = _game.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()
    private val _emulator = MutableStateFlow<String?>(null)
    val emulator = _emulator.asStateFlow()
    private val _core = MutableStateFlow<String?>(null)
    val core = _core.asStateFlow()
    private val _phase = MutableStateFlow<String?>(null)   // 下载/准备核心 的阶段提示
    val phase = _phase.asStateFlow()

    val progress = downloadMgr.active

    fun load(id: Long) {
        viewModelScope.launch {
            val g = repo.game(id) ?: run { _msg.value = "游戏不存在"; return@launch }
            _game.value = g
            _emulator.value = play.emulatorPackage(g)
            _core.value = play.retroArchManager().resolveCore(g.platformCode)
        }
    }

    fun coverFile(): java.io.File? = _game.value?.let { media.pathOf(it.coverFile) }

    // ==================== 游玩 ====================

    /**
     * 🎮 游玩。整个 App 的主路径:
     * 本地有 → 直接开; 没有 → 从 115 下, 下完自动开。
     */
    fun play(ctx: Context) {
        val g = _game.value ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val r = play.play(ctx, g)) {
                    is PlayCoordinator.Result.Launched -> {
                        _msg.value = "已启动 ${r.pkg}"
                        _game.value = repo.game(g.id) ?: g
                    }
                    is PlayCoordinator.Result.Downloading -> {
                        _phase.value = "正在从 115 下载…"
                        _msg.value = "开始下载, 下完自动开玩"
                    }
                    is PlayCoordinator.Result.AlreadyDownloading -> {
                        _phase.value = "正在下载中…"
                    }
                    is PlayCoordinator.Result.NoLocalRom -> _msg.value = "本地没找到 ROM"
                    is PlayCoordinator.Result.NoEmulator -> _msg.value = r.hint
                    is PlayCoordinator.Result.MissingCore ->
                        _msg.value = "缺核心 (${r.core}). 去设置 → RetroArch 集成 里装"
                    is PlayCoordinator.Result.UnreachablePath -> _msg.value = r.reason
                    is PlayCoordinator.Result.Error -> _msg.value = r.message
                }
            } catch (e: Exception) {
                _msg.value = "出错了: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 下载完成后由 UI 调, 继续启动 */
    fun launchAfterDownload(ctx: Context) {
        val g = _game.value ?: return
        viewModelScope.launch {
            _phase.value = null
            when (val r = play.launchLocal(ctx, g)) {
                is PlayCoordinator.Result.Launched -> {
                    _msg.value = "已启动 ${r.pkg}"
                    _game.value = repo.game(g.id) ?: g
                }
                is PlayCoordinator.Result.Error -> _msg.value = r.message
                is PlayCoordinator.Result.UnreachablePath -> _msg.value = r.reason
                is PlayCoordinator.Result.MissingCore -> _msg.value = "缺核心: ${r.core}"
                is PlayCoordinator.Result.NoEmulator -> _msg.value = r.hint
                else -> _msg.value = "还是起不来, 检查一下模拟器和 ROM 目录"
            }
        }
    }

    fun cancelDownload() {
        _game.value?.let { downloadMgr.cancel(it.id) }
        clearPhase()
    }

    fun clearPhase() { _phase.value = null }

    fun deleteLocal() {
        val g = _game.value ?: return
        viewModelScope.launch {
            downloadMgr.deleteLocal(g)
            _game.value = repo.game(g.id) ?: g.copy(localPath = "", localSize = 0)
            _msg.value = "已删除本地 ROM"
        }
    }

    fun toggleFav() {
        val g = _game.value ?: return
        viewModelScope.launch {
            repo.toggleFavorite(g)
            _game.value = repo.game(g.id) ?: g
        }
    }

    fun rescrape() {
        val g = _game.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.rescrape(g)
            _busy.value = false
            _game.value = repo.game(g.id) ?: g
            _msg.value = if (r.ok) "重新刮削完成 (${r.source})" else "这次还是没认出来"
        }
    }

    /** 用户手动上传封面 */
    fun uploadCover(ctx: Context, uri: Uri) {
        val g = _game.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val ok = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching false
                repo.setManualCover(g, bytes, "jpg")
            }.getOrDefault(false)
            _busy.value = false
            _game.value = repo.game(g.id) ?: g
            _msg.value = if (ok) "封面已更新" else "封面上传失败"
        }
    }

    fun installRetroArch(ctx: Context) {
        val ra = play.retroArchManager()
        if (ra.openInstallPage()) _msg.value = "已跳转下载页, 装完回来刷新"
        else _msg.value = "打不开下载页, 请手动去 retroarch.com"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    vm: GameDetailViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    LaunchedEffect(gameId) { vm.load(gameId) }

    val g by vm.game.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.msg.collectAsState()
    val emulator by vm.emulator.collectAsState()
    val core by vm.core.collectAsState()
    val phase by vm.phase.collectAsState()
    val progress by vm.progress.collectAsState()

    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackHost.showSnackbar(it) } }

    // 手动上传封面的文件选择器
    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.uploadCover(ctx, it) } }

    // 下载进度: 完成就自动启动
    val myProgress = progress[gameId]
    LaunchedEffect(myProgress?.done, myProgress?.error) {
        val p = myProgress ?: return@LaunchedEffect
        when {
            p.error != null -> {
                Toast.makeText(ctx, p.error, Toast.LENGTH_LONG).show()
                vm.clearPhase()
            }
            p.done -> {
                vm.load(gameId)
                Toast.makeText(ctx, "下载完成, 正在启动…", Toast.LENGTH_SHORT).show()
                vm.launchAfterDownload(ctx)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        g?.displayTitle ?: "游戏详情",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    g?.let {
                        IconButton(onClick = { vm.toggleFav() }) {
                            Icon(
                                if (it.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                "收藏",
                                tint = if (it.favorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = { pickCover.launch("image/*") }) {
                        Icon(Icons.Default.Image, "换封面")
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
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // 封面 + 元数据
            Row(Modifier.padding(16.dp)) {
                Box(
                    Modifier
                        .width(130.dp).aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val cover = remember(game.coverFile) { vm.coverFile() }
                    if (cover != null) {
                        AsyncImage(
                            model = cover, null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                game.platformCode, fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        game.displayTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    if (game.titleEn.isNotBlank() && game.titleEn != game.displayTitle) {
                        Text(
                            game.titleEn, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    game.platform?.let { Text("🎮 ${it.name}", fontSize = 12.sp) }
                    if (game.releaseDate.isNotBlank()) Text("📅 ${game.releaseDate}", fontSize = 12.sp)
                    if (game.developer.isNotBlank()) Text("🎬 ${game.developer}", fontSize = 12.sp)
                    if (game.publisher.isNotBlank()) Text("📦 ${game.publisher}", fontSize = 12.sp)
                    if (game.genre.isNotBlank()) Text("🎯 ${game.genre}", fontSize = 12.sp)
                    Text("💾 ${humanSize(game.romSize)}", fontSize = 12.sp)
                    if (game.playCount > 0) Text("▶️ 玩过 ${game.playCount} 次", fontSize = 12.sp)
                }
            }

            // ROM 状态
            RomStatusRow(
                game = game,
                emulator = emulator,
                core = core,
                onCancel = { vm.cancelDownload() },
            )

            // 下载进度
            myProgress?.let { p ->
                if (!p.done) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "📥 ${p.formattedSize}" +
                                if (p.formattedSpeed.isNotBlank()) " · ${p.formattedSpeed}" else "",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { p.percent },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        )
                    }
                }
            }

            if (game.description.isNotBlank()) {
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(game.description, Modifier.padding(12.dp), fontSize = 13.sp, lineHeight = 19.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ===== 主按钮 =====
            Column(Modifier.padding(horizontal = 16.dp)) {
                Button(
                    onClick = { vm.play(ctx) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !busy && phase == null,
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            phase != null -> phase!!
                            game.isLocal -> "▶ 开始游戏"
                            else -> "▶ 游玩 (从 115 下载)"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }

                if (emulator == null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.installRetroArch(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("装 RetroArch (核心我帮你下)", fontSize = 13.sp)
                    }
                }
                if (phase != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.cancelDownload() }, modifier = Modifier.fillMaxWidth()) {
                        Text("取消下载", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))

            // 详细信息
            Column(Modifier.padding(horizontal = 16.dp)) {
                DetailRow("文件名", game.romFilename)
                DetailRow("115 路径", game.pathDisplay)
                DetailRow("平台", game.platform?.let { "${it.code} · ${it.name}" } ?: game.platformCode)
                DetailRow("刮削来源", game.scrapeSource.ifBlank { "未刮削" })
                game.localFile()?.let {
                    DetailRow("本地文件", "${it.absolutePath}  (${humanSize(it.length())})")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.rescrape() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新刮削", fontSize = 12.sp)
                }
                if (game.isLocal) {
                    OutlinedButton(onClick = { vm.deleteLocal() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删本地", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label, Modifier.width(72.dp), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            value, Modifier.weight(1f), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun RomStatusRow(
    game: Game,
    emulator: String?,
    core: String?,
    onCancel: () -> Unit,
) {
    val realFile = game.localFile()
    val (text, sub, icon, color) = when {
        game.isLocal && realFile == null ->
            "本地记录还在, 但文件被删了" to "点播放会自动重新下载" to
                Icons.Default.Warning to Color(0xFFFF9800)
        game.isLocal ->
            "已下载到本机 · 点上面直接开玩" to "${humanSize(game.localSize)} · $emulator" to
                Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        else ->
            "在 115 上 · 点了才下载到手机" to
                "预计 ${humanSize(game.romSize)}" +
                    (emulator?.let { " · 用 $it" } ?: " · 没找到模拟器") +
                    (if (EmulatorLauncher.isRetroArch(emulator)) " (核心 ${core ?: "待装"})" else "") to
                Icons.Default.CloudDownload to Color(0xFF2196F3)
    }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(text, fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
                if (sub.isNotBlank()) {
                    Text(
                        sub, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
