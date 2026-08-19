package com.hilamalu.oshixcollector.ui.notification

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.notification.MAX_NOTIFICATIONS_PER_DAY
import kotlin.math.roundToInt

/**
 * おすすめ通知の設定画面（画像一覧のヘッダーにあるベルアイコンから開く）。
 * 通知するアカウント・1日の回数・時間帯を決める。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val enabled by viewModel.isEnabled.collectAsState()
    val perDay by viewModel.perDay.collectAsState()
    val startHour by viewModel.startHour.collectAsState()
    val endHour by viewModel.endHour.collectAsState()
    val targetUserIds by viewModel.targetUserIds.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 端末側の通知許可。設定アプリで変更されうるため、画面に戻るたびに読み直す
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsAllowed = granted }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.notification_settings_back)
                        )
                    }
                }
            )
        },
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
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.notification_enable_label),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            viewModel.setEnabled(checked)
                            // Android 13+ は実行時許可が要る。ONにした流れでそのまま許可を求める
                            if (checked && !notificationsAllowed &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
                DescriptionText(stringResource(R.string.notification_enable_description))
                if (enabled && !notificationsAllowed) {
                    Text(
                        stringResource(R.string.notification_permission_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = {
                        // 一度「許可しない」を選ぶと再要求できなくなるため、設定アプリへ誘導する
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }) {
                        Text(stringResource(R.string.notification_open_system_settings))
                    }
                }
            }

            if (enabled) {
                SettingsCard {
                    Text(
                        stringResource(R.string.notification_frequency_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(stringResource(R.string.notification_per_day, perDay))
                    // ドラッグ中はDataStoreに書かず、指を離した時点の値だけ保存する
                    var draft by remember(perDay) { mutableStateOf(perDay.toFloat()) }
                    Slider(
                        value = draft,
                        onValueChange = { draft = it },
                        onValueChangeFinished = { viewModel.setPerDay(draft.roundToInt()) },
                        valueRange = 1f..MAX_NOTIFICATIONS_PER_DAY.toFloat(),
                        steps = MAX_NOTIFICATIONS_PER_DAY - 2
                    )
                    DescriptionText(stringResource(R.string.notification_per_day_description))
                }

                SettingsCard {
                    Text(
                        stringResource(R.string.notification_hours_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        HourPicker(
                            label = stringResource(R.string.notification_hours_start),
                            hour = startHour,
                            // 終了時刻(最大24)より前でなければならない
                            hours = 0..22,
                            onSelect = viewModel::setStartHour
                        )
                        HourPicker(
                            label = stringResource(R.string.notification_hours_end),
                            hour = endHour,
                            hours = 1..24,
                            onSelect = viewModel::setEndHour
                        )
                    }
                    DescriptionText(stringResource(R.string.notification_hours_description))
                }

                SettingsCard {
                    Text(
                        stringResource(R.string.notification_accounts_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (accounts.isEmpty()) {
                        DescriptionText(stringResource(R.string.notification_accounts_empty))
                    } else {
                        accounts.forEach { account ->
                            val checked = account.xUserId in targetUserIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleAccount(account.xUserId, !checked) }
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { viewModel.toggleAccount(account.xUserId, it) }
                                )
                                Text("@${account.screenName}")
                            }
                        }
                        if (targetUserIds.none { id -> accounts.any { it.xUserId == id } }) {
                            DescriptionText(stringResource(R.string.notification_accounts_none_selected))
                        }
                    }
                }

                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.notification_favorites_only_label),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = favoritesOnly, onCheckedChange = viewModel::setFavoritesOnly)
                    }
                    DescriptionText(stringResource(R.string.notification_favorites_only_description))
                }

                Button(
                    onClick = { viewModel.sendTestNotification() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.notification_test_button))
                }
            }
        }
    }
}

/** 開始/終了時刻を選ぶドロップダウン。0〜24時の整数のみを扱う（分単位の指定は不要な精度）。 */
@Composable
private fun HourPicker(
    label: String,
    hour: Int,
    hours: IntRange,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.notification_hour_value, hour))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hours.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notification_hour_value, candidate)) },
                    onClick = {
                        onSelect(candidate)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 設定画面（SettingsScreen）のカードと同じ見た目にそろえる。 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun DescriptionText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
