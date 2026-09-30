package com.nasgame.ui.screen

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nasgame.stream.StreamClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(gameId: Long, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("初始化...") }
    var client: StreamClient? by remember { mutableStateOf(null) }

    LaunchedEffect(gameId) {
        // Initialize stream client - actual implementation in StreamClient.kt
        status = "正在连接服务器..."
        // client = StreamClient(ctx, gameId, ...)
        // client.start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("串流游玩") },
                navigationIcon = {
                    IconButton(onClick = {
                        client?.stop()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Video surface
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            // On-screen controls (placeholder)
            Surface(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf("A", "B", "X", "Y", "START", "SELECT").forEach { b ->
                        OutlinedButton(onClick = {
                            client?.sendKey(b)
                        }) { Text(b) }
                    }
                }
            }
        }
    }
}
