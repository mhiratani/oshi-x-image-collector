package com.hilamalu.oshixcollector.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
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
    val backfillAllDone by viewModel.backfillAllDone.collectAsState()
    val isFaceOnly by viewModel.isFaceOnly.collectAsState()
    val isFavoritesOnly by viewModel.isFavoritesOnly.collectAsState()
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

    // フィルター中は「戻る」でアプリを閉じず、フィルターをリセットするだけにする（Web版と同じ挙動）。
    // 拡大表示（Dialog）や絞り込みシート（ModalBottomSheet）は自前で「戻る」を処理して閉じるため、
    // このハンドラが反応するのはそれらが表示されていないときだけ
    val filtersActive = selectedAccountId != null || isFaceOnly || isFavoritesOnly
    BackHandler(enabled = filtersActive) { viewModel.clearFilters() }

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
                        FilterChip(
                            selected = isFavoritesOnly,
                            onClick = { viewModel.setFavoritesOnly(!isFavoritesOnly) },
                            label = { Text(stringResource(R.string.media_favorite_only_filter)) }
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
            if (media.isEmpty() && backfillAllDone && !viewModel.isBackfilling) {
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
                            allDone = backfillAllDone,
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
            onToggleFavorite = { asset, isFavorite -> viewModel.toggleFavorite(asset.mediaKey, isFavorite) },
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
                AccountFilterRow(
                    label = stringResource(R.string.media_filter_all),
                    count = null,
                    selected = selectedAccountId == null,
                    onClick = { selectAndClose(null) }
                )
            }
            items(accountChips) { chip ->
                AccountFilterRow(
                    label = "@${chip.screenName}",
                    count = chip.mediaCount,
                    selected = chip.xUserId == selectedAccountId,
                    onClick = { selectAndClose(chip.xUserId) }
                )
            }
        }
    }
}

/** ユーザー絞り込みシートの1行（ラジオボタン＋ラベル＋任意の枚数表示）。 */
@Composable
private fun AccountFilterRow(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = count?.let { { Text("$it") } },
        modifier = Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton
        )
    )
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
    allDone: Boolean,
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
            allDone -> {
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
    onToggleFavorite: (MediaAssetEntity, Boolean) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
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
            onOpenTweet = { tweetId -> openTweet(context, tweetId) },
            onOverrideFace = onOverrideFace,
            onToggleFavorite = onToggleFavorite,
            onClose = onClose
        )
    }
}

@Composable
private fun LightboxContent(
    media: List<MediaAssetEntity>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    screenNames: Map<String, String>,
    onOpenTweet: (String) -> Unit,
    onOverrideFace: (MediaAssetEntity, Boolean) -> Unit,
    onToggleFavorite: (MediaAssetEntity, Boolean) -> Unit,
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
        // ピンチズーム中はページ送り（横スワイプ）を止め、1本指ドラッグを表示位置の移動に充てる
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.currentPage) { zoomed = false }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val asset = media[page]
            ZoomableLightboxImage(
                model = asset.localImagePath ?: asset.xCdnUrl,
                isCurrentPage = pagerState.currentPage == page,
                onZoomedChanged = { zoomed = it }
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
                    verticalAlignment = Alignment.CenterVertically,
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
                        onClick = { onOpenTweet(current.tweetId) }
                    ) {
                        Text(stringResource(R.string.media_open_tweet))
                    }
                    IconButton(onClick = { onToggleFavorite(current, !current.isFavorite) }) {
                        Icon(
                            if (current.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(R.string.media_favorite_toggle),
                            tint = if (current.isFavorite) Color(0xFFFFC107) else Color.White
                        )
                    }
                }
            }
        }
    }
}

/** ライトボックスのピンチズームの最大倍率（Web版useLightboxZoomのMAX_SCALEと合わせる）。 */
private const val MAX_LIGHTBOX_SCALE = 5f

/**
 * ライトボックス内の1ページ分の画像。ピンチイン/アウトで拡大縮小し、拡大中は1本指ドラッグで
 * 表示位置を移動、ダブルタップで等倍に戻せる。未ズームの1本指操作は消費せず、
 * ページ送り（HorizontalPager）とタップで閉じる操作にそのまま渡す。拡大中はタップや
 * ドラッグをここで消費し、誤って閉じたりページが送られたりしないようにする
 * （呼び出し側はonZoomedChangedでページ送りを無効化する）。
 */
@Composable
private fun ZoomableLightboxImage(
    model: Any?,
    isCurrentPage: Boolean,
    onZoomedChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 拡大した画像の端が表示枠の内側に入り込まない範囲に移動量を収める（等倍では常に中央）
    fun clampOffset(candidate: Offset, forScale: Float): Offset {
        val maxX = (forScale - 1f) * containerSize.width / 2f
        val maxY = (forScale - 1f) * containerSize.height / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    // 別のページへ送られたらズームを解除し、戻ってきた時は等倍から始める
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                // ズーム中のダブルタップ（等倍に戻す）判定用。直前に成立したタップの離した時刻
                var lastTapUpAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 未ズームの1本指タップはここで消費せず「タップで閉じる」に渡すため、
                    // ダブルタップとして追うのはズーム中に始まったタップだけ
                    var isTap = scale > 1f
                    var lastUptime = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.size > 1
                        if (multiTouch) isTap = false
                        event.changes.firstOrNull()?.let { change ->
                            lastUptime = change.uptimeMillis
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                isTap = false
                            }
                        }
                        if (multiTouch || scale > 1f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f) {
                                val newScale = (scale * zoomChange).coerceIn(1f, MAX_LIGHTBOX_SCALE)
                                // ピンチ中心直下の画像上の点が指の下に留まるようオフセットも合わせて動かす
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val centroid = event.calculateCentroid()
                                val contentPoint = (centroid - center - offset) / scale
                                offset = clampOffset(centroid - center - contentPoint * newScale + panChange, newScale)
                                scale = newScale
                            } else {
                                offset = clampOffset(offset + panChange, scale)
                            }
                            if (scale <= 1f) offset = Offset.Zero
                            onZoomedChanged(scale > 1f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (isTap && lastUptime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        // 間隔は「1タップ目を離してから2タップ目を押すまで」で測る
                        if (down.uptimeMillis - lastTapUpAt < viewConfiguration.doubleTapTimeoutMillis && scale > 1f) {
                            // ズーム中のダブルタップは等倍に戻す
                            scale = 1f
                            offset = Offset.Zero
                            onZoomedChanged(false)
                            lastTapUpAt = 0L
                        } else {
                            lastTapUpAt = lastUptime
                        }
                    } else {
                        lastTapUpAt = 0L
                    }
                }
            }
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            loading = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        )
    }
}

private fun formatPostedAt(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.JAPAN)
        .format(Date(epochMillis))

// 元ポストをX公式アプリで開く。https://x.com/... を直接投げるとOSの設定次第で
// ブラウザが選ばれてしまうため、次の順で試す:
//   1. https を X公式アプリ(com.twitter.android)に名指しで渡す
//      （現行のXアプリは twitter://status?id= を受理するものの解釈できず
//        ホームに落ちるだけのため、https 名指しを最優先にする。
//        X v12.9.1 / Pixel 9 実機で確認済み）
//   2. twitter:// スキーム（x.com ドメイン未対応の旧Twitterアプリ救済）
//   3. どちらもダメならブラウザで https を開く（Xアプリ未インストール時など）
// 1・3で使う https はユーザー名不要の正規形 /i/status/<id>（App Link 対象）にする。
// /i/web/status/ はWeb専用リダイレクトでXアプリが受け取らないため使わない。
private const val X_APP_PACKAGE = "com.twitter.android"

private fun openTweet(context: Context, tweetId: String) {
    val webUri = Uri.parse("https://x.com/i/status/$tweetId")

    // 1. https を Xアプリに名指しで渡す（現行Xアプリでポスト詳細に直行する唯一の形式）
    val appWebIntent = Intent(Intent.ACTION_VIEW, webUri).setPackage(X_APP_PACKAGE)
    if (appWebIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(appWebIntent)
        return
    }

    // 2. Xアプリのディープリンクスキーム（x.com を知らない旧アプリ向け）
    val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("twitter://status?id=$tweetId"))
    if (schemeIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(schemeIntent)
        return
    }

    // 3. ブラウザへフォールバック
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    } catch (_: ActivityNotFoundException) {
        // 開けるアプリが一切無い端末では何もしない
    }
}
