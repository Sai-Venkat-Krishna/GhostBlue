package com.saivenkatkrishna.ghostblue

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleSeeds
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import com.saivenkatkrishna.ghostblue.ui.GhostBlueApp
import com.saivenkatkrishna.ghostblue.ui.PlaceholderMapContent
import java.util.UUID
import kotlinx.coroutines.runBlocking

const val TEST_USERNAME = "testaccount@betterblue.com"
const val TEST_PASSWORD = "betterblue"

/** A fresh app: an in-memory database, its own settings, and command checks that don't wait. */
fun testGraph(): AppGraph {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    return AppGraph(GhostBlueDatabase.inMemory(context), AppSettings(prefs), commandWaitSeconds = 0)
}

/** Signs in as the test account (three test cars) without going through the screens. */
fun AppGraph.signInTestAccount(): AppGraph = apply {
    val error = runBlocking {
        garage.signIn(TEST_USERNAME, TEST_PASSWORD, "", Brand.FAKE, Region.USA, FakeVehicleSeeds.defaultRecords(UUID.randomUUID()))
    }
    check(error == null) { "Test sign-in failed: $error" }
}

/** The app as it runs, minus Google Maps and network images. */
fun ComposeContentTestRule.setApp(graph: AppGraph) {
    setContent { GhostBlueApp(graph, mapContent = PlaceholderMapContent, remoteImages = false) }
}

fun ComposeContentTestRule.waitForTag(tag: String, timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeContentTestRule.waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
}

fun SemanticsNodeInteractionsProvider.firstWithTag(tag: String): SemanticsNodeInteraction =
    onAllNodes(hasTestTag(tag))[0]
