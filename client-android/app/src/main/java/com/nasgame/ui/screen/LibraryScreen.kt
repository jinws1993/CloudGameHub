package com.nasgame.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.nasgame.data.api.Platform
import com.nasgame.data.download.DownloadManager
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.NasGameRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: NasGameRepo,
    val prefs: PrefsStore,
    private val downloadMgr: DownloadManager,
) : ViewModel() {
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games = _games.asStateFlow()
    private val _platforms = MutableStateFlow<List<Platform>>(emptyList())
    val platforms = _platforms.asStateFlow()
    private val _selectedPlatform = MutableStateFlow<String?>(null)
    val selectedPlatform = _selectedPlatform.asStateFlow()
    private val _search = MutableStateFlow("")
    val search = _search.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _sort = MutableStateFlow("title")
    val sort = _sort.asStateFlow()

    /** 平台级 ROM 状态: code -> (已下载, 全部) */
    private val _localStats = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val localStats = _localStats.asStateFlow()

    init { refresh() }

    fun setPlatform(code: String?) {
        _selectedPlatform.value = code
        loadGames()
    }

    fun setSearch(q: String) {
        _search.value = q
        loadGames()
    }

    fun setSort(s: String) {
        _sort.value = s
        loadGames()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _platforms.value = repo.platforms()
                loadGames()
                updateLocalStats()
            } catch (e: Exception) {
                _error.value = e.message
            }
            _loading.value = false
        }
    }

    private fun loadGames() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val r = repo.games(
                    platform = _selectedPlatform.value,
                    search = _search.value.ifBlank { null },
                    pageSize = 200,  // 一次拉多些, 本地过滤
                )
                _games.value = sortGames(r.items, _sort.value)
                updateLocalStats()
            } catch (e: Exception) {
                _error.value = e.message
            }
            _loading.value = false
        }
    }

    private fun sortGames(items: List<Game>, sortBy: String): List<Game> {
        return when (sortBy) {
            "title" -> items.sortedBy { it.titleZh.ifBlank { it.titleEn.ifBlank { it.titleRaw } } }
            "year_desc" -> items.sortedByDescending { it.releaseDate.take(4) }
            "year_asc" -> items.sortedBy { it.releaseDate.take(4) }
            "size" -> items.sortedByDescending { it.romSize }
            else -> items
        }
    }

    private fun updateLocalStats() {
        viewModelScope.launch {
            val stats = mutableMapOf<String, Pair<Int, Int>>()
            _games.value.forEach { g ->
                val code = g.platform?.code ?: return@forEach
                val (have, total) = stats[code] ?: (0 to 0)
                val newHave = if (downloadMgr.isLocal(g)) have + 1 else have
                stats[code] = (newHave to total + 1)
            }
            _localStats.value = stats
        }
    }

    fun isLocal(g: Game) = downloadMgr.isLocal(g)

    fun serverUrl(): String? = kotlinx.coroutines.runBlocking { prefs.currentServer() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val games by vm.games.collectAsState()
    val platforms by vm.platforms.collectAsState()
    val selected by vm.selectedPlatform.collectAsState()
    val search by vm.search.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val sort by vm.sort.collectAsState()
    val localStats by vm.localStats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NasGameHub", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (selected != null) {
                            Text(
                                "${platforms.find { it.code == selected }?.name ?: selected}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        BadgedBox(badge = {
                            val active = localStats.values.sumOf { it.first }
                            if (active > 0) Badge { Text("$active") }
                        }) {
                            Icon(Icons.Default.Download, "下载管理")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ===== 平台 Tab (横向滚动) =====
            PlatformTabBar(
                platforms = platforms,
                selected = selected,
                localStats = localStats,
                onSelect = { vm.setPlatform(it) },
            )

            // ===== 搜索 + 排序 =====
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { vm.setSearch(it) },
                    placeholder = { Text("搜索游戏...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(when (sort) {
                            "title" -> "名称"
                            "year_desc" -> "年份↓"
                            "year_asc" -> "年份↑"
                            "size" -> "大小"
                            else -> "排序"
                        }, fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("title" to "名称 A-Z", "year_desc" to "年份从新到旧",
                            "year_asc" to "年份从旧到新", "size" to "大小从大到小").forEach { (k, l) ->
                            DropdownMenuItem(
                                text = { Text(l) },
                                onClick = { vm.setSort(k); expanded = false },
                                leadingIcon = if (sort == k) {
                                    { Icon(Icons.Default.Check, null) }
                                } else null,
                            )
                        }
                    }
                }
            }

            // ===== 内容区 =====
            when {
                loading && games.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null && games.isEmpty() -> {
                    ErrorView(message = error!!, onRetry = vm::refresh)
                }
                games.isEmpty() -> {
                    EmptyView(selected)
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(games, key = { it.id }) { g ->
                            GameCard(
                                game = g,
                                serverUrl = vm.serverUrl(),
                                isLocal = vm.isLocal(g),
                                onClick = { onOpenDetail(g.id.toLong()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformTabBar(
    platforms: List<Platform>,
    selected: String?,
    localStats: Map<String, Pair<Int, Int>>,
    onSelect: (String?) -> Unit,
) {
    val allTotal = platforms.sumOf { it.gameCount }
    val allHave = localStats.values.sumOf { it.first }

    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 全部
        item {
            PlatformChip(
                code = "*",
                name = "全部",
                count = allTotal,
                have = allHave,
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(platforms, key = { it.id }) { p ->
            val (have, total) = localStats[p.code] ?: (0 to 0)
            PlatformChip(
                code = p.code,
                name = p.name,
                count = total.takeIf { it > 0 } ?: p.gameCount,
                have = have,
                selected = selected == p.code,
                onClick = { onSelect(p.code) },
            )
        }
    }
}

@Composable
private fun PlatformChip(
    code: String,
    name: String,
    count: Int,
    have: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (code == "*") "🎮" else platformEmoji(code),
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    name,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    if (have > 0) "$have / $count" else "$count",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private fun platformEmoji(code: String): String = when (code) {
    "FC", "SFC" -> "🕹️"
    "N64" -> "🎯"
    "GBA", "GBC", "GB" -> "🎲"
    "MD" -> "🐉"
    "PS1", "PS2", "PSP" -> "💿"
    "NDS", "3DS" -> "📱"
    "WII", "GC" -> "🎮"
    "DC" -> "💎"
    "MAME", "ARCADE", "NEOGEO" -> "🕹️"
    "J2ME" -> "📞"
    else -> "🎮"
}

@Composable
private fun GameCard(
    game: Game,
    serverUrl: String?,
    isLocal: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (game.cover.isNotBlank() && serverUrl != null) {
                    AsyncImage(
                        model = joinUrl(serverUrl, game.cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(
                            game.platform?.code ?: "?",
                            fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            game.titleRaw.take(28),
                            fontSize = 10.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            // 平台徽章 (左上)
            Surface(
                Modifier.padding(4.dp).align(Alignment.TopStart),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    game.platform?.code ?: "?",
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 状态徽章 (右下): 已下载/待下载/待刮削
            val (text, bg) = when {
                isLocal -> "✓ 本地" to MaterialTheme.colorScheme.primary
                game.scrapeStatus == "pending" -> "待刮削" to MaterialTheme.colorScheme.tertiary
                game.cloudSource == "115" -> "115" to Color(0xFFFF9800)
                else -> "" to Color.Transparent
            }
            if (text.isNotBlank()) {
                Surface(
                    Modifier.padding(4.dp).align(Alignment.BottomEnd),
                    color = bg.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text,
                        Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                    )
                }
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(
                game.titleZh.ifBlank { game.titleEn.ifBlank { game.titleRaw } },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                game.titleEn.ifBlank { game.releaseDate.take(4) },
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun EmptyView(platform: String?) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📦", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (platform != null) "该平台暂无游戏" else "游戏库为空",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (platform != null) "在服务器端添加 ROM 或切换其他平台"
                else "在服务器端扫描游戏目录, 然后刷新",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text("加载失败", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 4,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(4.dp))
                Text("重试")
            }
        }
    }
}
fun joinUrl(base: String?, path: String): String? {
    if (base == null) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val b = base.trimEnd('/')
    val p = if (path.startsWith("/")) path else "/$path"
    return b + p
}
