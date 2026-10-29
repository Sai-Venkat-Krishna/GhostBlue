package com.saivenkatkrishna.ghostblue.kit.api

import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.HyundaiUSAAPIClient
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region

/**
 * Creates the client for a real brand and region. The fake brand and any
 * caching wrapper belong to the app, as in BetterBlueKit.
 *
 * Android so far has Hyundai USA; the other BetterBlueKit clients (Hyundai
 * Canada and Europe, Kia USA) are still to be ported.
 */
fun createBetterBlueKitAPIClient(configuration: APIClientConfiguration): APIClient =
    when (configuration.brand) {
        Brand.HYUNDAI -> when (configuration.region) {
            Region.USA -> HyundaiUSAAPIClient(configuration)
            else -> throw notSupported(Brand.HYUNDAI, configuration.region)
        }
        Brand.KIA -> throw notSupported(Brand.KIA, configuration.region)
        Brand.FAKE -> throw APIError.regionNotSupported(
            "Fake brand is not supported by BetterBlueKit API client factory",
        )
    }

private fun notSupported(brand: Brand, region: Region) =
    APIError.regionNotSupported("${brand.displayName} is not yet supported in ${region.rawValue}")

/** Regions with a working client on Android. */
fun supportedRegions(brand: Brand): List<Region> = when (brand) {
    Brand.HYUNDAI -> listOf(Region.USA)
    Brand.KIA -> emptyList()
    Brand.FAKE -> Region.entries.toList()
}

fun betaRegions(@Suppress("UNUSED_PARAMETER") brand: Brand): List<Region> = emptyList()
