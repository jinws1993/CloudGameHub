package com.nasgame

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
import com.nasgame.data.pan115.Pan115Client
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.ui.NasGameApp
import com.nasgame.ui.RootViewModel
import com.nasgame.ui.theme.NASGameTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 App 入口。
 *
 * 启动时先问一句"115 登录了没": 没登录或者 cookie 过期, 就先落在连接页,
 * 免得用户打开看到一个空库不知道该干嘛。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PrefsStore
    @Inject lateinit var pan: Pan115Client

    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pan.restore()          // 把保存的 115 cookie 灌进内存 jar
        rootViewModel.bootstrap()

        setContent {
            NASGameTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val has115 by rootViewModel.has115.collectAsStateWithLifecycle()
                    // null = 还在检查, 先按"需要登录"渲染
                    NasGameApp(startWithConnect = has115 != true)
                }
            }
        }
    }
}
