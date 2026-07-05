package com.hilamalu.oshixcollector.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.FirebaseAvailability
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val secureSettings = SecureSettings(application)
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val repository = MediaRepository(application)

    val isFirebaseConfigured: Boolean = FirebaseAvailability.isConfigured(application)

    val cloudBackupEnabled: StateFlow<Boolean> = cloudBackupSettings.isEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var xBearerToken by mutableStateOf(secureSettings.xBearerToken.orEmpty())
    var r2BucketName by mutableStateOf(secureSettings.r2BucketName.orEmpty())
    var r2AccountId by mutableStateOf(secureSettings.r2AccountId.orEmpty())
    var r2AccessKeyId by mutableStateOf(secureSettings.r2AccessKeyId.orEmpty())
    var r2SecretAccessKey by mutableStateOf(secureSettings.r2SecretAccessKey.orEmpty())
    var r2Endpoint by mutableStateOf(secureSettings.r2Endpoint.orEmpty())

    var signedInEmail by mutableStateOf(googleAuthManager.currentUser?.email)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var saved by mutableStateOf(false)
        private set

    fun save() {
        secureSettings.xBearerToken = xBearerToken.ifBlank { null }
        secureSettings.r2BucketName = r2BucketName.ifBlank { null }
        secureSettings.r2AccountId = r2AccountId.ifBlank { null }
        secureSettings.r2AccessKeyId = r2AccessKeyId.ifBlank { null }
        secureSettings.r2SecretAccessKey = r2SecretAccessKey.ifBlank { null }
        secureSettings.r2Endpoint = r2Endpoint.ifBlank { null }
        saved = true
    }

    fun dismissSaved() {
        saved = false
    }

    fun dismissError() {
        errorMessage = null
    }

    fun setCloudBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                try {
                    val user = googleAuthManager.signIn()
                    signedInEmail = user.email
                    cloudBackupSettings.setEnabled(true)
                    repository.backupExistingIfEnabled()
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            } else {
                cloudBackupSettings.setEnabled(false)
            }
        }
    }
}
