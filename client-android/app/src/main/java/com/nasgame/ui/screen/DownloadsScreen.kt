package com.nasgame.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.db.NasDb
import com.nasgame.data.download.DownloadManager
import com.nasgame.data.repo.LibraryRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadMgr: DownloadManager,
    private val db: NasDb,
    private val repo: LibraryRepo,
) : ViewModel() {
    val queue = downloadMgr.queue
    val active = downloadMgr.active

    private val _local = MutableStateFlow<List<LocalRom>>(emptyList())
    val local = _local.asStateFlow()
    private val _bytes = MutableStateFlow(0L)
    val bytes = _bytes.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val list = ArrayList<LocalRom>()
            var total = 0L
            repo.query(sort = "added", pageSize = 2000).forEach { g ->
                if (g.isLocal) {
                    g.localFile()?.let { f ->
                        list += LocalRom(g.id, g.displayTitle, g.platformCode, f.absolutePath, f.length())
                        total += f.length()
                    }
                }
            }
            list.sortByDescending { it.sizeBytes }
            _local.value = list
            _bytes.value = total
        }
    }

    fun cancel(id: Long) = downloadMgr.cancel(id)
    fun cancelAll() = downloadMgr.cancelAll()

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.game(id)?.let { g ->
                val path = g.localPath
                downloadMgr.deleteLocal(g)
                // 顺手把 .part 也清掉
                if (path.isNotBlank()) java.io.File("$path.part").delete()
            }
            refresh()
        }
    }
}

data class LocalRom(
    val id: Long,
    val title: String,
    val platform: String,
    val path: String,
    val sizeBytes: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val queue by vm.queue.collectAsState()
    val active by vm.active.collectAsState()
    val local by vm.local.collectAsState()
    val bytes by vm.bytes.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("下载与本地", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "本地 ${local.size} 个 · ${humanSize(bytes)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        TextButton(onClick = { vm.cancelAll() }) { Text("全部取消") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("下载中 ${if (queue.isEmpty()) "" else "(${queue.size})"}") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("已下载 ${local.size}") })
            }

            if (tab == 0) {
                if (queue.isEmpty()) {
                    EmptyBox("📭", "没有在下的任务", "游戏详情页点「游玩」就会自动开始下载")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(active.values.toList(), key = { it.gameId }) { p ->
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            p.filename, Modifier.weight(1f), fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        IconButton(onClick = { vm.cancel(p.gameId) }) {
                                            Icon(Icons.Default.Close, "取消", Modifier.size(18.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { p.percent },
                                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(
                                            androidx.compose.foundation.shape.CircleShape
                                        ),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        p.error ?: "${p.formattedSize}" +
                                            if (p.formattedSpeed.isNotBlank()) " · ${p.formattedSpeed}" else "",
                                        fontSize = 11.sp,
                                        color = if (p.error != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (local.isEmpty()) {
                    EmptyBox(
                        "☁️", "手机里还没有 ROM",
                        "游戏详情页点「游玩」, 会从 115 自动下一个下来"
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(local, key = { it.id }) { r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        r.platform, Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.title, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        humanSize(r.sizeBytes) + " · " + r.path.substringAfterLast('/'),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { vm.delete(r.id) }) {
                                    Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBox(icon: String, title: String, sub: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 44.sp)
            Spacer(Modifier.height(10.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                sub, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
