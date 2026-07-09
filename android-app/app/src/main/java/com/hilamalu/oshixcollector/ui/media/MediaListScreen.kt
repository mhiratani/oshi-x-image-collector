package com.hilamalu.oshixcollector.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaListScreen(viewModel: MediaViewModel = viewModel()) {
    val media by viewModel.media.collectAsState()
    val accountChips by viewModel.accountChips.collectAsState()
    val selectedAccountId by viewModel.selectedAccountId.collectAsState()
    val screenNames by viewModel.screenNameByUserId.collectAsState()
    val backfillState by viewModel.backfillState.collectAsState()
    val isFaceOnly by viewModel.isFaceOnly.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 拡大表示中の画像（Web版のselected。mediaKeyで持つことでリスト更新時のずれを防ぐ）
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // ユーザー絞り込みシートの開閉
    var showAccountSheet by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(viewModel.syncMessage) {
        viewModel.syncMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissSyncMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.nav_media))
                        FilterChip(
                            selected = isFaceOnly,
                            onClick = { viewModel.setFaceOnly(!isFaceOnly) },
                            label = { Text(stringResource(R.string.media_face_only_filter)) }
                        )
                        if (viewModel.faceDetectionRemaining > 0) {
                            Text(
                                stringResource(R.string.media_face_detecting, viewModel.faceDetectionRemaining),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                },
                actions = {
                    // 「最新を取得」(クラウド同期→X API取得)。旧クラウド同期アイコンの位置に配置
                    IconButton(onClick = { viewModel.refresh() }, enabled = !viewModel.isRefreshing) {
                        if (viewModel.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.media_refresh))
                        }
                    }
                }
            )
        },

        floatingActionButton = {
            FloatingActionButton(onClick = { showAccountSheet = true }) {
                Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.media_filter_by_account))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (media.isEmpty() && backfillState.allDone && !viewModel.isBackfilling) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            stringResource(if (isFaceOnly) R.string.media_empty_face_only else R.string.media_empty),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    // 右下のFABが最終行や過去読み込みボタンに重ならないよう下部に余白を確保
                    contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(media, key = MediaAssetEntity::mediaKey) { asset ->
                        MediaTile(
                            asset = asset,
                            // Web版と同じく1アカウント絞り込み中はバッジを出さない
                            badgeScreenName = if (selectedAccountId == null) screenNames[asset.xUserId] else null,
                            onClick = { selectedKey = asset.mediaKey }
                        )
                    }
                    // Web版のstatusフッター: 件数表示とバックフィル操作
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        BackfillFooter(
                            itemCount = media.size,
                            state = backfillState,
                            isBackfilling = viewModel.isBackfilling,
                            onBackfill = { viewModel.backfill() }
                        )
                    }
                }
            }
        }
    }

    // 拡大表示（Web版のlightbox）
    val selectedIndex = media.indexOfFirst { it.mediaKey == selectedKey }
    if (selectedKey != null && selectedIndex >= 0) {
        MediaLightbox(
            media = media,
            initialIndex = selectedIndex,
            screenNames = screenNames,
            onPageChanged = { asset -> selectedKey = asset.mediaKey },
            onOverrideFace = { asset, isFace -> viewModel.overrideFace(asset.mediaKey, isFace) },
            onClose = { selectedKey = null }
        )
    }

    // FABから開くユーザー絞り込みシート（単一選択。「すべて」で解除）
    if (showAccountSheet) {
        AccountFilterSheet(
            accountChips = accountChips,
            selectedAccountId = selectedAccountId,
            onSelect = { xUserId -> viewModel.selectAccountFilter(xUserId) },
            onDismiss = { showAccountSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountFilterSheet(
    accountChips: List<AccountChip>,
    selectedAccountId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // 選択後はhide()のアニメーション完了を待ってから閉じる（即座にshowAccountSheet=falseにすると
    // ModalBottomSheetのクローズアニメーションがスキップされ、見た目が瞬間消滅してしまうため）
    fun selectAndClose(xUserId: String?) {
        onSelect(xUserId)
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.media_filter_all)) },
                    leadingContent = {
                        RadioButton(selected = selectedAccountId == null, onClick = null)
                    },
                    modifier = Modifier.selectable(
                        selected = selectedAccountId == null,
                        onClick = { selectAndClose(null) },
                        role = Role.RadioButton
                    )
                )
            }
            items(accountChips) { chip ->
                ListItem(
                    headlineContent = { Text("@${chip.screenName}") },
                    leadingContent = {
                        RadioButton(selected = chip.xUserId == selectedAccountId, onClick = null)
                    },
                    trailingContent = { Text("${chip.mediaCount}") },
                    modifier = Modifier.selectable(
                        selected = chip.xUserId == selectedAccountId,
                        onClick = { selectAndClose(chip.xUserId) },
                        role = Role.RadioButton
                    )
                )
            }
        }
    }
}

@Composable
private fun MediaTile(
    asset: MediaAssetEntity,
    badgeScreenName: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        SubcomposeAsyncImage(
            model = asset.localImagePath ?: asset.xCdnUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
        )
        if (badgeScreenName != null) {
            Text(
                "@$badgeScreenName",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/** Web版page.tsxの一覧末尾ステータス（件数表示・過去読み込みボタン）の移植。 */
@Composable
private fun BackfillFooter(
    itemCount: Int,
    state: BackfillUiState,
    isBackfilling: Boolean,
    onBackfill: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 16.dp)
    ) {
        if (itemCount > 0) {
            Text(
                stringResource(R.string.media_all_shown, itemCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        when {
            isBackfilling -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Text(stringResource(R.string.media_backfill_running))
                }
            }
            state.allDone -> {
                if (itemCount > 0) {
                    Text(
                        stringResource(R.string.media_backfill_all_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            else -> {
                OutlinedButton(onClick = onBackfill, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.media_backfill))
                }
            }
        }
    }
}

/**
 * Web版page.tsxのlightboxの移植。全画面表示・スワイプによる画像送り・読み込み中表示・
 * メタ情報（@name/投稿日時/元ツイートを開く/顔判定の手動上書き）・「戻る」で閉じる、に対応する。
 */
@Composable
private fun MediaLightbox(
    media: List<MediaAssetEntity>,
    initialIndex: Int,
    screenNames: Map<String, String>,
    onPageChanged: (MediaAssetEntity) -> Unit,
    onOverrideFace: (MediaAssetEntity, Boolean) -> Unit,
    onClose: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { media.size })

    LaunchedEffect(pagerState.currentPage) {
        media.getOrNull(pagerState.currentPage)?.let(onPageChanged)
    }

    // 全画面Dialogにすることでボトムナビも覆い、「戻る」は拡大表示を閉じるだけになる
    // （Web版のuseLightboxHistoryBack相当はDialogのonDismissRequestが担う）
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LightboxContent(
            media = media,
            pagerState = pagerState,
            screenNames = screenNames,
            uriHandler = uriHandler,
            onOverrideFace = onOverrideFace,
            onClose = onClose
        )
    }
}

@Composable
private fun LightboxContent(
    media: List<MediaAssetEntity>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    screenNames: Map<String, String>,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    onOverrideFace: (MediaAssetEntity, Boolean) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            // 背景タップで閉じる（Web版と同じ）。リップルは全画面に出ると邪魔なので無効化
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val asset = media[page]
            SubcomposeAsyncImage(
                model = asset.localImagePath ?: asset.xCdnUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            )
        }

        val current = media.getOrNull(pagerState.currentPage)
        if (current != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    // メタ情報のタップは閉じる操作にしない（Web版のstopPropagation相当）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    screenNames[current.xUserId]?.let { name ->
                        Text("@$name", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        formatPostedAt(current.postedAt),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedButton(onClick = { onOverrideFace(current, current.isFace != true) }) {
                        Text(
                            stringResource(
                                if (current.isFace == true) R.string.media_face_mark_false
                                else R.string.media_face_mark_true
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://x.com/i/web/status/${current.tweetId}") }
                    ) {
                        Text(stringResource(R.string.media_open_tweet))
                    }
                }
            }
        }
    }
}

private fun formatPostedAt(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.JAPAN)
        .format(Date(epochMillis))
