package com.saivenkatkrishna.ghostblue.kit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Brand(val rawValue: String, val displayName: String) {
    @SerialName("hyundai") HYUNDAI("hyundai", "Hyundai"),
    @SerialName("kia") KIA("kia", "Kia"),
    @SerialName("fake") FAKE("fake", "Fake (Testing)");

    companion object {
        fun fromRawValue(raw: String): Brand? = entries.firstOrNull { it.rawValue == raw }

        /** Debug builds and the test account can pick the fake brand. */
        fun availableBrands(
            username: String = "",
            password: String = "",
            includeTestBrands: Boolean = false,
        ): List<Brand> =
            if (includeTestBrands || isTestAccount(username, password)) entries.toList()
            else listOf(HYUNDAI, KIA)

        fun hyundaiBaseUrl(region: Region): String = when (region) {
            Region.USA -> "https://api.telematics.hyundaiusa.com"
            Region.CANADA -> "https://mybluelink.ca"
            Region.EUROPE -> "https://prd.eu-ccapi.hyundai.com:8080"
            Region.AUSTRALIA -> "https://au-apigw.ccs.hyundai.com.au:8080"
            Region.CHINA -> "https://prd.cn-ccapi.hyundai.com"
            Region.INDIA -> "https://prd.in-ccapi.hyundai.connected-car.io:8080"
        }

        fun kiaBaseUrl(region: Region): String = when (region) {
            Region.USA -> "https://api.owners.kia.com"
            Region.CANADA -> "https://kiaconnect.ca"
            Region.EUROPE -> "https://prd.eu-ccapi.kia.com:8080"
            Region.AUSTRALIA -> "https://au-apigw.ccs.kia.com.au:8082"
            Region.CHINA -> "https://prd.cn-ccapi.kia.com"
            Region.INDIA -> "https://prd.in-ccapi.kia.connected-car.io:8080"
        }
    }
}

fun isTestAccount(username: String, password: String): Boolean =
    username.lowercase() == "testaccount@betterblue.com" && password == "betterblue"

@Serializable
enum class FuelType(val rawValue: String) {
    @SerialName("gas") GAS("gas"),
    @SerialName("electric") ELECTRIC("electric"),
    @SerialName("phev") PHEV("phev");

    val hasElectricCapability: Boolean get() = this != GAS

    companion object {
        fun fromNumber(number: Int): FuelType = when (number) {
            0 -> GAS
            1 -> PHEV
            2 -> ELECTRIC
            else -> GAS
        }
    }
}

@Serializable
enum class Region(val rawValue: String, val displayName: String) {
    @SerialName("US") USA("US", "USA"),
    @SerialName("CA") CANADA("CA", "Canada"),
    @SerialName("EU") EUROPE("EU", "Europe"),
    @SerialName("AU") AUSTRALIA("AU", "Australia"),
    @SerialName("CN") CHINA("CN", "China"),
    @SerialName("IN") INDIA("IN", "India");

    fun apiBaseUrl(brand: Brand): String = when (brand) {
        Brand.HYUNDAI -> Brand.hyundaiBaseUrl(this)
        Brand.KIA -> Brand.kiaBaseUrl(this)
        Brand.FAKE -> "https://fake.api.testing.com"
    }

    companion object {
        fun fromRawValue(raw: String): Region? = entries.firstOrNull { it.rawValue == raw }
    }
}
