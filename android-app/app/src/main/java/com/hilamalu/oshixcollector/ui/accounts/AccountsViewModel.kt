package com.hilamalu.oshixcollector.ui.accounts

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)

    val accounts: StateFlow<List<TargetAccountEntity>> = repository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Web版アカウント一覧の「収集画像数」（media_count）相当: xUserId→枚数。 */
    val mediaCountByUserId: StateFlow<Map<String, Int>> =
        repository.media
            .map { assets -> assets.groupingBy { it.xUserId }.eachCount() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun addAccount(screenName: String) {
        viewModelScope.launch {
            try {
                repository.addAccount(screenName)
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun removeAccount(screenName: String) {
        viewModelScope.launch { repository.removeAccount(screenName) }
    }

    fun dismissError() {
        errorMessage = null
    }
}
