package com.hilamalu.oshixcollector.data.notification

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知時刻の決定ロジックの検証。通知が「時間帯の外に出ない」「1日に設定した回数だけ出る」
 * 「何度計算しても予定がずれない」ことを担保する。
 */
class NotificationScheduleTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun settings(
        perDay: Int = 3,
        startHour: Int = 9,
        endHour: Int = 22
    ) = NotificationSettings.Snapshot(
        enabled = true,
        perDay = perDay,
        startHour = startHour,
        endHour = endHour,
        targetUserIds = emptySet(),
        favoritesOnly = false,
        faceOnly = false
    )

    private fun at(date: String, hour: Int): Long =
        LocalDate.parse(date).atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun hourOf(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone).hour

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    @Test
    fun `時間帯より前なら当日の時間帯内に予約される`() {
        val now = at("2026-08-19", 3)
        val next = NotificationSchedule.nextFireAt(now, settings(), zone)

        assertEquals(LocalDate.parse("2026-08-19"), dateOf(next))
        assertTrue(next > now)
        assertTrue(hourOf(next) in 9..21)
    }

    @Test
    fun `時間帯を過ぎていれば翌日の最初のスロットへ回る`() {
        val now = at("2026-08-19", 23)
        val next = NotificationSchedule.nextFireAt(now, settings(), zone)

        assertEquals(LocalDate.parse("2026-08-20"), dateOf(next))
        // 1日3回なら最初のスロットは 9:00〜13:20 の範囲
        assertTrue(hourOf(next) in 9..13)
    }

    @Test
    fun `1日の通知回数だけ時間帯の中に予約が並ぶ`() {
        val perDay = 5
        var cursor = at("2026-08-19", 0)
        val fireTimes = mutableListOf<Long>()
        // 「予約→その時刻の直後に再計算」を繰り返すと、実運用と同じ順で当日分が出てくる
        repeat(perDay) {
            val next = NotificationSchedule.nextFireAt(cursor, settings(perDay = perDay), zone)
            fireTimes += next
            cursor = next
        }

        assertEquals(fireTimes.sorted(), fireTimes)
        fireTimes.forEach { fireAt ->
            assertEquals(LocalDate.parse("2026-08-19"), dateOf(fireAt))
            assertTrue(fireAt >= at("2026-08-19", 9))
            assertTrue(fireAt <= at("2026-08-19", 22))
        }
        // 当日分を使い切ると翌日へ回る
        assertEquals(
            LocalDate.parse("2026-08-20"),
            dateOf(NotificationSchedule.nextFireAt(cursor, settings(perDay = perDay), zone))
        )
    }

    @Test
    fun `予約済みの時刻は再計算してもずれない`() {
        val now = at("2026-08-19", 10)
        val first = NotificationSchedule.nextFireAt(now, settings(), zone)
        // 予約後にアプリを起動し直した（＝予約時刻の直前に再計算した）想定
        val recomputed = NotificationSchedule.nextFireAt(first - 1, settings(), zone)

        assertEquals(first, recomputed)
    }

    @Test
    fun `開始と終了が逆転していても終日扱いで予約される`() {
        val now = at("2026-08-19", 10)
        val next = NotificationSchedule.nextFireAt(now, settings(startHour = 22, endHour = 6), zone)

        assertTrue(next > now)
        assertTrue(next <= at("2026-08-20", 0))
    }

    @Test
    fun `1日1回でも時間帯の中に収まる`() {
        val now = at("2026-08-19", 0)
        val next = NotificationSchedule.nextFireAt(now, settings(perDay = 1), zone)

        assertTrue(next >= at("2026-08-19", 9))
        assertTrue(next <= at("2026-08-19", 22))
    }
}
