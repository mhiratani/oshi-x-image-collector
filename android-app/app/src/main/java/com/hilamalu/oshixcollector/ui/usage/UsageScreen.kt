package com.hilamalu.oshixcollector.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.backup.ApiUsageEntry
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun usd(value: Double): String = String.format(Locale.US, "$%.4f", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(viewModel: UsageViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_usage)) },
                actions = {
                    IconButton(onClick = { viewModel.load() }, enabled = !viewModel.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.usage_reload))
                    }
                }
            )
        }
    ) { innerPadding ->
        val stats = viewModel.stats
        when {
            viewModel.isLoading && stats == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            viewModel.errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(viewModel.errorMessage!!, textAlign = TextAlign.Center)
                        Button(onClick = { viewModel.load() }, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.usage_reload))
                        }
                    }
                }
            }
            stats != null -> {
                UsageContent(stats, Modifier.padding(innerPadding))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageContent(stats: UsageStats, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile(stringResource(R.string.usage_today), usd(stats.todayCost))
                StatTile(stringResource(R.string.usage_month), usd(stats.monthCost))
                StatTile(stringResource(R.string.usage_all_time), usd(stats.allTimeCost))
                StatTile(stringResource(R.string.usage_month_calls), "${stats.monthCalls}")
                StatTile(stringResource(R.string.usage_month_quantity), "${stats.monthQuantity}")
            }
        }
        item {
            Text(
                stringResource(R.string.usage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        item { DailyChartCard(stats) }
        item {
            PurposeTableCard(
                title = stringResource(R.string.usage_daily_by_purpose_title),
                periodHeader = stringResource(R.string.usage_col_date),
                rows = stats.dailyByPurpose
            )
        }
        item {
            PurposeTableCard(
                title = stringResource(R.string.usage_monthly_by_purpose_title),
                periodHeader = stringResource(R.string.usage_col_month),
                rows = stats.monthlyByPurpose
            )
        }
        item { MonthBreakdownCard(stats) }
        item { RecentLogCard(stats.recent) }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    ElevatedCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Web版「直近30日の推移」の棒グラフ。 */
@Composable
private fun DailyChartCard(stats: UsageStats) {
    val today = stats.daily.lastOrNull()
    val last30Calls = stats.daily.sumOf { it.calls }
    val last30Cost = stats.daily.sumOf { it.cost }
    val maxDaily = maxOf(0.0001, stats.daily.maxOfOrNull { it.cost } ?: 0.0)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_daily_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.usage_daily_summary,
                    today?.calls ?: 0,
                    usd(today?.cost ?: 0.0),
                    last30Calls,
                    usd(last30Cost)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                stats.daily.forEach { day ->
                    val fraction = (day.cost / maxDaily).toFloat().coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(maxOf(2.dp, 96.dp * fraction))
                            .background(
                                if (day.cost > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                            )
                    )
                }
            }
        }
    }
}

/** Web版「用途別 呼び出し回数」（日次/月次）の表。 */
@Composable
private fun PurposeTableCard(title: String, periodHeader: String, rows: List<PeriodPurposeRow>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TableHeaderCell(periodHeader, weight = 1.4f)
                USAGE_PURPOSES.forEach { purpose ->
                    TableHeaderCell(USAGE_PURPOSE_LABEL[purpose] ?: purpose, weight = 1f)
                }
                TableHeaderCell(stringResource(R.string.usage_col_total), weight = 0.8f)
            }
            HorizontalDivider()
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.usage_empty_recent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    TableCell(row.periodLabel, weight = 1.4f)
                    USAGE_PURPOSES.forEach { purpose ->
                        TableCell("${row.callsByPurpose[purpose] ?: 0}", weight = 1f)
                    }
                    TableCell("${row.total}", weight = 0.8f)
                }
            }
        }
    }
}

/** Web版「今月の内訳」の表。 */
@Composable
private fun MonthBreakdownCard(stats: UsageStats) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.usage_month_breakdown_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                TableHeaderCell(stringResource(R.string.usage_col_purpose), weight = 1.2f)
                TableHeaderCell(stringResource(R.string.usage_col_calls), weight = 1f)
                TableHeaderCell(stringResource(R.string.usage_col_quantity), weight = 1f)
                TableHeaderCell(stringResource(R.string.usage_col_cost), weight = 1f)
            }
            HorizontalDivider()
            if (stats.byPurposeMonth.isEmpty()) {
                Text(
                    stringResource(R.string.usage_empty_month),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            stats.byPurposeMonth.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    TableCell(USAGE_PURPOSE_LABEL[row.purpose] ?: row.purpose, weight = 1.2f)
                    TableCell("${row.calls}", weight = 1f)
                    TableCell("${row.quantity}", weight = 1f)
                    TableCell(usd(row.cost), weight = 1f)
                }
            }
        }
    }
}

/** Web版「呼び出し履歴（直近50件）」。モバイル幅に合わせて表ではなくリスト形式で表示する。 */
@Composable
private fun RecentLogCard(recent: List<ApiUsageEntry>) {
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.JAPAN)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.usage_recent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (recent.isEmpty()) {
                Text(
                    stringResource(R.string.usage_empty_recent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            recent.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${dateFormat.format(Date(entry.calledAt))} ・ ${USAGE_PURPOSE_LABEL[entry.purpose] ?: entry.purpose}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            listOfNotNull(
                                entry.screenName?.let { "@$it" },
                                USAGE_RESOURCE_LABEL[entry.resource] ?: entry.resource,
                                "${entry.quantity}件"
                            ).joinToString(" ・ "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(usd(entry.costUsd), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableHeaderCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.weight(weight).padding(vertical = 4.dp)
    )
}

@Composable
private fun RowScope.TableCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(weight)
    )
}
