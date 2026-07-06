package com.hilamalu.oshixcollector.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
                                trailingContent = {
                                    IconButton(onClick = { viewModel.removeAccount(account.screenName) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.accounts_delete)
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
