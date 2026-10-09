package com.cloudgamehub.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudgamehub.data.download.DownloadManager
import com.cloudgamehub.data.media.MediaStore
import com.cloudgamehub.data.model.Platform
import com.cloudgamehub.data.pan115.Pan115Client
import com.cloudgamehub.data.prefs.PrefsStore
import com.cloudgamehub.data.repo.LibraryRepo
import com.cloudgamehub.emulator.RetroArchManager
import com.cloudgamehub.util.SafFileHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PrefsStore,
    private val repo: LibraryRepo,
    private val pan: Pan115Client,
    private val ra: RetroArchManager,
    private val media: MediaStore,
    private val downloadMgr: DownloadManager,
) : ViewModel() {

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    // ---- 115 ----
    private val _cookieSet = MutableStateFlow(false)
    val cookieSet = _cookieSet.asStateFlow()
    val rootPath = MutableStateFlow<String?>(null)

    // ---- AI ----
    private val _aiOn = MutableStateFlow(false)
    val aiOn = _aiOn.asStateFlow()
    private val _aiBase = MutableStateFlow("")
    val aiBase = _aiBase.asStateFlow()
    private val _aiKey = MutableStateFlow("")
    val aiKey = _aiKey.asStateFlow()
    private val _aiModel = MutableStateFlow("")
    val aiModel = _aiModel.asStateFlow()
    private val _aiTesting = MutableStateFlow(false)
    val aiTesting = _aiTesting.asStateFlow()

    // ---- 存储 ----
    private val _romKind = MutableStateFlow(PrefsStore.RomStorageKind.SAF)
    val romKind = _romKind.asStateFlow()
    private val _romDisplay = MutableStateFlow("")
    val romDisplay = _romDisplay.asStateFlow()
    private val _concurrent = MutableStateFlow(1)
    val concurrent = _concurrent.asStateFlow()

    // ---- RA ----
    private val _raInstalls = MutableStateFlow<List<RetroArchManager.RaInstall>>(emptyList())
    val raInstalls = _raInstalls.asStateFlow()
    private val _raPkg = MutableStateFlow<String?>(null)
    val raPkg = _raPkg.asStateFlow()
    private val _autoCore = MutableStateFlow(true)
    val autoCore = _autoCore.asStateFlow()
    private val _abi = MutableStateFlow("")
    val abi = _abi.asStateFlow()
    private val _cores = MutableStateFlow<List<String>>(emptyList())
    val cores = _cores.asStateFlow()
    private val _coreBusy = MutableStateFlow<String?>(null)
    val coreBusy = _coreBusy.asStateFlow()
    private val _overrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val overrides = _overrides.asStateFlow()

    // ---- 统计 ----
    private val _stats = MutableStateFlow(Stats())
    val stats = _stats.asStateFlow()

    data class Stats(
        val games: Int = 0, val local: Int = 0,
        val cloudBytes: Long = 0, val localBytes: Long = 0, val mediaBytes: Long = 0,
    )

    private val _platforms = MutableStateFlow<List<Platform>>(emptyList())
    val platforms = _platforms.asStateFlow()

    init {
        viewModelScope.launch { reloadAll() }
    }

    fun reloadAll() {
        viewModelScope.launch {
            _cookieSet.value = !prefs.cached115Cookie().isNullOrBlank()
            rootPath.value = prefs.romRootPath()
            _aiOn.value = prefs.aiEnabled()
            _aiBase.value = prefs.aiBaseUrl()
            _aiKey.value = prefs.aiApiKeyMasked()
            _aiModel.value = prefs.aiModel()
            _romKind.value = prefs.romStorageKind()
            _romDisplay.value = prefs.romStorageDisplay()
            _concurrent.value = prefs.concurrentDownloads()
            _raPkg.value = prefs.raPackage()
            _autoCore.value = prefs.raAutoInstallCore()
            _abi.value = ra.abi()
            _cores.value = ra.installedCores()
            _overrides.value = prefs.allRaCoreOverrides()
            _platforms.value = repo.platforms()
            refreshStats()
        }
    }

    private fun refreshStats() {
        viewModelScope.launch {
            _stats.value = withContext(Dispatchers.IO) {
                Stats(
                    games = repo.countAll(),
                    local = repo.localCount(),
                    cloudBytes = repo.totalRomBytes(),
                    localBytes = repo.localCount().let { repo.localBytes() },
                    mediaBytes = media.totalBytes(),
                )
            }
        }
    }

    fun say(s: String) { _toast.value = s }

    // ---- 115 ----
    fun logout115() {
        viewModelScope.launch {
            pan.applyCookieString("")
            prefs.clear115Cookie()
            _cookieSet.value = false
            _toast.value = "已退出 115 账号"
        }
    }

    fun test115() {
        viewModelScope.launch {
            _toast.value = runCatching { repo.loginCheck() }
                .fold({ it }, { "连接失败: ${it.message}" })
        }
    }

    // ---- AI ----
    fun setAiOn(on: Boolean) { viewModelScope.launch { prefs.setAiEnabled(on); _aiOn.value = on } }
    fun setAiBase(v: String) { viewModelScope.launch { prefs.setAiBaseUrl(v) } }
    fun setAiKey(v: String) { viewModelScope.launch { prefs.setAiApiKey(v); _aiKey.value = prefs.aiApiKeyMasked() } }
    fun setAiModel(v: String) { viewModelScope.launch { prefs.setAiModel(v) } }

    /** 真发一个请求试试通不通 */
    fun testAi() {
        viewModelScope.launch {
            _aiTesting.value = true
            val base = prefs.aiBaseUrl(); val key = prefs.aiApiKey(); val model = prefs.aiModel()
            val fake = com.cloudgamehub.data.model.Game(
                romFilename = "Super Mario Bros. 3 (USA) (Rev 1).nes", platformCode = "FC",
            )
            val r = com.cloudgamehub.data.scrape.AiClient().identify(base, key, model, fake.romFilename, "FC")
            _aiTesting.value = false
            _toast.value = if (r != null) {
                "✅ 连上了! 试问结果: ${r.titleEn.ifBlank { r.titleZh }}"
            } else {
                "❌ 连不上. 检查 base_url (要带 /v1)、key 和模型名"
            }
        }
    }

    // ---- 存储 ----
    fun setSafTree(uri: android.net.Uri) {
        viewModelScope.launch {
            SafFileHelper.persistTreePermission(prefsAppCtx, uri)
            prefs.setSafStorage(uri)
            _romKind.value = prefs.romStorageKind()
            _romDisplay.value = prefs.romStorageDisplay()
            _toast.value = "已切换 ROM 目录"
        }
    }

    fun resetRomDir() {
        viewModelScope.launch {
            prefs.romStorageSafUri()?.let {
                runCatching { SafFileHelper.releaseTreePermission(prefsAppCtx, android.net.Uri.parse(it)) }
            }
            prefs.resetToDefault()
            _romKind.value = prefs.romStorageKind()
            _romDisplay.value = prefs.romStorageDisplay()
            _toast.value = "已改回 App 私有目录 (RetroArch 读不到, 慎用)"
        }
    }

    fun setConcurrent(n: Int) {
        viewModelScope.launch {
            prefs.setConcurrentDownloads(n); downloadMgr.updateConcurrency(n)
            _concurrent.value = n
        }
    }

    // ---- RA ----
    fun setRaPkg(pkg: String?) {
        viewModelScope.launch { prefs.setRaPackage(pkg); _raPkg.value = pkg?.takeIf { it.isNotBlank() } }
    }

    fun setAutoCore(on: Boolean) {
        viewModelScope.launch { prefs.setRaAutoInstallCore(on); _autoCore.value = on }
    }

    fun refreshRa() {
        viewModelScope.launch {
            _raInstalls.value = ra.installedRetroArch()
            _cores.value = ra.installedCores()
            _abi.value = ra.abi()
        }
    }

    fun installCore(platformCode: String) {
        if (_coreBusy.value != null) return
        viewModelScope.launch {
            _coreBusy.value = platformCode
            val r = runCatching { ra.ensureCore(platformCode) }
            _coreBusy.value = null
            _toast.value = r.fold({ "核心装好了: ${java.io.File(it).name}" },
                { "装核心失败: ${it.message}" })
            _cores.value = ra.installedCores()
        }
    }

    fun setCoreOverride(platformCode: String, core: String?) {
        viewModelScope.launch {
            ra.setCoreOverride(platformCode, core)
            _overrides.value = prefs.allRaCoreOverrides()
        }
    }

    fun recommendedCores(code: String) = ra.recommendedCores(code)
    fun coreStatus(code: String): String {
        val target = _overrides.value[code] ?: ra.recommendedCores(code).firstOrNull()
        return when {
            target == null -> ra.noCoreHint(code) ?: "无可用核心"
            _cores.value.contains(target) -> "已就绪 · $target"
            else -> "待下载 · $target"
        }
    }

    fun openRetroArch() {
        viewModelScope.launch {
            val pkg = ra.preferredPackage(_raPkg.value)
            if (pkg == null) {
                _toast.value = if (ra.openInstallPage()) "已跳转 RetroArch 官方下载页"
                else "没装 RetroArch, 请手动到 retroarch.com 下载"
            } else if (!ra.openRetroArch(pkg)) {
                _toast.value = "拉起 RetroArch 失败"
            }
        }
    }

    // ---- 清理 ----
    fun gcMedia() {
        viewModelScope.launch {
            val n = repo.gcMedia()
            _toast.value = "清理了 $n 个没用的封面文件"
            refreshStats()
        }
    }

    fun reconcileLocal() {
        viewModelScope.launch {
            val n = downloadMgr.reconcile()
            _toast.value = if (n == 0) "本地文件都还在" else "清掉了 $n 条失效记录"
            refreshStats()
        }
    }

    /**
     * SettingsViewModel 需要 Context 拿 contentResolver (SAF 授权要), 由 Screen 注入。
     * 用 lateinit 而不是可空 —— 可空的话每个调用点都得写 !!, 反而更容易漏。
     */
    lateinit var prefsAppCtx: android.content.Context
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogin115: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    vm.prefsAppCtx = ctx

    val toast by vm.toast.collectAsState()
    val cookieSet by vm.cookieSet.collectAsState()
    val rootPath by vm.rootPath.collectAsState()
    val aiOn by vm.aiOn.collectAsState()
    val aiBase by vm.aiBase.collectAsState()
    val aiKey by vm.aiKey.collectAsState()
    val aiModel by vm.aiModel.collectAsState()
    val aiTesting by vm.aiTesting.collectAsState()
    val romKind by vm.romKind.collectAsState()
    val romDisplay by vm.romDisplay.collectAsState()
    val concurrent by vm.concurrent.collectAsState()
    val raInstalls by vm.raInstalls.collectAsState()
    val raPkg by vm.raPkg.collectAsState()
    val autoCore by vm.autoCore.collectAsState()
    val abi by vm.abi.collectAsState()
    val cores by vm.cores.collectAsState()
    val coreBusy by vm.coreBusy.collectAsState()
    val overrides by vm.overrides.collectAsState()
    val platforms by vm.platforms.collectAsState()
    val stats by vm.stats.collectAsState()

    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackHost.showSnackbar(it) } }

    val safPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.setSafTree(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
        ) {

            // ================= 115 =================
            SectionCard("☁️ 115 网盘") {
                InfoRow("状态", if (cookieSet) "已登录" else "未登录", good = cookieSet)
                rootPath.value?.let { InfoRow("ROM 根目录", it) }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = onLogin115, modifier = Modifier.weight(1f)) {
                        Text(if (cookieSet) "重新登录" else "去登录")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.test115() }) { Text("测试连接") }
                }
                if (cookieSet) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.logout115() }) {
                        Text("退出 115 账号", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cookie 只存在本机, 不上传. 约一周过期, 过期后重新登录即可.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            // ================= AI 刮削 =================
            SectionCard("🤖 AI 刮削") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("用 AI 认游戏", fontSize = 14.sp)
                        Text(
                            "识别中文译名/年份/厂商/简介. 关掉也能用, 只是少了这些信息.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(checked = aiOn, onCheckedChange = { vm.setAiOn(it) })
                }
                if (aiOn) {
                    Spacer(Modifier.height(10.dp))
                    var base by remember(aiBase) { mutableStateOf(aiBase) }
                    OutlinedTextField(
                        value = base, onValueChange = { base = it; vm.setAiBase(it) },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    var key by remember(aiKey) { mutableStateOf("") }
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("API Key") },
                        placeholder = { Text(if (aiKey.isBlank()) "sk-..." else aiKey) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    var model by remember(aiModel) { mutableStateOf(aiModel) }
                    OutlinedTextField(
                        value = model, onValueChange = { model = it; vm.setAiModel(it) },
                        label = { Text("模型") },
                        placeholder = { Text("gpt-4o-mini / deepseek-chat / qwen-plus") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = { vm.setAiKey(key) }) { Text("保存 Key") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.testAi() }, enabled = !aiTesting) {
                            Text(if (aiTesting) "测试中…" else "测试连接")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "任何 OpenAI 兼容接口都行: OpenAI / DeepSeek / Kimi / 通义 / " +
                            "Ollama(base_url 填 http://localhost:11434/v1).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }

            // ================= ROM 存放 =================
            SectionCard("📁 ROM 存放位置") {
                InfoRow("方式", when (romKind) {
                    PrefsStore.RomStorageKind.DEFAULT -> "App 私有目录"
                    PrefsStore.RomStorageKind.SAF -> "外部目录 (推荐)"
                    PrefsStore.RomStorageKind.LEGACY_PATH -> "自定义路径"
                })
                Text(
                    romDisplay, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (romKind == PrefsStore.RomStorageKind.DEFAULT) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            "⚠️ RetroArch 在 Android 11+ 读不到这个目录, 点了会开不了游戏. " +
                                "请改成外部目录.",
                            Modifier.padding(10.dp), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = { safPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选目录")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.resetRomDir() }) { Text("恢复默认") }
                }
                Spacer(Modifier.height(12.dp))
                Text("同时下载数", fontSize = 13.sp)
                Row {
                    listOf(1, 2, 3).forEach { n ->
                        FilterChip(
                            selected = concurrent == n,
                            onClick = { vm.setConcurrent(n) },
                            label = { Text("$n") },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }

            // ================= RetroArch =================
            SectionCard("🕹️ RetroArch 集成") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (raInstalls.isEmpty()) "未检测到 RetroArch" else "已安装",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (raInstalls.isEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                        raInstalls.forEach {
                            Text(
                                "${it.pkg}  v${it.versionName}" +
                                    if (it.hasPlayActivity) "" else "  (不支持外部启动)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                    OutlinedButton(onClick = { vm.refreshRa() }) { Text("刷新") }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { vm.openRetroArch() }, modifier = Modifier.weight(1f)) {
                        Text(if (raInstalls.isEmpty()) "去装 RetroArch" else "打开 RetroArch")
                    }
                }
                Spacer(Modifier.height(8.dp))
                var pkg by remember(raPkg) { mutableStateOf(raPkg ?: "") }
                OutlinedTextField(
                    value = pkg, onValueChange = { pkg = it },
                    label = { Text("包名 (留空 = 自动探测)") },
                    placeholder = { Text("com.retroarch.aarch64") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row {
                    OutlinedButton(onClick = { vm.setRaPkg(pkg) }) { Text("保存") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pkg = ""; vm.setRaPkg(null) }) { Text("自动") }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动安装缺失核心", fontSize = 14.sp)
                        Text(
                            "架构 $abi · 本机已有核心 ${cores.size} 个",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(checked = autoCore, onCheckedChange = { vm.setAutoCore(it) })
                }

                if (raInstalls.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("平台核心", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    platforms.forEach { p ->
                        CoreRow(
                            code = p.code, name = p.name,
                            status = vm.coreStatus(p.code),
                            recommended = vm.recommendedCores(p.code),
                            override = overrides[p.code],
                            busy = coreBusy == p.code,
                            anyBusy = coreBusy != null,
                            onInstall = { vm.installCore(p.code) },
                            onPick = { c -> vm.setCoreOverride(p.code, c) },
                        )
                    }
                }
            }

            // ================= 存储统计 =================
            SectionCard("📊 存储") {
                InfoRow("库中游戏", "${stats.games} 个")
                InfoRow("115 云端总量", humanSize(stats.cloudBytes))
                InfoRow("已下到手机", "${stats.local} 个 · ${humanSize(stats.localBytes)}")
                InfoRow("封面缓存", humanSize(stats.mediaBytes))
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { vm.reconcileLocal() }, modifier = Modifier.weight(1f)) {
                        Text("校验本地文件", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.gcMedia() }, modifier = Modifier.weight(1f)) {
                        Text("清理封面", fontSize = 12.sp)
                    }
                }
            }

            // ================= 关于 =================
            SectionCard("ℹ️ 关于") {
                Text(
                    "CloudGameHub · v1.3.0\n" +
                        "单 App 私有游戏库 —— ROM 全在 115 上, 玩的时候才下到手机.\n" +
                        "不需要 NAS, 不需要服务器, 数据都在这台手机上.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, good: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label, Modifier.width(96.dp), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            value, Modifier.weight(1f), fontSize = 12.sp,
            color = if (good) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoreRow(
    code: String, name: String, status: String,
    recommended: List<String>, override: String?,
    busy: Boolean, anyBusy: Boolean,
    onInstall: () -> Unit, onPick: (String?) -> Unit,
) {
    var expanded by remember(code) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(code, Modifier.width(52.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val nameModifier = if (recommended.isNotEmpty()) {
                Modifier.weight(1f).clickable { expanded = !expanded }
            } else {
                Modifier.weight(1f)
            }
            Column(nameModifier) {
                Text(name, fontSize = 11.sp)
                Text(
                    status, fontSize = 10.sp,
                    color = if (status.startsWith("已就绪")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (expanded && recommended.isNotEmpty()) {
                IconButton(onClick = { expanded = false }) {
                    Icon(Icons.Default.ExpandLess, "收起", Modifier.size(18.dp))
                }
            } else {
                TextButton(onClick = onInstall, enabled = !anyBusy) {
                    Text(if (busy) "装…" else "装核心", fontSize = 11.sp)
                }
            }
        }
        if (expanded && recommended.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                recommended.forEach { c ->
                    FilterChip(
                        selected = override == c,
                        onClick = { onPick(c) },
                        label = { Text(c, fontSize = 10.sp) },
                    )
                }
                if (override != null) {
                    FilterChip(
                        selected = false, onClick = { onPick(null) },
                        label = { Text("默认", fontSize = 10.sp) },
                    )
                }
            }
        }
    }
}
