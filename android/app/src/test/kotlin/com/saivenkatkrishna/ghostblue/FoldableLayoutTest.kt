package com.saivenkatkrishna.ghostblue

import android.app.Application
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An unfolded Pixel Fold's inner screen: from 600 dp wide the screens use
 * two columns, as iOS's `AdaptiveLayout` does on the iPhone Duo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w841dp-h701dp-xhdpi")
class FoldableLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homePutsTheBriefingBesideTheCar() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("briefingCard")
        val car = compose.onNodeWithTag("batteryPercent").getBoundsInRoot()
        val briefing = compose.onNodeWithTag("briefingCard").getBoundsInRoot()
        assertTrue("briefing ${briefing.left} should start right of the car ${car.right}", briefing.left > car.right)
        assertTrue(briefing.top < 400.dp)
    }

    @Test fun energyPairsItsCards() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_ENERGY").performClick()
        compose.waitForTag("energyDelivered")
        val status = compose.onNodeWithTag("chargingStatus").getBoundsInRoot()
        val delivered = compose.onNodeWithTag("energyDelivered").getBoundsInRoot()
        assertTrue(delivered.left > status.right)
    }

    @Test fun tripsShowTheOverviewBesideTheCharts() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_TRIPS").performClick()
        compose.waitForTag("efficiencyTrend")
        val overview = compose.onNodeWithTag("tripsOverview").getBoundsInRoot()
        val trend = compose.onAllNodes(hasTestTag("efficiencyTrend"))[0].getBoundsInRoot()
        assertTrue(trend.left > overview.right)
    }
}
