package com.nasgame.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.data.repo.NasGameRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo: NasGameRepo,
    private val prefs: PrefsStore,
) : ViewModel() {
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** 上次连接的服务器地址 (不论登录状态) — 启动时回填 */
    suspend fun lastServer(): String = prefs.lastServerUrl().orEmpty()

    /** 当前已登录账号的用户名 (仅当勾了记住才返回) */
    suspend fun savedUsername(): String = prefs.username.firstOrNull().orEmpty()

    /** 已记住的密码 (仅当勾了记住才返回) */
    suspend fun savedPassword(): String? = prefs.savedPassword()

    /** 当前是否已勾选记住密码 */
    suspend fun isRemembered(): Boolean = prefs.isRememberMe()

    suspend fun login(server: String, username: String, password: String, remember: Boolean): Boolean {
        loading = true; error = null
        return try {
            repo.login(server, username, password, remember); true
        } catch (e: Exception) {
            error = e.message; false
        } finally { loading = false }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val vm: LoginViewModel = hiltViewModel()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 从 Prefs 读初始值 (避免硬编码 default)
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    // 首次渲染时一次性预填
    LaunchedEffect(Unit) {
        if (!loaded) {
            server = vm.lastServer()
            username = vm.savedUsername()
            rememberMe = vm.isRemembered()
            if (rememberMe) password = vm.savedPassword().orEmpty()
            loaded = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.SportsEsports, null, modifier = Modifier.size(72.dp),
                 tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("NASGame", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                 color = MaterialTheme.colorScheme.primary)
            Text("你的私人游戏库管家", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(36.dp))

            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.100:14322") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            // 记住密码 checkbox — 默认未勾, 勾选后下次自动填
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = { rememberMe = it },
                )
                Text(
                    "记住密码",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "下次自动填账号密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (vm.login(server, username, password, rememberMe)) onLoggedIn()
                    }
                },
                enabled = !vm.loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (vm.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp),
                                                            color = MaterialTheme.colorScheme.onPrimary)
                else Text("登录")
            }
        }
    }
}