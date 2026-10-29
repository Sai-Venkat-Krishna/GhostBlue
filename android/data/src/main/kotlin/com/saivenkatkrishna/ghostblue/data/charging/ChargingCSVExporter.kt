package com.saivenkatkrishna.ghostblue.data.charging

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import com.saivenkatkrishna.ghostblue.data.vehicle.energySource
import com.saivenkatkrishna.ghostblue.kit.model.swiftDescription
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Charging history as CSV: UTC dates, every value quoted, CRLF lines. No VIN, as on iOS. */
object ChargingCSVExporter {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun csv(sessions: List<ChargeSession>, vehicle: BBVehicle): String {
        val header = "Started (UTC),Ended (UTC),State,Start SOC (%),End SOC (%),Energy (kWh),Energy source,Cost,Cost source,Location,Charger"
        val rows = sessions.map { session ->
            listOf(
                format(session.startDate),
                session.endDate?.let(::format) ?: "",
                if (session.isActive) "In progress" else "Completed",
                swiftDescription(session.startPercentage),
                swiftDescription(session.endPercentage),
                swiftDescription(vehicle.energyDelivered(session)),
                vehicle.energySource(session),
                vehicle.chargingCost(session)?.let(::swiftDescription) ?: "",
                if (session.actualCost == null) "Estimated" else "Receipt",
                session.locationType.title,
                if (session.isDCFast) "DC" else "AC",
            ).joinToString(",", transform = ::escape)
        }
        return (listOf(header) + rows).joinToString("\r\n") + "\r\n"
    }

    private fun format(date: Instant) = formatter.format(date)

    private fun escape(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
