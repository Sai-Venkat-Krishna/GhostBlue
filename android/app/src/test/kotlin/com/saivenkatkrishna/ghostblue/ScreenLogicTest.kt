package com.saivenkatkrishna.ghostblue

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.model.VehicleImageColor
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.ui.components.VehicleImages
import com.saivenkatkrishna.ghostblue.ui.energy.ChargeHistoryRange
import com.saivenkatkrishna.ghostblue.ui.energy.ChargeHistorySummary
import com.saivenkatkrishna.ghostblue.ui.energy.ChargePowerFilter
import com.saivenkatkrishna.ghostblue.ui.energy.SessionDraft
import com.saivenkatkrishna.ghostblue.ui.home.briefingItems
import com.saivenkatkrishna.ghostblue.ui.home.chargeTarget
import com.saivenkatkrishna.ghostblue.ui.trips.TripAnalytics
import com.saivenkatkrishna.ghostblue.ui.trips.TripCSVExporter
import com.saivenkatkrishna.ghostblue.ui.trips.TripChartData
import com.saivenkatkrishna.ghostblue.ui.trips.TripDateFilter
import com.saivenkatkrishna.ghostblue.ui.trips.TripTrendPeriod
import com.saivenkatkrishna.ghostblue.ui.trips.comparisonSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the screens port from the iOS views, with the iOS tests' cases where they exist. */
class ScreenLogicTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-28T18:00:00Z")

    private fun trip(daysAgo: Long, distance: Double, wh: Int, parts: IntArray = intArrayOf(0, 0, 0, 0)) = EVTripDetail(
        distance = distance,
        odometer = 1000.0 + daysAgo,
        accessoriesEnergy = parts[2],
        totalEnergyUsed = wh,
        regenEnergy = 0,
        climateEnergy = parts[1],
        drivetrainEnergy = parts[0],
        batteryCareEnergy = parts[3],
        startDate = now.minusSeconds(daysAgo * 86_400),
        durationSeconds = 1800,
        avgSpeed = 30.0,
        maxSpeed = 60.0,
    )

    // MARK: - Car image

    @Test fun vehicleImageURLMatchesIOS() {
        assertEquals("https://bluelink.andyfase.com/app-assets/car-images//ioniq5/white.png", VehicleImages.url("Hyundai IONIQ 5", VehicleImageColor.WHITE))
        assertEquals("ioniq5n", VehicleImages.modelKey("2025 IONIQ 5 N"))
        assertEquals("ev6", VehicleImages.modelKey("Kia EV6"))
        assertEquals("ioniq5", VehicleImages.modelKey("Sonata"))
        assertEquals(
            "https://bluelink.andyfase.com/app-assets/car-images//ioniq6/matte-grey.png",
            VehicleImages.url("IONIQ 6", VehicleImageColor.MATTE_GREY),
        )
    }

    // MARK: - Trips (TripChartDataTests, CustomizationAnalyticsTests)

    @Test fun axisLabelsAreEvenlySpacedAndKeepTheLast() {
        assertEquals(emptyList<Int>(), TripChartData.axisIndices(0))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), TripChartData.axisIndices(6))
        assertEquals(listOf(0, 3, 6, 9, 11), TripChartData.axisIndices(12))
        assertEquals(listOf(0, 2, 4, 6), TripChartData.axisIndices(7))
    }

    @Test fun chartTakesTheNewestTwelveOldestFirst() {
        val trips = (0 until 15).map { trip(it.toLong(), 10.0, 3000) }
        val indexed = TripChartData.indexedTrips(trips)
        assertEquals(12, indexed.size)
        assertEquals(trips[11], indexed.first().second)
        assertEquals(trips[0], indexed.last().second)
        assertEquals(1.0, TripChartData.yAxisMaximum(emptyList()), 0.0)
        assertEquals(11.5, TripChartData.yAxisMaximum(listOf(10.0, 2.0)), 1e-9)
    }

    @Test fun energyFallsBackToThePositiveParts() {
        assertEquals(2.5, TripAnalytics.energyKWh(trip(0, 10.0, 2500)), 1e-9)
        assertEquals(3.0, TripAnalytics.energyKWh(trip(0, 10.0, 0, intArrayOf(2000, 500, 500, -100))), 1e-9)
        assertNull(TripAnalytics.efficiencyMilesPerKWh(trip(0, 0.0, 2500)))
        assertEquals(4.0, TripAnalytics.efficiencyMilesPerKWh(trip(0, 10.0, 2500))!!, 1e-9)
    }

    @Test fun dateFiltersDropOldAndFutureTrips() {
        val trips = listOf(trip(1, 5.0, 1000), trip(10, 5.0, 1000), trip(-1, 5.0, 1000))
        assertEquals(1, TripAnalytics.filtered(trips, TripDateFilter.SEVEN_DAYS, now, zone).size)
        assertEquals(2, TripAnalytics.filtered(trips, TripDateFilter.THIRTY_DAYS, now, zone).size)
        assertEquals(3, TripAnalytics.filtered(trips, TripDateFilter.ALL, now, zone).size)
    }

    @Test fun trendAggregatesPerPeriodAndReportsTheChange() {
        val trips = listOf(trip(0, 20.0, 5000), trip(0, 10.0, 5000), trip(1, 40.0, 10000))
        val points = TripAnalytics.trendPoints(trips, TripTrendPeriod.DAILY, zone, Locale.US)
        assertEquals(2, points.size)
        assertEquals(4.0, points[0].efficiencyMilesPerKWh, 1e-9)
        assertEquals(3.0, points[1].efficiencyMilesPerKWh, 1e-9)
        assertEquals(-25.0, TripAnalytics.percentageChange(points)!!, 1e-9)
        val extremes = TripAnalytics.extremes(trips)!!
        assertEquals(trips[0], extremes.best)
        assertEquals(trips[1], extremes.worst)
    }

    @Test fun csvMatchesTheIOSExport() {
        // 12.345 is stored as 12.3450000000000006…, so C's printf (and Swift) round it up.
        val first = trip(0, 12.345, 3210)
        val record = TripRecord("VIN1", first).apply {
            customName = "Commute, home"
            isFavorite = true
            startLocationName = "Home"
        }
        val csv = TripCSVExporter.csv(listOf(first), "VIN1", mapOf(record.tripKey to record), ratePerKWh = 0.3)
        val lines = csv.split("\n")
        assertEquals(
            "Date,Name,Favorite,Distance (mi),Distance (km),Energy (kWh),Efficiency (mi/kWh),Efficiency (km/kWh),Duration (min),Average speed (mph),Maximum speed (mph),Start,Destination,Estimated cost",
            lines[0],
        )
        assertEquals("2026-10-28T18:00:00Z,\"Commute, home\",Yes,12.35,19.87,3.210,3.85,6.19,30.0,30.0,60.0,Private location,,0.96", lines[1])
        assertTrue(csv.endsWith("\n"))
    }

    @Test fun comparisonCallsTiesAndWinners() {
        assertEquals("These trips have nearly identical efficiency", comparisonSummary(trip(0, 10.0, 2500), trip(1, 20.0, 5000), Distance.Units.MILES))
        assertEquals("Trip A was 1.0 mi/kWh more efficient", comparisonSummary(trip(0, 10.0, 2000), trip(1, 8.0, 2000), Distance.Units.MILES))
    }

    // MARK: - Energy

    private fun vehicle() = BBVehicle().apply {
        vin = "VIN1"
        model = "IONIQ 5"
        fuelType = FuelType.ELECTRIC
        batteryCapacityKWh = 80.0
        electricityRatePerKWh = 0.25
    }

    private fun session(daysAgo: Long, from: Double, to: Double, peak: Double = 7.0) =
        ChargeSession("VIN1", now.minusSeconds(daysAgo * 86_400), from, isDCFast = false).apply {
            endPercentage = to
            endDate = startDate.plusSeconds(3600)
            lastSampleDate = endDate!!
            maxChargeSpeedKW = peak
            locationType = ChargingLocationType.PUBLIC_AC
        }

    @Test fun weekIsTodayAndTheSixDaysBefore() {
        val start = ChargeHistoryRange.WEEK.start(now, zone)
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), start)
    }

    @Test fun summaryTotalsEnergyCostAndChange() {
        val sessions = listOf(session(0, 20.0, 30.0), session(2, 50.0, 60.0, peak = 1.5), session(10, 10.0, 20.0))
        val summary = ChargeHistorySummary(sessions, vehicle(), ChargeHistoryRange.WEEK, ChargePowerFilter.ALL, now, zone, Locale.US)
        assertEquals(2, summary.displayed.size)
        assertEquals(16.0, summary.totalEnergyKWh, 1e-9)
        assertEquals(4.0, summary.totalCost!!, 1e-9)
        assertEquals(7, summary.buckets.size)
        assertEquals(8.0, summary.buckets.last().energyKWh, 1e-9)
        // The week before had one 8 kWh session.
        assertEquals(100.0, summary.energyChange!!, 1e-9)
        val slow = ChargeHistorySummary(sessions, vehicle(), ChargeHistoryRange.MONTH, ChargePowerFilter.UNDER_TWO_KW, now, zone, Locale.US)
        assertEquals(1, slow.displayed.size)
    }

    @Test fun quarterSumsByWeek() {
        val summary = ChargeHistorySummary(listOf(session(0, 20.0, 30.0)), vehicle(), ChargeHistoryRange.QUARTER, ChargePowerFilter.ALL, now, zone, Locale.US)
        assertTrue(summary.buckets.size in 13..15)
        assertEquals(LocalDate.of(2026, 10, 25), summary.buckets.last().start)
    }

    @Test fun sessionDraftValidatesAndPrices() {
        val vehicle = vehicle()
        val draft = SessionDraft.forNewSession(vehicle, now)
        assertNull(draft.validationMessage)
        assertEquals(8.0, draft.energyKWh(vehicle, null), 1e-9)
        assertEquals(2.0, draft.cost(vehicle, null, now)!!, 1e-9)
        assertEquals("End time must be later than start time.", draft.copy(end = draft.start).validationMessage)
        assertEquals("Energy must be zero or greater.", draft.copy(energyText = "-1").validationMessage)
        assertEquals("Rate must be greater than zero.", draft.copy(rateText = "0").validationMessage)
        assertEquals(12.5, draft.copy(actualCostText = "12.5").cost(vehicle, null, now)!!, 1e-9)

        val saved = draft.copy(energyText = "9").newSession(vehicle)
        draft.copy(energyText = "9").applyTo(saved, vehicle)
        assertEquals(9.0, saved.manualEnergyKWh!!, 1e-9)
        assertEquals(30.0, saved.endPercentage, 0.0)
    }

    // MARK: - Home

    @Test fun briefingFlagsWhatNeedsAttention() {
        val vehicle = vehicle().apply {
            lockStatus = VehicleStatus.LockStatus.UNLOCKED
            battery12V = 15
            lastUpdated = now
        }
        val items = briefingItems(vehicle, Distance.Units.MILES, now).map { it.title }
        assertEquals(listOf("12V battery is low", "Vehicle is unlocked"), items)
        val fine = vehicle().apply {
            lockStatus = VehicleStatus.LockStatus.LOCKED
            lastUpdated = now
        }
        assertEquals("Everything looks good", briefingItems(fine, Distance.Units.MILES, now).single().title)
    }

    @Test fun chargeTargetStaysBetweenTheLevelAndFull() {
        fun ev(percentage: Double, target: Double?) = VehicleStatus.EVStatus(
            charging = true,
            chargeSpeed = 7.0,
            evRange = VehicleStatus.FuelRange(Distance(200.0, Distance.Units.MILES), percentage),
            plugType = VehicleStatus.PlugType.AC_CHARGER,
            targetSocAC = target,
        )
        assertEquals(80, chargeTarget(ev(50.0, 80.0)))
        assertEquals(90, chargeTarget(ev(90.0, 80.0)))
        assertEquals(100, chargeTarget(ev(50.0, null)))
    }
}
