package com.saivenkatkrishna.ghostblue.kit.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class Vehicle(
    val vin: String,
    val regId: String,
    val model: String,
    @Serializable(with = UppercaseUUIDSerializer::class) val accountId: UUID,
    val fuelType: FuelType,
    val generation: Int,
    val odometer: Distance,
    val vehicleKey: String? = null,
) {
    val id: String get() = vin
}

@Serializable
data class AuthToken(
    val accessToken: String,
    val refreshToken: String,
    @Serializable(with = Iso8601InstantSerializer::class) val expiresAt: java.time.Instant,
) {
    /** Valid until five minutes before it expires. */
    val isValid: Boolean get() = java.time.Instant.now() < expiresAt.minusSeconds(300)

    override fun toString(): String = "AuthToken(expiresAt=$expiresAt)"
}
