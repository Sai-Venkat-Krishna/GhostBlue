package com.saivenkatkrishna.ghostblue.ui.components

import android.text.format.DateUtils
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Display formatting shared by the screens, matching the iOS strings. */
object Format {
    /** "%.1f" as Swift's `String(format:)` writes it: C's printf, rounding the exact binary value. */
    fun oneDecimal(value: Double): String = decimals(value, 1)

    fun decimals(value: Double, digits: Int): String = printf(value, digits)

    /** C's "%.nf": the double's exact value rounded half to even, "." as the separator. */
    fun printf(value: Double, digits: Int): String {
        if (!value.isFinite()) return if (value.isNaN()) "nan" else if (value > 0) "inf" else "-inf"
        return BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
    }

    /** ".number.precision(.fractionLength(n))": half-even, locale digits. */
    fun fraction(value: Double, digits: Int): String {
        val rounded = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN)
        return NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(rounded)
    }

    /** "84", "77.4": up to one decimal. */
    fun upToOneDecimal(value: Double): String =
        BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

    fun kWh(value: Double): String = "${oneDecimal(value)} kWh"

    /** Trip energy: kWh from one kilowatt-hour, watt-hours below. */
    fun tripEnergy(kWh: Double): String = if (kWh >= 1) "${oneDecimal(kWh)} kWh" else "${(kWh * 1000).roundToLong()} Wh"

    /** Trip pill energy from watt-hours. */
    fun wattHours(wh: Int): String = if (wh >= 1000) "${oneDecimal(wh / 1000.0)} kWh" else "$wh Wh"

    /** "128 mi": a distance in miles in the owner's unit. */
    fun miles(miles: Double, unit: Distance.Units): String = Distance.Units.MILES.format(miles, unit)

    fun distance(distance: Distance, unit: Distance.Units): String = distance.units.format(distance.length, unit)

    fun temperature(temperature: Temperature, unit: Temperature.Units): String =
        temperature.units.format(temperature.value, unit)

    /** Reported temperatures outside a plausible range are placeholders, not readings. */
    fun isPlausible(temperature: Temperature): Boolean {
        val value = temperature.value
        if (!value.isFinite()) return false
        return if (temperature.units == Temperature.Units.CELSIUS) value in -40.0..60.0 else value in -40.0..140.0
    }

    fun currency(value: Double, digits: Int = 2): String {
        val format = NumberFormat.getCurrencyInstance()
        runCatching { format.currency = Currency.getInstance(Locale.getDefault()) }
            .onFailure { format.currency = Currency.getInstance("USD") }
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        return format.format(value)
    }

    fun currencyCode(): String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD")

    /** "1 hr, 5 min", "45 min": iOS's abbreviated hours-and-minutes style. */
    fun duration(seconds: Double): String {
        val minutes = (seconds / 60).roundToLong()
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours == 0L -> "$remainder min"
            remainder == 0L -> "$hours hr"
            else -> "$hours hr, $remainder min"
        }
    }

    /** "5 min. ago", "2 hr. ago", "Yesterday": the relative style the iOS app shows. */
    fun relative(date: Instant, now: Instant = Instant.now()): String {
        val seconds = Duration.between(date, now).seconds
        if (abs(seconds) < 60) return "just now"
        return DateUtils.getRelativeTimeSpanString(
            date.toEpochMilli(),
            now.toEpochMilli(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }

    private fun pattern(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())

    fun pattern(date: Instant, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
        pattern(pattern).format(date.atZone(zone))

    fun pattern(date: LocalDate, pattern: String): String = pattern(pattern).format(date)

    fun time(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(date.atZone(zone))

    fun dateTime(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
            .format(date.atZone(zone))

    fun dateTimeWithSeconds(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            .format(date.atZone(zone))

    /** "3:41 PM", "3:41 PM tomorrow", "3:41 PM Sat": when a charge will finish. */
    fun finishTime(date: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val time = time(date, zone)
        return when (day) {
            today -> time
            today.plusDays(1) -> "$time tomorrow"
            else -> "$time ${pattern(date, "EEE", zone)}"
        }
    }

    /** Parses what the owner typed, accepting the locale's decimal separator as well as ".". */
    fun parseDecimal(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toDoubleOrNull()?.let { return it }
        val position = java.text.ParsePosition(0)
        val number = NumberFormat.getNumberInstance().parse(trimmed, position)
        return if (number != null && position.index == trimmed.length) number.toDouble() else null
    }
}
