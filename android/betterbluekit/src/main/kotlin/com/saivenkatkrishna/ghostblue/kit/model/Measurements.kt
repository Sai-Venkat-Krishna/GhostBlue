package com.saivenkatkrishna.ghostblue.kit.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Distance(val length: Double, val units: Units) {
    @Serializable
    enum class Units(val rawValue: String, val displayName: String, val abbreviation: String) {
        @SerialName("miles") MILES("miles", "Miles", "mi"),
        @SerialName("kilometers") KILOMETERS("kilometers", "Kilometers", "km");

        fun convert(length: Double, to: Units): Double = when {
            this == to -> length
            this == MILES && to == KILOMETERS -> length * 1.609344
            this == KILOMETERS && to == MILES -> length / 1.609344
            else -> length
        }

        fun format(length: Double, to: Units): String =
            "${formatWholeNumber(convert(length, to))} ${to.abbreviation}"

        companion object {
            /** The API's unit code: 1 is kilometres, anything else miles. */
            fun fromCode(code: Int): Units = if (code == 1) KILOMETERS else MILES
        }
    }
}

@Serializable
data class Temperature(val value: Double, val units: Units) {
    @Serializable
    enum class Units(val rawValue: String, val displayName: String, val symbol: String) {
        @SerialName("celsius") CELSIUS("celsius", "Celsius", "°C"),
        @SerialName("fahrenheit") FAHRENHEIT("fahrenheit", "Fahrenheit", "°F");

        fun integer(): Int = if (this == FAHRENHEIT) 1 else 0

        /** Standard HVAC range in these units. */
        val hvacRange: ClosedFloatingPointRange<Double>
            get() = if (this == FAHRENHEIT) 62.0..82.0 else 16.0..28.0

        fun format(temperature: Double, to: Units): String {
            val converted = when {
                this == CELSIUS && to == FAHRENHEIT -> temperature * 9.0 / 5.0 + 32.0
                this == FAHRENHEIT && to == CELSIUS -> (temperature - 32.0) * 5.0 / 9.0
                else -> temperature
            }
            return "${formatWholeNumber(converted)}${to.symbol}"
        }

        companion object {
            /** The API's unit code: 1 is Fahrenheit, anything else (or none) Celsius. */
            fun fromCode(code: Int?): Units = if (code == 1) FAHRENHEIT else CELSIUS
        }
    }

    companion object {
        const val MINIMUM = 62.0
        const val MAXIMUM = 82.0

        /** Parses the API's temperature: a number, "HI", or anything else as the minimum. */
        fun fromApi(units: Int?, value: String?): Temperature {
            val number = value?.toDoubleOrNull()
            val parsed = when {
                number != null -> number
                value == "HI" -> MAXIMUM
                else -> MINIMUM
            }
            return Temperature(parsed, Units.fromCode(units))
        }
    }
}

/** Formats like Foundation's NumberFormatter with no fraction digits (rounds half to even). */
internal fun formatWholeNumber(value: Double): String =
    if (value.isNaN() || value.isInfinite()) "0"
    else BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
