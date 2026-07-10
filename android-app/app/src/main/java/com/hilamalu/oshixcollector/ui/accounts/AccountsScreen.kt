package com.hilamalu.oshixcollector.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(viewModel: AccountsViewModel = viewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    val mediaCounts by viewModel.mediaCountByUserId.collectAsState()
    var newScreenName by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_accounts)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = newScreenName,
                        onValueChange = { newScreenName = it },
                        label = { Text(stringResource(R.string.accounts_add_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newScreenName.isNotBlank()) {
                                viewModel.addAccount(newScreenName)
                                newScreenName = ""
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(stringResource(R.string.accounts_add))
                    }
                }
            }

            if (accounts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.accounts_empty))
                }
            } else {
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(accounts, key = TargetAccountEntity::screenName) { account ->
                            ListItem(
                                headlineContent = { Text("@${account.screenName}") },
                                supportingContent = {
                                    Column {
                                        // Web版アカウント一覧の 収集画像数 / X User ID / 最終取得ID に対応
                                        Text(
                                            stringResource(
                                                R.string.accounts_media_count,
                                                account.xUserId?.let { mediaCounts[it] } ?: 0
                                            )
                                        )
                                        Text(
                                            account.xUserId
                                                ?.let { stringResource(R.string.accounts_user_id, it) }
                                                ?: stringResource(R.string.accounts_user_id_unresolved)
                                        )
                                        account.lastFetchedId?.let {
                                            Text(stringResource(R.string.accounts_last_fetched_id, it))
                                        }
                                        if (account.syncPaused) {
                                            Text(
                                                stringResource(R.string.accounts_sync_paused_label),
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    // 削除の概念は無く、追跡をやめたい場合は同期停止にする（データは残る）
                                    TextButton(onClick = {
                                        viewModel.setSyncPaused(account.screenName, !account.syncPaused)
                                    }) {
                                        Text(
                                            stringResource(
                                                if (account.syncPaused) R.string.accounts_sync_resume
                                                else R.string.accounts_sync_pause
                                            )
                                        )
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

        }
    }
}
