package com.cloudgamehub.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cloudgamehub.data.db.CloudDb
import com.cloudgamehub.data.media.MediaStore
import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.model.Platform
import com.cloudgamehub.data.prefs.PrefsStore
import com.cloudgamehub.data.repo.LibraryRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: LibraryRepo,
    private val db: CloudDb,
    private val media: MediaStore,
    private val prefs: PrefsStore,
) : ViewModel() {
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games = _games.asStateFlow()
    private val _platform = MutableStateFlow<String?>(null)
    val platform = _platform.asStateFlow()
    private val _search = MutableStateFlow("")
    val search = _search.asStateFlow()
    private val _favOnly = MutableStateFlow(false)
    val favOnly = _favOnly.asStateFlow()
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts = _counts.asStateFlow()
    private val _total = MutableStateFlow(0)
    val total = _total.asStateFlow()
    private val _rootPath = MutableStateFlow<String?>(null)
    val rootPath = _rootPath.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()
    private val _sort = MutableStateFlow("title")
    val sort = _sort.asStateFlow()

    init { reload(); viewModelScope.launch { _rootPath.value = prefs.romRootPath() } }

    fun reload() {
        viewModelScope.launch {
            _games.value = repo.query(
                platform = _platform.value, search = _search.value,
                favoriteOnly = _favOnly.value, sort = _sort.value, pageSize = 300,
            )
            _counts.value = repo.countByPlatform()
            _total.value = repo.countAll()
        }
    }

    fun setPlatform(code: String?) { _platform.value = code; reload() }
    fun setSearch(s: String) { _search.value = s; reload() }
    fun toggleFavOnly() { _favOnly.value = !_favOnly.value; reload() }
    fun setSort(s: String) { _sort.value = s; reload() }

    fun platforms(): List<Platform> = repo.platforms()

    fun toggleFavorite(g: Game) {
        viewModelScope.launch {
            repo.toggleFavorite(g)
            _games.value = _games.value.map { if (it.id == g.id) it.copy(favorite = !g.favorite) else it }
        }
    }

    fun coverFile(game: Game): File? = media.pathOf(game.coverFile)

    fun say(msg: String) { _msg.value = msg }
    fun clearMsg() { _msg.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenConnect: () -> Unit,
    onRescan: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val games by vm.games.collectAsState()
    val platform by vm.platform.collectAsState()
    val search by vm.search.collectAsState()
    val favOnly by vm.favOnly.collectAsState()
    val counts by vm.counts.collectAsState()
    val total by vm.total.collectAsState()
    val rootPath by vm.rootPath.collectAsState()
    val sort by vm.sort.collectAsState()
    val msg by vm.msg.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackHost.showSnackbar(it); vm.clearMsg() } }

    val gridState = rememberLazyGridState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("游戏库", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "$total 个游戏" + (rootPath?.let { " · $it" } ?: ""),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, "搜索")
                    }
                    IconButton(onClick = { vm.toggleFavOnly() }) {
                        Icon(
                            Icons.Default.Favorite,
                            "只看收藏",
                            tint = if (favOnly) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Default.Download, "下载管理")
                    }
                    IconButton(onClick = onRescan) {
                        Icon(Icons.Default.Refresh, "重新扫描 115")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            if (showSearch) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { vm.setSearch(it) },
                    placeholder = { Text("搜中文名 / 英文名 / 文件名") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )
            }

            // 平台筛选
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = platform == null && !favOnly,
                        onClick = { vm.setPlatform(null) },
                        label = { Text("全部 ${total}") },
                    )
                }
                items(vm.platforms().filter { (counts[it.code] ?: 0) > 0 }) { p ->
                    FilterChip(
                        selected = platform == p.code,
                        onClick = { vm.setPlatform(if (platform == p.code) null else p.code) },
                        label = { Text("${p.name} ${counts[p.code] ?: 0}") },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${games.size} 个",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    vm.setSort(if (sort == "title") "recent" else "title")
                }) {
                    Text(if (sort == "title") "按名称" else "按最近玩", fontSize = 12.sp)
                }
            }

            if (games.isEmpty()) {
                EmptyLibrary(onRescan = onRescan, hasFilter = search.isNotBlank() || platform != null)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(games, key = { it.id }) { g ->
                        GameCard(g, vm, onOpenDetail)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(g: Game, vm: LibraryViewModel, onOpen: (Long) -> Unit) {
    Column(Modifier.clickable { onOpen(g.id) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = remember(g.coverFile) { vm.coverFile(g) }
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = g.displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        g.platformCode,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 本地已下载标记
            if (g.isLocal) {
                Surface(
                    Modifier.align(Alignment.TopStart).padding(4.dp),
                    color = Color(0xFF2E7D32),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("⬇ 本地", Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        color = Color.White, fontSize = 9.sp)
                }
            }
            if (g.favorite) {
                Icon(
                    Icons.Default.Favorite, "收藏",
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                    tint = Color(0xFFE91E63),
                )
            }
            // 没刮出来的打个标
            if (g.scrapeStatus != "done") {
                Surface(
                    Modifier.align(Alignment.BottomStart).padding(4.dp),
                    color = Color(0xFFFF9800),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("待刮削", Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        color = Color.White, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            g.displayTitle,
            fontSize = 11.sp, lineHeight = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyLibrary(onRescan: () -> Unit, hasFilter: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hasFilter) "🔍" else "📭", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                if (hasFilter) "没有匹配的游戏" else "库里还是空的",
                fontWeight = FontWeight.Bold, fontSize = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasFilter) "换个关键词试试"
                else "ROM 全在 115 上, 扫描一下就能看到",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            if (!hasFilter) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRescan) { Text("去扫描 115") }
            }
        }
    }
}
