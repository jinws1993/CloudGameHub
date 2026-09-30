package com.nasgame.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

    init { refresh() }

    fun setPlatform(code: String?) {
        _selectedPlatform.value = code
        loadGames()
    }

    fun setSearch(q: String) {
        _search.value = q
        loadGames()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _platforms.value = repo.platforms()
                loadGames()
            } catch (_: Exception) { }
            _loading.value = false
        }
    }

    private fun loadGames() {
        viewModelScope.launch {
            try {
                val r = repo.games(
                    platform = _selectedPlatform.value,
                    search = _search.value.ifBlank { null },
                )
                _games.value = r.items
            } catch (_: Exception) { }
        }
    }

    fun serverUrl(): String? = kotlinx.coroutines.runBlocking { prefs.currentServer() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val games by vm.games.collectAsState()
    val platforms by vm.platforms.collectAsState()
    val selected by vm.selectedPlatform.collectAsState()
    val search by vm.search.collectAsState()
    val loading by vm.loading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏库") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Platform filter chips
            ScrollableTabRow(
                selectedTabIndex = platforms.indexOfFirst { it.code == selected } + 1,
                edgePadding = 8.dp,
            ) {
                Tab(selected = selected == null,
                    onClick = { vm.setPlatform(null) },
                    text = { Text("全部") })
                platforms.forEach { p ->
                    Tab(selected = selected == p.code,
                        onClick = { vm.setPlatform(p.code) },
                        text = { Text("${p.code} (${p.gameCount})") })
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { vm.setSearch(it) },
                placeholder = { Text("搜索游戏...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (games.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无游戏", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(games, key = { it.id }) { g ->
                        GameCard(g, vm.serverUrl()) { onOpenDetail(g.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(g: Game, serverUrl: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (g.cover.isNotBlank() && serverUrl != null) {
                    AsyncImage(
                        model = serverUrl + g.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(g.platform?.code ?: "?",
                             fontSize = 24.sp, fontWeight = FontWeight.Bold,
                             color = MaterialTheme.colorScheme.primary)
                        Text(
                            g.titleRaw.take(30),
                            fontSize = 10.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                if (g.scrapeStatus == "pending") {
                    Box(
                        Modifier.padding(4.dp)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                                        RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text("待刮削", fontSize = 9.sp)
                    }
                }
            }
            Column(Modifier.padding(6.dp)) {
                Text(
                    g.titleZh.ifBlank { g.titleEn.ifBlank { g.titleRaw } },
                    fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${g.platform?.code ?: ""} · ${g.releaseDate.take(4)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
