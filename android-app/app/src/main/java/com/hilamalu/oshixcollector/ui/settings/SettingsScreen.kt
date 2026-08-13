package com.hilamalu.oshixcollector.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** エクスポート時に要求するパスフレーズの最低文字数。 */
private const val MIN_EXPORT_PASSPHRASE_LENGTH = 8

/**
 * 設定画面。日常的に触る項目（Bearer Token・機種変更の引き継ぎ）と、
 * クラウドバックアップを使う人だけが触る項目を2つのタブに分ける。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // errorMessageは発生箇所で文脈込みの文言になっているため、ここではそのまま表示する
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
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
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.nav_settings)) })
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    listOf(R.string.settings_tab_common, R.string.settings_tab_backup)
                        .forEachIndexed { index, labelRes ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = {
                                    if (selectedTab != index) {
                                        // タブ切り替えでは入力欄のフォーカスアウトを待たずに破棄されうるため保存する
                                        viewModel.saveAll()
                                        selectedTab = index
                                    }
                                },
                                text = { Text(stringResource(labelRes)) }
                            )
                        }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> CommonSettingsTab(viewModel, snackbarHostState)
                else -> BackupSettingsTab(viewModel)
            }
        }
    }
}

/** 両タブ共通のスクロールコンテナ。タブを切り替えると破棄され、スクロール位置もリセットされる。 */
@Composable
private fun SettingsTabContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

// ──────────────────────────────── 共通設定タブ ────────────────────────────────

/** Bearer Tokenと機種変更の引き継ぎ。クラウドバックアップを使わないユーザーはこのタブだけで完結する。 */
@Composable
private fun CommonSettingsTab(viewModel: SettingsViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current

    // ── 設定の引き継ぎ（機種変更）: パスフレーズ暗号化ファイルのエクスポート/インポート ──
    // エクスポートは「パスフレーズ入力 → 保存先選択(SAF)」、インポートは「ファイル選択(SAF) → パスフレーズ入力」の順
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportSettings(uri, pendingExportPassphrase)
        pendingExportPassphrase = ""
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    LaunchedEffect(viewModel.transferState) {
        when (val state = viewModel.transferState) {
            TransferUiState.Exported -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_transfer_export_success))
                viewModel.dismissTransferState()
            }
            TransferUiState.Imported -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_transfer_import_success))
                viewModel.dismissTransferState()
            }
            is TransferUiState.Failed -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_transfer_failed, state.message))
                viewModel.dismissTransferState()
            }
            TransferUiState.Idle -> Unit
        }
    }

    if (showExportDialog) {
        PassphraseDialog(
            titleRes = R.string.settings_transfer_export_dialog_title,
            messageRes = R.string.settings_transfer_export_dialog_message,
            requireConfirmation = true,
            onConfirm = { passphrase ->
                showExportDialog = false
                pendingExportPassphrase = passphrase
                exportLauncher.launch("oshi-x-collector-settings.json")
            },
            onDismiss = { showExportDialog = false }
        )
    }
    pendingImportUri?.let { uri ->
        PassphraseDialog(
            titleRes = R.string.settings_transfer_import_dialog_title,
            messageRes = R.string.settings_transfer_import_dialog_message,
            requireConfirmation = false,
            onConfirm = { passphrase ->
                pendingImportUri = null
                viewModel.importSettings(uri, passphrase)
            },
            onDismiss = { pendingImportUri = null }
        )
    }

    SettingsTabContent {
        SettingsCard(title = stringResource(R.string.settings_x_section)) {
            DescriptionText(stringResource(R.string.settings_x_description))
            AutoSaveTextField(
                value = viewModel.xBearerToken,
                onValueChange = { viewModel.xBearerToken = it },
                labelRes = R.string.settings_x_bearer_token,
                onSave = { viewModel.saveXBearerToken() },
                isPassword = true
            )
            val hasToken = viewModel.xBearerToken.isNotBlank()
            StatusRow(
                icon = if (hasToken) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                tint = if (hasToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                text = stringResource(
                    if (hasToken) R.string.settings_x_token_saved else R.string.settings_x_token_missing
                )
            )
        }

        SettingsCard(title = stringResource(R.string.settings_transfer_section)) {
            DescriptionText(stringResource(R.string.settings_transfer_description))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showExportDialog = true }) {
                    Text(stringResource(R.string.settings_transfer_export_button))
                }
                Button(
                    onClick = {
                        // エクスポートは application/json で書き出すが、Drive等が別のMIMEを
                        // 返すことがあるため受け入れ側は少し広く取る
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain")
                        )
                    }
                ) {
                    Text(stringResource(R.string.settings_transfer_import_button))
                }
            }
        }
    }
}

// ─────────────────────────────── バックアップ設定タブ ───────────────────────────────

/**
 * クラウドバックアップの設定。先頭のマスタートグルがONの間だけ、
 * 必要な入力欄（Firebase・R2）とサインイン・復元の操作を表示する。
 */
@Composable
private fun BackupSettingsTab(viewModel: SettingsViewModel) {
    val requested by viewModel.backupRequested.collectAsState()
    val effectiveEnabled by viewModel.cloudBackupEnabled.collectAsState()
    val hasLocalData by viewModel.hasLocalData.collectAsState()

    // R2/Firebaseの接続設定は一度入れたら普段は触らないため、デフォルトで折りたたんでおく
    var r2Expanded by rememberSaveable { mutableStateOf(false) }
    var firebaseExpanded by rememberSaveable { mutableStateOf(false) }

    SettingsTabContent {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_cloud_backup_toggle_label),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = requested,
                        onCheckedChange = { viewModel.setBackupRequested(it) }
                    )
                }
                DescriptionText(
                    stringResource(
                        if (requested) R.string.settings_cloud_backup_on_description
                        else R.string.settings_cloud_backup_off_description
                    )
                )
            }
        }

        if (requested) {
            SetupStatusCard(viewModel, effectiveEnabled)

            CollapsibleSettingsCard(
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
            ) {
                DescriptionText(stringResource(R.string.settings_firebase_description))
                val saveFirebase = { viewModel.saveFirebaseSettings() }
                AutoSaveTextField(viewModel.firebaseApiKey, { viewModel.firebaseApiKey = it }, R.string.settings_firebase_api_key, saveFirebase, isPassword = true)
                AutoSaveTextField(viewModel.firebaseProjectId, { viewModel.firebaseProjectId = it }, R.string.settings_firebase_project_id, saveFirebase)
                AutoSaveTextField(viewModel.firebaseAppId, { viewModel.firebaseAppId = it }, R.string.settings_firebase_app_id, saveFirebase)
                AutoSaveTextField(viewModel.firebaseWebClientId, { viewModel.firebaseWebClientId = it }, R.string.settings_firebase_web_client_id, saveFirebase)
            }

            CollapsibleSettingsCard(
                title = stringResource(R.string.settings_r2_section),
                expanded = r2Expanded,
                onToggle = {
                    r2Expanded = !r2Expanded
                    // 折りたたむ時はフォーカスイベントを経ずに入力欄が破棄されうるため、ここでも保存する
                    if (!r2Expanded) viewModel.saveR2Settings()
                }
            ) {
                DescriptionText(stringResource(R.string.settings_r2_description))
                val saveR2 = { viewModel.saveR2Settings() }
                AutoSaveTextField(viewModel.r2BucketName, { viewModel.r2BucketName = it }, R.string.settings_r2_bucket, saveR2)
                AutoSaveTextField(viewModel.r2AccountId, { viewModel.r2AccountId = it }, R.string.settings_r2_account_id, saveR2)
                AutoSaveTextField(viewModel.r2AccessKeyId, { viewModel.r2AccessKeyId = it }, R.string.settings_r2_access_key, saveR2)
                AutoSaveTextField(viewModel.r2SecretAccessKey, { viewModel.r2SecretAccessKey = it }, R.string.settings_r2_secret_key, saveR2, isPassword = true)
                AutoSaveTextField(viewModel.r2Endpoint, { viewModel.r2Endpoint = it }, R.string.settings_r2_endpoint, saveR2)
            }

            RestoreCard(viewModel, hasLocalData)
        }
    }
}

/** バックアップに必要な項目が揃っているかを一覧で示し、足りないものへの導線を出す。 */
@Composable
private fun SetupStatusCard(viewModel: SettingsViewModel, effectiveEnabled: Boolean) {
    SettingsCard(title = stringResource(R.string.settings_setup_status_section)) {
        SetupRow(
            done = viewModel.isFirebaseConfigured,
            required = true,
            title = stringResource(R.string.settings_setup_firebase_title),
            detail = stringResource(
                if (viewModel.isFirebaseConfigured) R.string.settings_setup_done
                else R.string.settings_setup_firebase_todo
            )
        )

        val signedInEmail = viewModel.signedInEmail
        SetupRow(
            done = signedInEmail != null,
            required = true,
            title = stringResource(R.string.settings_setup_signin_title),
            detail = when {
                signedInEmail != null ->
                    stringResource(R.string.settings_cloud_backup_signed_in_as, signedInEmail)
                viewModel.isFirebaseConfigured -> stringResource(R.string.settings_setup_signin_todo)
                else -> stringResource(R.string.settings_setup_signin_blocked)
            },
            action = {
                if (signedInEmail == null) {
                    Button(
                        onClick = { viewModel.signIn() },
                        enabled = viewModel.isFirebaseConfigured
                    ) {
                        Text(stringResource(R.string.settings_sign_in_button))
                    }
                } else {
                    TextButton(onClick = { viewModel.signOut() }) {
                        Text(stringResource(R.string.settings_sign_out_button))
                    }
                }
            }
        )

        SetupRow(
            done = viewModel.isR2Configured,
            required = false,
            title = stringResource(R.string.settings_setup_r2_title),
            detail = stringResource(
                if (viewModel.isR2Configured) R.string.settings_setup_done
                else R.string.settings_setup_r2_todo
            )
        )

        if (viewModel.signedOutByConfigChange) {
            NoticeRow(
                text = stringResource(R.string.settings_signed_out_by_config_change),
                color = MaterialTheme.colorScheme.error,
                onDismiss = { viewModel.dismissSignedOutNotice() }
            )
        }

        HorizontalDivider()
        StatusRow(
            icon = if (effectiveEnabled) Icons.Filled.CheckCircle else Icons.Outlined.Info,
            tint = if (effectiveEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            text = stringResource(
                if (effectiveEnabled) R.string.settings_setup_active
                else R.string.settings_setup_inactive
            )
        )
    }
}

/** クラウドからの復元/同期。結果はSnackbarで流さず、閉じるまで残るインライン行として表示する。 */
@Composable
private fun RestoreCard(viewModel: SettingsViewModel, hasLocalData: Boolean) {
    SettingsCard(title = stringResource(R.string.settings_cloud_restore_section)) {
        DescriptionText(stringResource(R.string.settings_cloud_restore_description))
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
            is RestoreUiState.Success -> NoticeRow(
                text = stringResource(
                    if (state.isInitialRestore) R.string.settings_cloud_restore_success
                    else R.string.settings_cloud_sync_success,
                    state.result.accountsRestored,
                    state.result.mediaRowsRestored,
                    state.result.imagesDownloaded,
                    state.result.imagesFailed
                ),
                color = MaterialTheme.colorScheme.primary,
                onDismiss = { viewModel.dismissRestoreState() }
            )
            is RestoreUiState.Failed -> NoticeRow(
                text = stringResource(R.string.settings_cloud_restore_failed, state.message),
                color = MaterialTheme.colorScheme.error,
                onDismiss = { viewModel.dismissRestoreState() }
            )
            RestoreUiState.Idle -> Unit
        }
    }
}

// ──────────────────────────────── 共通パーツ ────────────────────────────────

/** 見出し付きのカード。設定画面の各区画で共通。 */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** 見出しタップで開閉するカード。R2/Firebaseのような普段触らない接続設定に使う。 */
@Composable
private fun CollapsibleSettingsCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.settings_section_collapse else R.string.settings_section_expand
                    )
                )
            }
            if (expanded) content()
        }
    }
}

/** カード内の補足説明。本文より一段弱いスタイルで統一する。 */
@Composable
private fun DescriptionText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** アイコン付きの1行ステータス表示。 */
@Composable
private fun StatusRow(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * セットアップ状況の1項目。[required]がfalseの項目は未設定でもバックアップ自体は動くため、
 * エラー色ではなく控えめな色で「何ができなくなるか」を示す。
 */
@Composable
private fun SetupRow(
    done: Boolean,
    required: Boolean,
    title: String,
    detail: String,
    action: @Composable (() -> Unit)? = null
) {
    val tint = when {
        done -> MaterialTheme.colorScheme.primary
        required -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            Text(
                stringResource(
                    if (required) R.string.settings_setup_required else R.string.settings_setup_optional
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else tint,
            modifier = Modifier.padding(start = 26.dp)
        )
        action?.let {
            Row(modifier = Modifier.padding(start = 26.dp)) { it() }
        }
    }
}

/** 結果・警告の永続表示。ユーザーが閉じるまで残る（Snackbarのように消えない）。 */
@Composable
private fun NoticeRow(text: String, color: Color, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.settings_result_dismiss))
        }
    }
}

/**
 * 引き継ぎファイルのパスフレーズ入力ダイアログ。
 * [requireConfirmation]がtrueの時（エクスポート）は確認欄と最低文字数も課し、
 * 満たさない理由をエラー文言で示す（インポート側は旧バージョンで作ったファイルも
 * 取り込めるよう、文字数の制限はかけない）。
 */
@Composable
private fun PassphraseDialog(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    requireConfirmation: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    val tooShort = requireConfirmation &&
        passphrase.isNotEmpty() && passphrase.length < MIN_EXPORT_PASSPHRASE_LENGTH
    val mismatch = requireConfirmation && confirmation.isNotEmpty() && passphrase != confirmation
    val canConfirm = passphrase.isNotEmpty() && !tooShort &&
        (!requireConfirmation || passphrase == confirmation)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(messageRes))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.settings_transfer_passphrase_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text(stringResource(R.string.settings_transfer_passphrase_too_short)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.settings_transfer_passphrase_confirm_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text(stringResource(R.string.settings_transfer_passphrase_mismatch)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = canConfirm) {
                Text(stringResource(R.string.settings_transfer_dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_transfer_dialog_cancel))
            }
        }
    )
}

/**
 * フォーカスが外れた時に[onSave]で自動保存する設定入力欄。設定画面の全テキスト項目で共通。
 * [isPassword]の欄は既定でマスクし、末尾のアイコンで表示/非表示を切り替えられる
 * （長い認証情報を貼り付けた後に目視確認できるようにするため）。
 */
@Composable
private fun AutoSaveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    onSave: () -> Unit,
    isPassword: Boolean = false
) {
    var revealed by remember { mutableStateOf(false) }
    val masked = isPassword && !revealed

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.settings_hide_value else R.string.settings_show_value
                        )
                    )
                }
            }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) onSave() }
    )
}
