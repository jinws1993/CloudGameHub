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
import com.nasgame.data.repo.NasGameRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(private val repo: NasGameRepo) : ViewModel() {
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    suspend fun login(server: String, username: String, password: String): Boolean {
        loading = true; error = null
        return try {
            repo.login(server, username, password); true
        } catch (e: Exception) {
            error = e.message; false
        } finally { loading = false }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val vm: LoginViewModel = hiltViewModel()
    var server by remember { mutableStateOf("http://192.168.1.100:14322") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("admin123") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (vm.login(server, username, password)) onLoggedIn()
                    }
                },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (vm.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp),
                                                          color = MaterialTheme.colorScheme.onPrimary)
                else Text("登 录")
            }
        }
    }
}
