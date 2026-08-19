package com.hilamalu.oshixcollector.data.notification

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 「1日N回・指定した時間帯の中でランダムに」通知する時刻を決める純粋なロジック。
 *
 * 時間帯をN等分したスロットを作り、各スロットの中でランダムな1点を選ぶ。
 * 完全なランダム（N点をバラバラに引く）にすると通知が固まって連続することがあるため、
 * 「ばらけて見える」ことを優先してスロット方式にしている。
 *
 * スロット内の位置は「日付＋スロット番号」をシードにした疑似乱数で決めるため、
 * 同じ日の同じスロットを何度計算しても同じ時刻になる。設定変更やアプリ起動のたびに
 * 再計算されても予定がずれない（＝直前に前倒しされて連続通知になったりしない）。
 */
object NotificationSchedule {

    /**
     * [nowMillis]より後の、次に通知すべき時刻（epoch millis）を返す。
     * 当日の残りスロットが無ければ翌日の最初のスロットを返す。
     */
    fun nextFireAt(
        nowMillis: Long,
        settings: NotificationSettings.Snapshot,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val perDay = settings.perDay.coerceIn(1, MAX_NOTIFICATIONS_PER_DAY)
        val configuredStart = settings.startHour.coerceIn(0, 23) * 60
        val configuredEnd = settings.endHour.coerceIn(1, 24) * 60
        // 開始 >= 終了（設定の取り違え・旧データ）なら終日を対象にする。
        // そのまま計算すると幅0や負の時間帯になり、通知が一切出ない状態になるため
        val startMinute = if (configuredEnd <= configuredStart) 0 else configuredStart
        val endMinute = if (configuredEnd <= configuredStart) MINUTES_PER_DAY else configuredEnd

        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        // 当日分を先に見て、全スロットを過ぎていれば翌日の最初のスロットへ回す
        for (dayOffset in 0L..1L) {
            val date = today.plusDays(dayOffset)
            for (slot in 0 until perDay) {
                val fireAt = fireTimeOf(date, slot, startMinute, endMinute, perDay, zone)
                if (fireAt > nowMillis) return fireAt
            }
        }
        // ここには到達しない（翌日のスロットは必ず未来）が、保険として1時間後を返す
        return nowMillis + 60 * 60 * 1000L
    }

    /**
     * [date]の[slot]番目のスロットに割り当てる通知時刻。
     * スロットの境界を整数の分で切ることで、隣り合うスロットが同じ分に重ならない
     * （＝1日にスロット数ぴったりの通知が出る）ことを保証する。
     */
    private fun fireTimeOf(
        date: LocalDate,
        slot: Int,
        startMinute: Int,
        endMinute: Int,
        perDay: Int,
        zone: ZoneId
    ): Long {
        val windowMinutes = endMinute - startMinute
        val slotStart = startMinute + windowMinutes * slot / perDay
        val slotEnd = startMinute + windowMinutes * (slot + 1) / perDay
        // 日付とスロット番号だけで決まるシード（同じスロットは何度計算しても同じ時刻になる）
        val jitter = Random(date.toEpochDay() * 31 + slot).nextDouble()
        // 切り捨てるため、必ず slotStart 以上 slotEnd 未満に収まる
        val minuteOfDay = slotStart + ((slotEnd - slotStart) * jitter).toInt()
        // ZonedDateTime越しに加算することで、夏時間の切り替わり日でも実時刻がずれない
        return date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
