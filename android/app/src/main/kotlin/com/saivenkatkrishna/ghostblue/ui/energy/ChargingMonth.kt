package com.saivenkatkrishna.ghostblue.ui.energy

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * This calendar month's charging for a car, as the "This month" section of
 * iOS's `ChargingIntelligenceView`: what it cost (receipts, else saved
 * tariffs, else rates), the energy, a straight-line projection to the end of
 * the month, and the owner's budget.
 */
data class ChargingMonth(
    val cost: Double,
    val energyKWh: Double,
    val projectedCost: Double,
    val budget: Double?,
) {
    /** Spent share of the budget, capped at 1; null without a budget. */
    val budgetFraction: Double?
        get() = budget?.takeIf { it > 0 }?.let { (cost / it).coerceIn(0.0, 1.0) }

    val isOverBudget: Boolean get() = budget != null && budget > 0 && cost > budget

    /** Under budget so far, but not at this pace. */
    val isOnPaceToExceed: Boolean get() = budget != null && budget > 0 && !isOverBudget && projectedCost > budget

    companion object {
        fun of(
            vehicle: BBVehicle,
            sessions: List<ChargeSession>,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): ChargingMonth {
            val today = now.atZone(zone).toLocalDate()
            val month = YearMonth.from(today)
            val inMonth = sessions.filter { it.vin == vehicle.vin && YearMonth.from(it.startDate.atZone(zone)) == month }
            val cost = inMonth.sumOf { vehicle.chargingCost(it) ?: 0.0 }
            val energy = inMonth.sumOf { vehicle.energyDelivered(it) }
            val day = maxOf(1, today.dayOfMonth)
            return ChargingMonth(
                cost = cost,
                energyKWh = energy,
                projectedCost = cost / day * month.lengthOfMonth(),
                budget = vehicle.monthlyChargingBudget,
            )
        }
    }
}
