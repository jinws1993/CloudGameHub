package com.nasgame.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.pan115.Pan115Client
import com.nasgame.data.prefs.PrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App 级状态: 有没有登录 115、ROM 根目录在哪 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val prefs: PrefsStore,
    private val pan: Pan115Client,
) : ViewModel() {

    private val _has115 = MutableStateFlow<Boolean?>(null)
    val has115 = _has115.asStateFlow()

    private val _rootPath = MutableStateFlow<String?>(null)
    val rootPath = _rootPath.asStateFlow()

    /**
     * 启动时检查一次登录态。
     * 没 cookie / cookie 过期 → false, MainActivity 会先落在连接页。
     */
    fun bootstrap() {
        viewModelScope.launch {
            _rootPath.value = prefs.romRootPath()
            val cookie = prefs.cached115Cookie()
            if (cookie.isNullOrBlank()) {
                _has115.value = false
                return@launch
            }
            pan.restore()
            _has115.value = runCatching { pan.checkLogin() }.isSuccess
        }
    }

    fun refreshRoot() {
        viewModelScope.launch { _rootPath.value = prefs.romRootPath() }
    }
}
