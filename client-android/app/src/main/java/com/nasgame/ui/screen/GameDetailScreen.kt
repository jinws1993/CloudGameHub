package com.nasgame.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.nasgame.data.api.Game
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.NasGameRepo
import com.nasgame.emulator.EmulatorLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val repo: NasGameRepo,
    private val prefs: PrefsStore,
) : ViewModel() {
    private val _game = MutableStateFlow<Game?>(null)
    val game = _game.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            try { _game.value = repo.game(id) } catch (e: Exception) {
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

    fun downloadAndLaunch() {
        val g = _game.value ?: return
        viewModelScope.launch {
            _busy.value = true
            _progress.value = 0f
            try {
                val romFile = repo.romCacheDir(g.platform?.code ?: "misc").resolve(g.romFilename)
                if (!romFile.exists() || romFile.length() != g.romSize) {
                    _msg.value = "下载中: ${g.romFilename}"
                    repo.downloadRom(g.id.toLong(), romFile) { done, total ->
                        if (total > 0) _progress.value = done / total.toFloat()
                    }
                }
                val platCode = g.platform?.code ?: ""
                val pkg = kotlinx.coroutines.runBlocking { prefs.getEmulatorPkg(platCode) }
                if (pkg.isNullOrBlank()) {
                    _msg.value = "请先在设置中配置 [$platCode] 的模拟器包名"
                } else {
                    val launched = EmulatorLauncher.launch(LocalContextRef.value, pkg, romFile.absolutePath)
                    if (!launched) {
                        // fallback: try generic intent
                        try {
                            val i = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.fromFile(romFile), "*/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            LocalContextRef.value.startActivity(i)
                        } catch (_: ActivityNotFoundException) {
                            _msg.value = "未找到可启动的模拟器"
                        }
                    } else {
                        // record play
                        try { repo.playLocal(g.id.toLong()) } catch (_: Exception) {}
                        _msg.value = "已启动模拟器"
                    }
                }
            } catch (e: Exception) {
                _msg.value = "失败: ${e.message}"
            } finally { _busy.value = false }
        }
    }
}

// small holder for context (hack to pass into ViewModel)
object LocalContextRef {
    var value: android.content.Context = androidx.test.core.app.ApplicationProvider
        .getApplicationContext() // placeholder, replaced by CompositionLocalProvider
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
    LaunchedEffect(Unit) { LocalContextRef.value = ctx; vm.load(gameId) }
    val g by vm.game.collectAsState()
    val progress by vm.progress.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.msg.collectAsState()

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(g?.titleZh ?: g?.titleEn ?: g?.titleRaw ?: "游戏详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
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
            Row(Modifier.padding(16.dp)) {
                Box(
                    Modifier.width(140.dp).aspectRatio(3f / 4f)
                        .background(MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (game.cover.isNotBlank() && vm.serverUrl() != null) {
                        AsyncImage(model = vm.serverUrl() + game.cover, null,
                                   modifier = Modifier.fillMaxSize())
                    } else {
                        Text(game.platform?.code ?: "?",
                             fontSize = 28.sp, fontWeight = FontWeight.Bold,
                             color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(game.titleZh.ifBlank { game.titleEn }, fontSize = 20.sp,
                         fontWeight = FontWeight.Bold)
                    if (game.titleEn.isNotBlank() && game.titleZh.isNotBlank()) {
                        Text(game.titleEn, fontSize = 14.sp,
                             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("平台: ${game.platform?.name ?: "?"}", fontSize = 13.sp)
                    if (game.releaseDate.isNotBlank()) Text("年份: ${game.releaseDate}", fontSize = 13.sp)
                    if (game.developer.isNotBlank()) Text("开发商: ${game.developer}", fontSize = 13.sp)
                    if (game.publisher.isNotBlank()) Text("发行商: ${game.publisher}", fontSize = 13.sp)
                    if (game.genre.isNotBlank()) Text("类型: ${game.genre}", fontSize = 13.sp)
                    if (game.rating > 0) Text("评分: ⭐ ${"%.1f".format(game.rating)}", fontSize = 13.sp)
                }
            }
            if (game.description.isNotBlank()) {
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(game.description, Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (busy) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    LinearProgressIndicator(progress = { progress },
                                            modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("${(progress * 100).toInt()}%", fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::downloadAndLaunch, Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("下载并运行")
                }
                OutlinedButton(onClick = onStream, Modifier.weight(1f)) {
                    Icon(Icons.Default.Stream, null)
                    Spacer(Modifier.width(4.dp))
                    Text("串流")
                }
            }
            TextButton(onClick = vm::toggleFav, Modifier.padding(horizontal = 16.dp)) {
                Text(if (g?.favorite == true) "★ 已收藏 (点击取消)" else "☆ 收藏")
            }
        }
    }
}
