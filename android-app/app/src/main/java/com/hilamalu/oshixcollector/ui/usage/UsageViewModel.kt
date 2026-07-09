package com.hilamalu.oshixcollector.ui.usage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.backup.ApiUsageEntry
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.FirestoreMirror
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Web版usage/page.tsxのPURPOSESと同じ表示順・ラベル。 */
val USAGE_PURPOSES = listOf("resolve", "check", "collect", "backfill")

val USAGE_PURPOSE_LABEL = mapOf(
    "resolve" to "ID解決",
    "check" to "新着チェック",
    "collect" to "新規収集",
    "backfill" to "バックフィル"
)

val USAGE_RESOURCE_LABEL = mapOf(
    "user_read" to "User: Read",
    "posts_read" to "Posts: Read"
)

data class DailyUsage(
    val date: LocalDate,
    val calls: Int,
    val quantity: Int,
    val cost: Double
)

data class PurposeUsage(
    val purpose: String,
    val calls: Int,
    val quantity: Int,
    val cost: Double
)

/** 期間（日 or 月）× 用途別の呼び出し回数（Web版pivotByPurposeの結果に対応）。 */
data class PeriodPurposeRow(
    val periodLabel: String,
    val callsByPurpose: Map<String, Int>,
    val total: Int
)

/** Web版`/api/usage`のレスポンス相当をクライアント側で集計したもの。 */
data class UsageStats(
    val todayCost: Double,
    val monthCost: Double,
    val allTimeCost: Double,
    val monthCalls: Int,
    val monthQuantity: Int,
    val daily: List<DailyUsage>,
    val dailyByPurpose: List<PeriodPurposeRow>,
    val monthlyByPurpose: List<PeriodPurposeRow>,
    val byPurposeMonth: List<PurposeUsage>,
    val recent: List<ApiUsageEntry>
)

class UsageViewModel(application: Application) : AndroidViewModel(application) {
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val firestoreMirror = FirestoreMirror(application, googleAuthManager)

    var isLoading by mutableStateOf(false)
        private set

    var stats by mutableStateOf<UsageStats?>(null)
        private set

    /** クラウドバックアップ未設定などで読み込めない場合の案内メッセージ。 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                if (!cloudBackupSettings.isEnabled.first()) {
                    errorMessage = "API使用量はFirestoreに記録されます。設定画面でクラウドバックアップを有効にしてください。"
                    return@launch
                }
                if (googleAuthManager.currentUser == null) {
                    googleAuthManager.signIn()
                }
                stats = aggregate(firestoreMirror.fetchApiUsageLog())
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    /** Web版`lib/repo/apiUsage.ts`のgetUsageStats相当（SQL集計のKotlin移植）。 */
    private fun aggregate(entries: List<ApiUsageEntry>): UsageStats {
        val zone = ZoneId.systemDefault()
        fun dateOf(entry: ApiUsageEntry): LocalDate =
            Instant.ofEpochMilli(entry.calledAt).atZone(zone).toLocalDate()

        val today = LocalDate.now(zone)
        val monthStart = today.withDayOfMonth(1)
        val monthEntries = entries.filter { !dateOf(it).isBefore(monthStart) }
        val since30d = today.minusDays(29)
        val last30d = entries.filter { !dateOf(it).isBefore(since30d) }
        val since12mo = YearMonth.from(today).minusMonths(11)

        val daily = (0..29).map { offset ->
            val date = since30d.plusDays(offset.toLong())
            val dayEntries = last30d.filter { dateOf(it) == date }
            DailyUsage(
                date = date,
                calls = dayEntries.size,
                quantity = dayEntries.sumOf { it.quantity },
                cost = dayEntries.sumOf { it.costUsd }
            )
        }

        // 日次×用途（呼び出しのあった日のみ、新しい順）
        val dailyByPurpose = last30d
            .groupBy { dateOf(it) }
            .toSortedMap(reverseOrder())
            .map { (date, dayEntries) ->
                val counts = dayEntries.groupingBy { it.purpose }.eachCount()
                PeriodPurposeRow(
                    periodLabel = "${date.monthValue}/${date.dayOfMonth}",
                    callsByPurpose = counts,
                    total = dayEntries.size
                )
            }

        // 月次×用途（直近12ヶ月のうち呼び出しのあった月のみ、新しい順）
        val monthlyByPurpose = entries
            .groupBy { YearMonth.from(dateOf(it)) }
            .filterKeys { !it.isBefore(since12mo) }
            .toSortedMap(reverseOrder())
            .map { (month, monthRows) ->
                val counts = monthRows.groupingBy { it.purpose }.eachCount()
                PeriodPurposeRow(
                    periodLabel = "${month.year}年${month.monthValue}月",
                    callsByPurpose = counts,
                    total = monthRows.size
                )
            }

        val byPurposeMonth = monthEntries
            .groupBy { it.purpose }
            .map { (purpose, rows) ->
                PurposeUsage(
                    purpose = purpose,
                    calls = rows.size,
                    quantity = rows.sumOf { it.quantity },
                    cost = rows.sumOf { it.costUsd }
                )
            }
            .sortedBy { USAGE_PURPOSES.indexOf(it.purpose).let { i -> if (i < 0) USAGE_PURPOSES.size else i } }

        return UsageStats(
            todayCost = entries.filter { dateOf(it) == today }.sumOf { it.costUsd },
            monthCost = monthEntries.sumOf { it.costUsd },
            allTimeCost = entries.sumOf { it.costUsd },
            monthCalls = monthEntries.size,
            monthQuantity = monthEntries.sumOf { it.quantity },
            daily = daily,
            dailyByPurpose = dailyByPurpose,
            monthlyByPurpose = monthlyByPurpose,
            byPurposeMonth = byPurposeMonth,
            recent = entries.take(50)
        )
    }
}
