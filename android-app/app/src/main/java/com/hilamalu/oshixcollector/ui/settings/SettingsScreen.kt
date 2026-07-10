package com.hilamalu.oshixcollector.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.MediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val cloudBackupEnabled by viewModel.cloudBackupEnabled.collectAsState()
    val hasLocalData by viewModel.hasLocalData.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // R2/Firebaseの接続設定は普段は触らないため、デフォルトで折りたたんでおく
    var r2Expanded by remember { mutableStateOf(false) }
    var firebaseExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(context.getString(R.string.settings_sign_in_failed, message))
            viewModel.dismissError()
        }
    }

    // 自動保存はフォーカスが外れた時にしか走らないため、入力欄にフォーカスが残ったまま
    // 画面を離れた（タブ切り替え）・アプリがバックグラウンドに回った場合の保存漏れをここで防ぐ
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.saveAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.saveAll()
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.settings_x_section), style = MaterialTheme.typography.titleMedium)
                    AutoSaveTextField(
                        value = viewModel.xBearerToken,
                        onValueChange = { viewModel.xBearerToken = it },
                        labelRes = R.string.settings_x_bearer_token,
                        onSave = { viewModel.saveXBearerToken() },
                        isPassword = true
                    )
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeader(
                        title = stringResource(R.string.settings_r2_section),
                        expanded = r2Expanded,
                        onToggle = {
                            r2Expanded = !r2Expanded
                            // 折りたたむ時はフォーカスイベントを経ずに入力欄が破棄されうるため、ここでも保存する
                            if (!r2Expanded) viewModel.saveR2Settings()
                        }
                    )
                    if (r2Expanded) {
                        val saveR2 = { viewModel.saveR2Settings() }
                        AutoSaveTextField(viewModel.r2BucketName, { viewModel.r2BucketName = it }, R.string.settings_r2_bucket, saveR2)
                        AutoSaveTextField(viewModel.r2AccountId, { viewModel.r2AccountId = it }, R.string.settings_r2_account_id, saveR2)
                        AutoSaveTextField(viewModel.r2AccessKeyId, { viewModel.r2AccessKeyId = it }, R.string.settings_r2_access_key, saveR2)
                        AutoSaveTextField(viewModel.r2SecretAccessKey, { viewModel.r2SecretAccessKey = it }, R.string.settings_r2_secret_key, saveR2, isPassword = true)
                        AutoSaveTextField(viewModel.r2Endpoint, { viewModel.r2Endpoint = it }, R.string.settings_r2_endpoint, saveR2)
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeader(
                        title = stringResource(R.string.settings_firebase_section),
                        expanded = firebaseExpanded,
                        onToggle = {
                            firebaseExpanded = !firebaseExpanded
                            // 折りたたみ＝編集の一段落とみなし、保存に加えてサインアウトカスケードもここで適用する
                            if (!firebaseExpanded) {
                                viewModel.saveFirebaseSettings()
                                viewModel.applyFirebaseConfigIfChanged()
                            }
                        }
                    )
                    if (firebaseExpanded) {
                        Text(stringResource(R.string.settings_firebase_description))
                        val saveFirebase = { viewModel.saveFirebaseSettings() }
                        AutoSaveTextField(viewModel.firebaseApiKey, { viewModel.firebaseApiKey = it }, R.string.settings_firebase_api_key, saveFirebase, isPassword = true)
                        AutoSaveTextField(viewModel.firebaseProjectId, { viewModel.firebaseProjectId = it }, R.string.settings_firebase_project_id, saveFirebase)
                        AutoSaveTextField(viewModel.firebaseAppId, { viewModel.firebaseAppId = it }, R.string.settings_firebase_app_id, saveFirebase)
                        AutoSaveTextField(viewModel.firebaseWebClientId, { viewModel.firebaseWebClientId = it }, R.string.settings_firebase_web_client_id, saveFirebase)
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.settings_cloud_backup_section), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_cloud_backup_description))

                    if (!viewModel.isFirebaseConfigured) {
                        Text(stringResource(R.string.settings_cloud_backup_firebase_not_configured))
                    } else if (viewModel.signedInEmail == null) {
                        // 未ログイン: まずGoogleサインインしてもらう（成功するとトグル表示に切り替わる）
                        Text(stringResource(R.string.settings_sign_in_description))
                        Button(onClick = { viewModel.signIn() }) {
                            Text(stringResource(R.string.settings_sign_in_button))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = cloudBackupEnabled, onCheckedChange = { viewModel.setCloudBackupEnabled(it) })
                            Text(
                                stringResource(R.string.settings_cloud_backup_toggle_label),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        viewModel.signedInEmail?.let { email ->
                            Text(stringResource(R.string.settings_cloud_backup_signed_in_as, email))
                        }

                        Button(
                            onClick = { viewModel.restoreFromCloud() },
                            enabled = viewModel.restoreState !is RestoreUiState.InProgress
                        ) {
                            // 初回(ローカルが空)は「復元」、2回目以降は「同期」として同じ処理を案内する
                            Text(
                                stringResource(
                                    if (hasLocalData) R.string.settings_cloud_sync_button
                                    else R.string.settings_cloud_restore_button
                                )
                            )
                        }

                        when (val state = viewModel.restoreState) {
                            is RestoreUiState.InProgress -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        if (state.isInitialRestore) R.string.settings_cloud_restore_success
                                        else R.string.settings_cloud_sync_success,
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

/** フォーカスが外れた時に[onSave]で自動保存する設定入力欄。設定画面の全テキスト項目で共通。 */
@Composable
private fun AutoSaveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    onSave: () -> Unit,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) onSave() }
    )
}

/** R2/Firebaseカードの折りたたみ見出し。タップで展開/収納をトグルする。 */
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null
        )
    }
}
