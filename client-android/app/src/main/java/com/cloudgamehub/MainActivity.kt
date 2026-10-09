package com.cloudgamehub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudgamehub.ui.CloudGameHubApp
import com.cloudgamehub.ui.RootViewModel
import com.cloudgamehub.ui.theme.CloudGameHubTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 App 入口。
 *
 * 启动时先问一句"115 登录了没": 没登录或者 cookie 过期, 就先落在连接页,
 * 免得用户打开看到一个空库不知道该干嘛。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // cookie 载入和登录态检查都在 RootViewModel.bootstrap() 里做 (需要协程)
        rootViewModel.bootstrap()

        setContent {
            CloudGameHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val has115 by rootViewModel.has115.collectAsStateWithLifecycle()
                    // null = 还在检查, 先按"需要登录"渲染
                    CloudGameHubApp(startWithConnect = has115 != true)
                }
            }
        }
    }
}
