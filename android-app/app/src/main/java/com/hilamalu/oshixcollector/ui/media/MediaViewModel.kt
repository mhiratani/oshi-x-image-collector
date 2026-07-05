package com.hilamalu.oshixcollector.ui.media

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MediaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)

    val media: StateFlow<List<MediaAssetEntity>> = repository.media
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var isRefreshing by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            errorMessage = null
            try {
                repository.refreshAll()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isRefreshing = false
            }
        }
    }

    fun dismissError() {
        errorMessage = null
    }
}
