package com.nasgame.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.nasgame.data.download.DownloadItem
import com.nasgame.data.download.DownloadManager
import com.nasgame.data.download.DownloadProgress
import com.nasgame.data.prefs.PrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadMgr: DownloadManager,
    val prefs: PrefsStore,
) : ViewModel() {
    val queue = downloadMgr.queue
    val active = downloadMgr.active

    private val _localRoms = MutableStateFlow<List<LocalRom>>(emptyList())
    val localRoms = _localRoms.asStateFlow()
    private val _totalSize = MutableStateFlow(0L)
    val totalSize = _totalSize.asStateFlow()
    private val _concurrent = MutableStateFlow(2)
    val concurrent = _concurrent.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            _concurrent.value = prefs.concurrentDownloads()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val list = mutableListOf<LocalRom>()
            var total = 0L
            downloadMgr.romCacheDir("fc").parentFile?.listFiles()?.forEach { platDir ->
                if (platDir.isDirectory) {
                    platDir.listFiles()?.forEach { f ->
                        if (f.isFile && !f.name.endsWith(".part")) {
                            list.add(LocalRom(
                                platform = platDir.name,
                                filename = f.name,
                                sizeBytes = f.length(),
                                path = f,
                            ))
                            total += f.length()
                        }
                    }
                }
            }
            list.sortByDescending { it.sizeBytes }
            _localRoms.value = list
            _totalSize.value = total
        }
    }

    fun cancel(gameId: Long) = downloadMgr.cancel(gameId)

    fun cancelAll() = downloadMgr.cancelAll()

    fun deleteLocal(file: File) {
        if (file.exists()) {
            file.delete()
            // 删 .part
            file.parentFile?.listFiles()?.forEach {
                if (it.name == "${file.name}.part") it.delete()
            }
            refresh()
        }
    }

    fun setConcurrent(n: Int) {
        _concurrent.value = n
        downloadMgr.updateConcurrency(n)
    }
}

data class LocalRom(
    val platform: String,
    val filename: String,
    val sizeBytes: Long,
    val path: File,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val queue by vm.queue.collectAsState()
    val active by vm.active.collectAsState()
    val localRoms by vm.localRoms.collectAsState()
    val totalSize by vm.totalSize.collectAsState()
    val concurrent by vm.concurrent.collectAsState()

    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载与本地") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "刷新") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 总览统计
            Surface(
                Modifier.padding(16.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null)
                        Spacer(Modifier.width(8.dp))
                        Text("本地占用", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        Text(humanSize(totalSize), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GridView, null)
                        Spacer(Modifier.width(8.dp))
                        Text("ROM 文件数", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        Text("${localRoms.size} 个", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("最大并发下载", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        listOf(1, 2, 3, 4).forEach { n ->
                            FilterChip(
                                selected = concurrent == n,
                                onClick = { vm.setConcurrent(n) },
                                label = { Text("$n") },
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("下载中 (${queue.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("本地 ROM (${localRoms.size})") })
            }

            when (tab) {
                0 -> DownloadListView(queue, active, vm)
                1 -> LocalRomsList(localRoms, vm)
            }
        }
    }
}

@Composable
private fun DownloadListView(
    queue: List<DownloadItem>,
    active: Map<Long, DownloadProgress>,
    vm: DownloadsViewModel,
) {
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📥", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text("暂无下载任务", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(queue, key = { it.gameId }) { item ->
            val p = active[item.gameId]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                item.platform,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.title,
                            Modifier.weight(1f),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { vm.cancel(item.gameId) }) {
                            Icon(Icons.Default.Close, "取消")
                        }
                    }
                    Text(
                        item.romFilename,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (p == null) {
                        Text("排队中...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    } else if (p.error != null) {
                        Text("❌ ${p.error}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    } else {
                        LinearProgressIndicator(
                            progress = { p.percent },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(
                                "${(p.percent * 100).toInt()}%  ${p.formattedSize}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(p.formattedSpeed, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }

        if (queue.isNotEmpty()) {
            item {
                TextButton(
                    onClick = vm::cancelAll,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Cancel, null)
                    Spacer(Modifier.width(4.dp))
                    Text("取消全部")
                }
            }
        }
    }
}

@Composable
private fun LocalRomsList(
    items: List<LocalRom>,
    vm: DownloadsViewModel,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📂", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text("本地无文件", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text("下载 ROM 后会出现在这里", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { "${it.platform}/${it.filename}" }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            r.platform,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.filename,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            humanSize(r.sizeBytes),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { vm.deleteLocal(r.path) }) {
                        Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
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
    return "%.2f %s".format(v, units[i])
}