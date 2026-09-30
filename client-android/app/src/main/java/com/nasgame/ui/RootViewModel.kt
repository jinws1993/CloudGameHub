package com.nasgame.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasgame.data.repo.NasGameRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val repo: NasGameRepo,
) : ViewModel() {
    init {
        viewModelScope.launch { repo.init() }
    }
    val isLoggedIn = repo.isLoggedIn
}
