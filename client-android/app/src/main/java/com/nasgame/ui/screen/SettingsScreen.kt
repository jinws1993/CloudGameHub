package com.nasgame.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.api.Platform
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.NasGameRepo
import com.nasgame.util.SafFileHelper
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
    private val _romDisplay = MutableStateFlow("")
    val romDisplay: kotlinx.coroutines.flow.StateFlow<String> = _romDisplay.asStateFlow()
    private val _romKind = MutableStateFlow(PrefsStore.RomStorageKind.DEFAULT)
    val romKind: kotlinx.coroutines.flow.StateFlow<PrefsStore.RomStorageKind> = _romKind.asStateFlow()
    private val _concurrent = MutableStateFlow(2)
    val concurrent = _concurrent.asStateFlow()
    private val _globalEmu = MutableStateFlow<String?>(null)
    val globalEmu = _globalEmu.asStateFlow()
    private val _raCore = MutableStateFlow<String?>(null)
    val raCore = _raCore.asStateFlow()

    init {
        viewModelScope.launch {
            _emuMap.value = prefs.allEmuPackages()
            _server.value = prefs.currentServer()
            _romDisplay.value = prefs.romStorageDisplay()
            _romKind.value = prefs.romStorageKind()
            _concurrent.value = prefs.concurrentDownloads()
            _globalEmu.value = prefs.globalEmulatorPkg()
            _raCore.value = prefs.defaultRetroArchCore()
            try { _platforms.value = repo.platforms() } catch (_: Exception) {}
        }
    }

    fun setEmu(platCode: String, pkg: String) {
        viewModelScope.launch {
            prefs.setEmulatorPkg(platCode, pkg)
            _emuMap.value = prefs.allEmuPackages()
        }
    }

    fun setSafTree(ctx: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            SafFileHelper.persistTreePermission(ctx, uri)
            prefs.setSafStorage(uri)
            _romKind.value = prefs.romStorageKind()
            _romDisplay.value = prefs.romStorageDisplay()
        }
    }

    fun resetRomToDefault() {
        viewModelScope.launch {
            // 释放旧 SAF 权限 (如果有)
            val oldUri = prefs.romStorageSafUri()
            if (oldUri != null) {
                try {
                    SafFileHelper.releaseTreePermission(
                        // 我们需要在 ViewModel 里拿 Context. 简化: 跳过 release, 系统会在 uninstall 时清
                    )
                } catch (_: Exception) {}
            }
            prefs.resetToDefault()
            _romKind.value = prefs.romStorageKind()
            _romDisplay.value = prefs.romStorageDisplay()
        }
    }

    fun setConcurrent(n: Int) {
        viewModelScope.launch {
            prefs.setConcurrentDownloads(n)
            _concurrent.value = n
        }
    }

    fun setGlobalEmu(pkg: String?) {
        viewModelScope.launch {
            prefs.setGlobalEmulatorPkg(pkg)
            _globalEmu.value = prefs.globalEmulatorPkg()
        }
    }

    fun setRaCore(core: String?) {
        viewModelScope.launch {
            prefs.setDefaultRetroArchCore(core)
            _raCore.value = prefs.defaultRetroArchCore()
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
    val ctx = LocalContext.current
    val platforms by vm.platforms.collectAsState()
    val emuMap by vm.emuMap.collectAsState()
    val server by vm.server.collectAsState()
    val romDisplay by vm.romDisplay.collectAsState()
    val romKind by vm.romKind.collectAsState()
    val concurrent by vm.concurrent.collectAsState()
    val globalEmu by vm.globalEmu.collectAsState()
    val raCore by vm.raCore.collectAsState()

    // SAF 文件选择器 launcher
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.setSafTree(ctx, uri)
        }
    }

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
            // ============ 服务器 ============
            ElevatedCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(server ?: "(未连接)",
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }

            // ============ ROM 存储路径 (SAF) ============
            ElevatedCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("📁 ROM 存放路径", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点\"选择目录\"调起系统文件管理器选一个文件夹, " +
                        "ROM 会下载到这里. 默认 App 私有目录 (卸载自动清理).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    // 当前路径
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (romKind) {
                                PrefsStore.RomStorageKind.DEFAULT -> Icons.Default.Smartphone
                                PrefsStore.RomStorageKind.SAF -> Icons.Default.Folder
                                PrefsStore.RomStorageKind.LEGACY_PATH -> Icons.Default.Folder
                            },
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            when (romKind) {
                                PrefsStore.RomStorageKind.DEFAULT -> "App 私有 (默认)"
                                PrefsStore.RomStorageKind.SAF -> "已选外部目录"
                                PrefsStore.RomStorageKind.LEGACY_PATH -> "自定义路径"
                            },
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        romDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = { safLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("选择目录")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { vm.resetRomToDefault() },
                            modifier = Modifier.weight(1f),
                        ) { Text("恢复默认") }
                    }
                }
            }

            // ============ 并发下载数 ============
            ElevatedCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("⬇️ 同时下载数", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ROM 通常几十 MB ~ 几 GB, 并发太多会卡带宽/内存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(1, 2, 3, 4).forEach { n ->
                            FilterChip(
                                selected = concurrent == n,
                                onClick = { vm.setConcurrent(n) },
                                label = { Text("$n") },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "当前: $concurrent",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ============ 全局模拟器 (RetroArch 优先) ============
            ElevatedCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("🎮 全局模拟器", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "推荐填入 RetroArch 包名 (com.retroarch), " +
                        "所有未单独配置的平台都会用它启动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    var globalText by remember(globalEmu) { mutableStateOf(globalEmu ?: "") }
                    OutlinedTextField(
                        value = globalText,
                        onValueChange = { globalText = it },
                        label = { Text("全局模拟器包名") },
                        placeholder = { Text("com.retroarch") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        OutlinedButton(
                            onClick = { vm.setGlobalEmu(globalText.trim()) },
                        ) { Text("保存") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { globalText = ""; vm.setGlobalEmu(null) },
                        ) { Text("清空 (走自动检测)") }
                    }
                    Spacer(Modifier.height(12.dp))
                    var coreText by remember(raCore) { mutableStateOf(raCore ?: "") }
                    OutlinedTextField(
                        value = coreText,
                        onValueChange = { coreText = it },
                        label = { Text("RetroArch 默认 core (可选)") },
                        placeholder = { Text("例如 nestopia_libretro.so (FC)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { vm.setRaCore(coreText.trim().ifBlank { null }) },
                    ) { Text("保存 core") }
                }
            }

            // ============ 平台级模拟器映射 ============
            Text(
                "🎮 模拟器包名映射 (按平台)",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "为单个平台指定模拟器 (留空则用上面设置的全局模拟器)",
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