package com.cloudgamehub.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudgamehub.data.model.ScanState
import com.cloudgamehub.data.prefs.PrefsStore
import com.cloudgamehub.data.repo.LibraryRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repo: LibraryRepo,
    private val prefs: PrefsStore,
) : ViewModel() {
    val state = repo.scan

    private val _counts = MutableStateFlow(Triple(0, 0, 0))
    val counts = _counts.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _counts.value = Triple(repo.countAll(), repo.localCount(), repo.pendingScrapeCount())
        }
    }

    fun start() {
        viewModelScope.launch {
            val cid = prefs.romRootCid()
            val path = prefs.romRootPath() ?: "根目录"
            if (cid.isNullOrBlank()) {
                repo.scan.value = ScanState(done = true, error = "还没选目录")
                return@launch
            }
            val auto = prefs.autoScrapeAfterScan()
            repo.scanAndImport(cid, path, auto) { added, total ->
                _counts.value = Triple(repo.countAll(), repo.localCount(), repo.pendingScrapeCount())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    rootPath: String?,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    vm: ScanViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val counts by vm.counts.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    // 跑完了自动去库里
    LaunchedEffect(state.done, state.error) {
        if (state.done && state.error == null) {
            kotlinx.coroutines.delay(900)
            onOpenLibrary()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描 115") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, "关闭") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (state.running) "⏳" else if (state.done) "✅" else "📂", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                rootPath ?: "根目录",
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.phase.ifBlank { "准备开始" },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(20.dp))
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.percent },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.scanned} / ${state.total}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            if (state.current.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.current,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 2,
                )
            }
            if (state.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.message, fontSize = 12.sp)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("❌ $it", Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(28.dp))
            if (!state.running) {
                Button(
                    onClick = { vm.start() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始扫描", fontSize = 15.sp)
                }
            } else if (state.phase.startsWith("刮削")) {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "AI 正在挨个认游戏. 这一步最花时间, 认出中文译名和简介. " +
                            "你可以切到后台干别的, 扫完会通知.",
                        Modifier.padding(12.dp), fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前状态", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    StatRow("库里游戏", "${counts.first} 个")
                    StatRow("已下载到手机", "${counts.second} 个")
                    StatRow("还没刮削", "${counts.third} 个")
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
