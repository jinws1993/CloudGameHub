package com.nasgame.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.api.Platform
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.NasGameRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PrefsStore,
    private val repo: NasGameRepo,
) : ViewModel() {
    private val _platforms = MutableStateFlow<List<Platform>>(emptyList())
    val platforms = _platforms.asStateFlow()
    private val _emuMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val emuMap = _emuMap.asStateFlow()
    private val _server = MutableStateFlow<String?>(null)
    val server = _server.asStateFlow()

    init {
        viewModelScope.launch {
            _emuMap.value = prefs.allEmuPackages()
            _server.value = prefs.currentServer()
            try { _platforms.value = repo.platforms() } catch (_: Exception) {}
        }
    }

    fun setEmu(platCode: String, pkg: String) {
        viewModelScope.launch {
            prefs.setEmulatorPkg(platCode, pkg)
            _emuMap.value = prefs.allEmuPackages()
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.logout()
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val platforms by vm.platforms.collectAsState()
    val emuMap by vm.emuMap.collectAsState()
    val server by vm.server.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            ElevatedCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(server ?: "(未连接)",
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }

            Text(
                "模拟器包名映射",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "为每个平台填入已安装的模拟器包名。留空则自动检测常用包。",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))

            platforms.forEach { p ->
                val v = emuMap[p.code] ?: ""
                var text by remember(v) { mutableStateOf(v) }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        vm.setEmu(p.code, it)
                    },
                    label = { Text("${p.code} - ${p.name}") },
                    placeholder = { Text("com.example.emulator") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.logout(onBack) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("退出登录") }
            Spacer(Modifier.height(40.dp))
        }
    }
}
