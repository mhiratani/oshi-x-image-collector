package com.hilamalu.oshixcollector.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.MediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val cloudBackupEnabled by viewModel.cloudBackupEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(context.getString(R.string.settings_sign_in_failed, message))
            viewModel.dismissError()
        }
    }
    LaunchedEffect(viewModel.saved) {
        if (viewModel.saved) {
            snackbarHostState.showSnackbar(context.getString(R.string.settings_saved))
            viewModel.dismissSaved()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_intro_description),
                    modifier = Modifier.padding(16.dp)
                )
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_x_section), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = viewModel.xBearerToken,
                        onValueChange = { viewModel.xBearerToken = it },
                        label = { Text(stringResource(R.string.settings_x_bearer_token)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_cloud_backup_section), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_cloud_backup_description), modifier = Modifier.padding(vertical = 8.dp))

                    Text(stringResource(R.string.settings_r2_section), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = viewModel.r2BucketName,
                        onValueChange = { viewModel.r2BucketName = it },
                        label = { Text(stringResource(R.string.settings_r2_bucket)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.r2AccountId,
                        onValueChange = { viewModel.r2AccountId = it },
                        label = { Text(stringResource(R.string.settings_r2_account_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.r2AccessKeyId,
                        onValueChange = { viewModel.r2AccessKeyId = it },
                        label = { Text(stringResource(R.string.settings_r2_access_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.r2SecretAccessKey,
                        onValueChange = { viewModel.r2SecretAccessKey = it },
                        label = { Text(stringResource(R.string.settings_r2_secret_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.r2Endpoint,
                        onValueChange = { viewModel.r2Endpoint = it },
                        label = { Text(stringResource(R.string.settings_r2_endpoint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )

                    Text(stringResource(R.string.settings_firebase_section), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.settings_firebase_description), modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = viewModel.firebaseApiKey,
                        onValueChange = { viewModel.firebaseApiKey = it },
                        label = { Text(stringResource(R.string.settings_firebase_api_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.firebaseProjectId,
                        onValueChange = { viewModel.firebaseProjectId = it },
                        label = { Text(stringResource(R.string.settings_firebase_project_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.firebaseAppId,
                        onValueChange = { viewModel.firebaseAppId = it },
                        label = { Text(stringResource(R.string.settings_firebase_app_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.firebaseWebClientId,
                        onValueChange = { viewModel.firebaseWebClientId = it },
                        label = { Text(stringResource(R.string.settings_firebase_web_client_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )

                    Button(onClick = { viewModel.save() }, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.settings_save))
                    }

                    if (!viewModel.isFirebaseConfigured) {
                        Text(
                            stringResource(R.string.settings_cloud_backup_firebase_not_configured),
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Switch(checked = cloudBackupEnabled, onCheckedChange = { viewModel.setCloudBackupEnabled(it) })
                        }
                        viewModel.signedInEmail?.let { email ->
                            Text(stringResource(R.string.settings_cloud_backup_signed_in_as, email))
                        }

                        Button(
                            onClick = { viewModel.restoreFromCloud() },
                            enabled = viewModel.restoreState !is RestoreUiState.InProgress,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_cloud_restore_button))
                        }

                        when (val state = viewModel.restoreState) {
                            is RestoreUiState.InProgress -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Text(
                                        when (val progress = state.progress) {
                                            MediaRepository.RestoreProgress.FetchingMetadata ->
                                                stringResource(R.string.settings_cloud_restore_fetching)
                                            is MediaRepository.RestoreProgress.DownloadingImages ->
                                                stringResource(
                                                    R.string.settings_cloud_restore_downloading,
                                                    progress.completed,
                                                    progress.total
                                                )
                                        },
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                            is RestoreUiState.Success -> LaunchedEffect(state) {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.settings_cloud_restore_success,
                                        state.result.accountsRestored,
                                        state.result.mediaRowsRestored,
                                        state.result.imagesDownloaded,
                                        state.result.imagesFailed
                                    )
                                )
                                viewModel.dismissRestoreState()
                            }
                            is RestoreUiState.Failed -> LaunchedEffect(state) {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_cloud_restore_failed, state.message)
                                )
                                viewModel.dismissRestoreState()
                            }
                            RestoreUiState.Idle -> Unit
                        }
                    }
                }
            }
        }
    }
}
