package com.hilamalu.oshixcollector.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.MediaRepository

/**
 * 初回起動オンボーディング。[onFinished]は判定完了（既存データ有り）またはフロー完了時に呼ばれる。
 * [onNeedsConfiguration]は復元/バックアップを希望したがFirebase未設定だった場合、設定画面へ誘導するために呼ばれる。
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onNeedsConfiguration: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val step = viewModel.step

    LaunchedEffect(step) {
        when (step) {
            is OnboardingStep.Done -> onFinished()
            is OnboardingStep.NeedsConfiguration -> onNeedsConfiguration()
            else -> Unit
        }
    }

    if (step is OnboardingStep.Checking || step is OnboardingStep.Done || step is OnboardingStep.NeedsConfiguration) return

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                OnboardingStep.Welcome -> {
                    Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.onboarding_welcome_description),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    Button(onClick = { viewModel.start() }) {
                        Text(stringResource(R.string.onboarding_start_button))
                    }
                }

                OnboardingStep.AskRestore -> {
                    Text(stringResource(R.string.onboarding_ask_restore))
                    Row(modifier = Modifier.padding(top = 16.dp)) {
                        Button(onClick = { viewModel.answerRestore(true) }) {
                            Text(stringResource(R.string.onboarding_yes))
                        }
                        TextButton(onClick = { viewModel.answerRestore(false) }) {
                            Text(stringResource(R.string.onboarding_no))
                        }
                    }
                }

                is OnboardingStep.Restoring -> {
                    CircularProgressIndicator()
                    Text(
                        when (val progress = step.progress) {
                            MediaRepository.RestoreProgress.FetchingMetadata ->
                                stringResource(R.string.settings_cloud_restore_fetching)
                            is MediaRepository.RestoreProgress.DownloadingImages ->
                                stringResource(
                                    R.string.settings_cloud_restore_downloading,
                                    progress.completed,
                                    progress.total
                                )
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                OnboardingStep.AskCloudBackup -> {
                    Text(stringResource(R.string.onboarding_ask_cloud_backup))
                    Row(modifier = Modifier.padding(top = 16.dp)) {
                        Button(onClick = { viewModel.answerCloudBackup(true) }) {
                            Text(stringResource(R.string.onboarding_yes))
                        }
                        TextButton(onClick = { viewModel.answerCloudBackup(false) }) {
                            Text(stringResource(R.string.onboarding_no))
                        }
                    }
                }

                OnboardingStep.EnablingBackup -> CircularProgressIndicator()

                is OnboardingStep.Error -> {
                    Text(step.message)
                    Row(modifier = Modifier.padding(top = 16.dp)) {
                        Button(onClick = { viewModel.retry(step.retryStep) }) {
                            Text(stringResource(R.string.onboarding_retry))
                        }
                        TextButton(onClick = { viewModel.skip() }) {
                            Text(stringResource(R.string.onboarding_skip))
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}
