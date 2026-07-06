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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)

    val accounts: StateFlow<List<TargetAccountEntity>> = repository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isBackfilling by mutableStateOf(false)
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

    /** 「過去の投稿を読み込む」ボタンから呼ぶ。全追跡アカウント（backfillDone==falseのもの）を対象に遡り取得する。 */
    fun backfillAll() {
        if (isBackfilling) return
        viewModelScope.launch {
            isBackfilling = true
            try {
                repository.backfillAll()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isBackfilling = false
            }
        }
    }

    fun dismissError() {
        errorMessage = null
    }
}


