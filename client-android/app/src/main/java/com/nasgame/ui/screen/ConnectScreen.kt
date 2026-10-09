package com.nasgame.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.pan115.Pan115Exception
import com.nasgame.data.pan115.Pan115Client
import com.nasgame.data.pan115.QrLoginService
import com.nasgame.data.pan115.QrRenderer
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.LibraryRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val pan: Pan115Client,
    private val prefs: PrefsStore,
    private val repo: LibraryRepo,
    private val qr: QrLoginService,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()
    private val _ok = MutableStateFlow(false)
    val ok = _ok.asStateFlow()
    private val _qr = MutableStateFlow<QrLoginService.QrSession?>(null)
    val qrSession = _qr.asStateFlow()
    private val _qrStatus = MutableStateFlow<QrLoginService.Status?>(null)
    val qrStatus = _qrStatus.asStateFlow()
    private val _qrBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val qrBitmap = _qrBitmap.asStateFlow()

    private var pollJob: Job? = null

    /** 已经有 cookie 就直接跳过登录页 */
    suspend fun hasCookie(): Boolean = !prefs.cached115Cookie().isNullOrBlank()

    suspend fun verifyStored(): Boolean = try {
        pan.restore()
        repo.loginCheck()
        true
    } catch (_: Exception) {
        false
    }

    /** Cookie 登录 */
    fun loginWithCookie(cookie: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true; _msg.value = null
            try {
                pan.applyCookieString(cookie)
                val info = repo.loginCheck()      // 立刻验证, 别等用户点进去才发现过期
                prefs.save115Cookie(pan.cookieHeader())
                _msg.value = info
                _ok.value = true
            } catch (e: Exception) {
                _msg.value = when (e) {
                    is Pan115Exception -> e.message
                    else -> "连接失败: ${e.message}"
                }
                _ok.value = false
            } finally {
                _busy.value = false
            }
        }
    }

    /** 扫码登录 */
    fun startQr() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true; _msg.value = null; _qrStatus.value = null
            try {
                val s = qr.createSession()
                _qr.value = s
                _qrBitmap.value = QrRenderer.render(s.qrUrl)
                _busy.value = false
                startPolling(s)
            } catch (e: Exception) {
                _msg.value = "获取二维码失败: ${e.message}"
                _busy.value = false
            }
        }
    }

    private fun startPolling(s: QrLoginService.QrSession) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            try {
                val cookie = qr.awaitLogin(s) { st -> _qrStatus.value = st }
                pan.applyCookieString(cookie)
                val info = repo.loginCheck()
                prefs.save115Cookie(pan.cookieHeader())
                _qrStatus.value = QrLoginService.Status.CONFIRMED
                _msg.value = info
                _ok.value = true
            } catch (e: Exception) {
                _msg.value = when (e) {
                    is Pan115Exception -> e.message
                    else -> "扫码失败: ${e.message}"
                }
                _qrStatus.value = QrLoginService.Status.EXPIRED
            }
        }
    }

    fun cancelQr() {
        pollJob?.cancel()
        _qr.value = null
        _qrBitmap.value = null
        _qrStatus.value = null
    }

    fun logout() {
        viewModelScope.launch {
            pollJob?.cancel()
            pan.applyCookieString("")
            prefs.clear115Cookie()
            _ok.value = false
            _msg.value = "已退出 115 账号"
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

/**
 * 115 登录页。两种方式:
 * - 扫码 (推荐): 生成二维码, 用 115 App 扫
 * - Cookie: 从浏览器 F12 里复制, 适合已经有 cookie 的人
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    vm: ConnectViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val busy by vm.busy.collectAsState()
    val msg by vm.msg.collectAsState()
    val ok by vm.ok.collectAsState()
    val qrSession by vm.qrSession.collectAsState()
    val qrBitmap by vm.qrBitmap.collectAsState()
    val qrStatus by vm.qrStatus.collectAsState()

    var cookieText by remember { mutableStateOf("") }
    var showCookie by remember { mutableStateOf(false) }

    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(ok) { if (ok) onConnected() }
    LaunchedEffect(msg) { msg?.let { snackHost.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("📦", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "NasGameHub",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "接上你的 115 网盘, ROM 留在云上, 玩的时候才下到手机",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(28.dp))

            // ---------- 扫码 ----------
            if (qrSession == null) {
                Button(
                    onClick = { vm.startQr() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.QrCode2, null)
                    Spacer(Modifier.width(8.dp))
                    Text("扫码登录 (推荐)", fontSize = 16.sp)
                }
            } else {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "115 登录二维码",
                                modifier = Modifier.size(220.dp),
                            )
                        } ?: CircularProgressIndicator()

                        Spacer(Modifier.height(12.dp))
                        Text(
                            when (qrStatus) {
                                QrLoginService.Status.SCANNED -> "已扫描, 请在 115 App 上点确认"
                                QrLoginService.Status.WAITING -> "请用 115 App 扫描这个二维码"
                                QrLoginService.Status.CONFIRMED -> "登录成功!"
                                QrLoginService.Status.EXPIRED -> "二维码已过期"
                                QrLoginService.Status.FAILED -> "出错了"
                                null -> "正在生成二维码…"
                            },
                            fontSize = 14.sp,
                        )
                        if (qrStatus == QrLoginService.Status.WAITING) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                qrSession!!.qrUrl,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "同一台手机? 用另一台设备扫码, 或复制链接到 115 App 打开",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.cancelQr() }) { Text("换一种方式") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text(
                    "  或者用 Cookie  ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                HorizontalDivider(Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))

            // ---------- Cookie ----------
            OutlinedTextField(
                value = cookieText,
                onValueChange = { cookieText = it },
                label = { Text("115 Cookie") },
                placeholder = { Text("UID=xxx; CID=xxx; SEID=xxx") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                visualTransformation = if (showCookie) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showCookie = !showCookie }) {
                        Icon(
                            if (showCookie) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            "显示/隐藏",
                        )
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "获取方法: 浏览器登录 115.com → F12 → Network → 任一请求的 Request Headers → " +
                    "复制整条 Cookie (只要 UID / CID / SEID / KID 那几段)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.loginWithCookie(cookieText.trim()) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !busy && cookieText.isNotBlank(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("连接 115", fontSize = 15.sp)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "🔒 Cookie 只存在这台手机上, 不上传到任何地方. 大约一周过期, 过期后重新连一次即可.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
