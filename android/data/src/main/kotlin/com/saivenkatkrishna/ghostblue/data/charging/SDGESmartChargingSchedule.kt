package com.saivenkatkrishna.ghostblue.data.charging

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class SmartChargingPreference(val rawValue: String) {
    CHARGE("charge"),
    PAUSE("pause"),
}

/** A schedule boundary where the preference changes. */
data class SmartChargingTransition(val date: Instant, val preference: SmartChargingPreference) {
    val title: String
        get() = if (preference == SmartChargingPreference.CHARGE) "Charging window opens" else "Charging window closes"
}

/**
 * SDG&E's residential EV super-off-peak periods, in San Diego time whatever
 * the phone's time zone. Weekdays charge 12–6 AM and 10 AM–2 PM; weekends
 * and holidays charge 12 AM–2 PM. Port of GhostBlue's `SDGESmartChargingSchedule`.
 */
object SDGESmartChargingSchedule {
    val timeZone: ZoneId = ZoneId.of("America/Los_Angeles")

    fun preference(at: Instant, zone: ZoneId = timeZone): SmartChargingPreference {
        val local = at.atZone(zone)
        val minuteOfDay = local.hour * 60 + local.minute
        if (isWeekendOrHoliday(at, zone)) {
            return if (minuteOfDay < 14 * 60) SmartChargingPreference.CHARGE else SmartChargingPreference.PAUSE
        }
        val isOvernight = minuteOfDay < 6 * 60
        val isMidday = minuteOfDay >= 10 * 60 && minuteOfDay < 14 * 60
        return if (isOvernight || isMidday) SmartChargingPreference.CHARGE else SmartChargingPreference.PAUSE
    }

    fun isWeekendOrHoliday(date: Instant, zone: ZoneId = timeZone): Boolean {
        val day = date.atZone(zone).dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return true
        return isHoliday(date, zone)
    }

    fun isHoliday(date: Instant, zone: ZoneId = timeZone): Boolean {
        val day = date.atZone(zone).toLocalDate()
        return day in holidayDates(day.year)
    }

    fun holidayDates(year: Int): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        // The fixed-date holiday itself; a Sunday one is also observed on
        // Monday, but a Saturday one is not moved to Friday.
        for ((month, day) in listOf(1 to 1, 7 to 4, 11 to 11, 12 to 25)) {
            val actual = LocalDate.of(year, month, day)
            dates += actual
            if (actual.dayOfWeek == DayOfWeek.SUNDAY) dates += actual.plusDays(1)
        }
        // Presidents' Day, Memorial Day, Labor Day and Thanksgiving.
        dates += LocalDate.of(year, 2, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
        dates += LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))
        dates += LocalDate.of(year, 9, 1).with(TemporalAdjusters.dayOfWeekInMonth(1, DayOfWeek.MONDAY))
        dates += LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        return dates
    }

    /** The next [count] boundaries after [date] where the preference actually changes. */
    fun transitions(after: Instant, count: Int = 4): List<SmartChargingTransition> {
        if (count <= 0) return emptyList()
        val start = after.atZone(timeZone).toLocalDate()
        val result = mutableListOf<SmartChargingTransition>()
        for (offset in 0..maxOf(7, count)) {
            val day = start.plusDays(offset.toLong())
            for (hour in listOf(0, 6, 10, 14)) {
                val boundary = ZonedDateTime.of(day.atTime(hour, 0), timeZone).toInstant()
                if (boundary <= after) continue
                val next = preference(boundary)
                if (next == preference(boundary.minusSeconds(1))) continue
                result += SmartChargingTransition(boundary, next)
                if (result.size == count) return result
            }
        }
        return result
    }

    fun mostRecentTransition(at: Instant): Instant? {
        val start = at.atZone(timeZone).minusDays(2).toInstant()
        return transitions(start, count = 16).lastOrNull { it.date <= at }?.date
    }

    /** "Oct 19, 2026 at 7:00 AM PDT", in San Diego time. */
    fun formattedTime(date: Instant): String {
        val local = date.atZone(timeZone)
        val text = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.US).format(local)
        return "$text ${DateTimeFormatter.ofPattern("zzz", Locale.US).format(local)}"
    }
}
