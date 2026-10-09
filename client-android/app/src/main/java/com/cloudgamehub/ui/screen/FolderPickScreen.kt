package com.cloudgamehub.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudgamehub.data.pan115.Pan115Item
import com.cloudgamehub.data.prefs.PrefsStore
import com.cloudgamehub.data.repo.LibraryRepo
import com.cloudgamehub.data.scan.PlatformDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FolderPickViewModel @Inject constructor(
    private val repo: LibraryRepo,
    private val prefs: PrefsStore,
) : ViewModel() {

    private val _items = MutableStateFlow<List<Pan115Item>>(emptyList())
    val items = _items.asStateFlow()
    private val _crumbs = MutableStateFlow<List<Pair<String, String>>>(listOf("0" to "根目录"))
    val crumbs = _crumbs.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()

    private val _alreadyRoot = MutableStateFlow<Pair<String, String>?>(null)
    val alreadyRoot = _alreadyRoot.asStateFlow()

    private var currentCid: String = "0"

    init {
        viewModelScope.launch {
            val cid = prefs.romRootCid()
            val path = prefs.romRootPath()
            if (!cid.isNullOrBlank()) _alreadyRoot.value = cid to (path ?: cid)
            load("0")
        }
    }

    fun load(cid: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true; _msg.value = null
            try {
                currentCid = cid
                _crumbs.value = repo.breadcrumb(cid).ifEmpty { listOf(cid to "当前目录") }
                val list = repo.browse115(cid)
                // 目录排前面, 同类按名称排
                _items.value = list.sortedWith(
                    compareByDescending<Pan115Item> { it.isDir }.thenBy { it.name.lowercase() }
                )
            } catch (e: Exception) {
                _msg.value = "读取目录失败: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun enter(item: Pan115Item) {
        if (item.isDir && item.cid.isNotBlank()) load(item.cid)
    }

    fun goUp() {
        val c = _crumbs.value
        if (c.size <= 1) return
        load(c[c.size - 2].first)
    }

    fun goCrumb(index: Int) {
        val c = _crumbs.value
        if (index in c.indices) load(c[index].first)
    }

    /** 猜一下这个目录里大概有多少 ROM, 给用户一个预期 */
    fun romCountHint(item: Pan115Item): String {
        val plat = PlatformDetector.detect(item.name, item.name)
        return if (plat != "misc") "看起来是 $plat" else ""
    }

    fun chooseThis(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val path = _crumbs.value.joinToString("/") { it.second }.removePrefix("/")
            prefs.setRomRoot(currentCid, path)
            onDone(path.ifBlank { "根目录" })
        }
    }

    /** 目录里像个 ROM 库吗 (给个提示, 不强制) */
    fun looksLikeRomRoot(): Boolean = _crumbs.value.size > 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickScreen(
    onBack: () -> Unit,
    onPicked: (String) -> Unit,
    vm: FolderPickViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val crumbs by vm.crumbs.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.msg.collectAsState()
    val already by vm.alreadyRoot.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackHost.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                title = { Text("选择 ROM 目录") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { vm.chooseThis { p -> onPicked(p) } }) {
                        Icon(Icons.Default.Check, "用这个目录")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // 面包屑
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.goUp() }, enabled = crumbs.size > 1) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一级")
                }
                LazyRowCrumbs(crumbs, vm::goCrumb)
            }

            if (already != null) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "上次选的是: ${already!!.second}",
                        Modifier.padding(10.dp),
                        fontSize = 12.sp,
                    )
                }
            }

            Text(
                "挑一个装 ROM 的文件夹, 进去之后点右上角 ✓ 就用它扫描",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            if (busy && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这个文件夹是空的", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.name + it.cid }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.enter(item) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (item.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                null,
                                tint = if (item.isDir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (!item.isDir) {
                                    Text(
                                        humanSize(item.size),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                }
                            }
                            if (item.isDir) {
                                val hint = vm.romCountHint(item)
                                if (hint.isNotBlank()) {
                                    Text(
                                        hint, fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }
                        HorizontalDivider(
                            Modifier.padding(start = 52.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyRowCrumbs(crumbs: List<Pair<String, String>>, onClick: (Int) -> Unit) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, (cid, name) ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
            Text(
                name,
                Modifier.clickable { onClick(i) }.padding(horizontal = 4.dp, vertical = 4.dp),
                fontSize = 13.sp,
                fontWeight = if (i == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun humanSize(n: Long): String {
    if (n <= 0) return "0 B"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}
