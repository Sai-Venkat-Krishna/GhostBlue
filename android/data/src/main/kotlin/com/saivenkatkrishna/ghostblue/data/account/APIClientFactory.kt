package com.saivenkatkrishna.ghostblue.data.account

import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.api.createBetterBlueKitAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.fake.StoreFakeVehicleProvider
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import kotlinx.coroutines.CoroutineScope

/**
 * The cached client for an account. The test account always gets the test
 * car, whose server state lives in [db]'s vehicle rows.
 *
 * Unlike iOS, which falls back to the test car if a client can't be made,
 * this throws BetterBlueKit's "not yet supported" error: Android doesn't
 * have every brand and region yet, and a silent fake car would hide that.
 */
fun createAPIClient(configuration: APIClientConfiguration, db: GhostBlueDatabase, scope: CoroutineScope): CachedAPIClient {
    val brand = if (isTestAccount(configuration.username, configuration.password)) Brand.FAKE else configuration.brand
    if (brand == Brand.FAKE) {
        BBLogger.info(BBLogCategory.API, "APIClientFactory: Creating Room-backed Fake API client")
        val client = FakeAPIClient(configuration, StoreFakeVehicleProvider(RoomFakeVehicleStore(db)))
        return CachedAPIClient(client, scope)
    }
    BBLogger.info(
        BBLogCategory.API,
        "APIClientFactory: Creating ${brand.displayName} API client for ${configuration.region.rawValue}",
    )
    return CachedAPIClient(createBetterBlueKitAPIClient(configuration.copy(brand = brand)), scope)
}
