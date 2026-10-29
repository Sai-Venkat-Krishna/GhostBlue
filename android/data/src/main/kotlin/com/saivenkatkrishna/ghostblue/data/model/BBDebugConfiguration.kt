package com.saivenkatkrishna.ghostblue.data.model

import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeDebugConfiguration
import com.saivenkatkrishna.ghostblue.kit.model.UppercaseUUIDSerializer
import java.util.UUID
import kotlinx.serialization.Serializable

/** Failures a test car simulates. Port of GhostBlue's `BBDebugConfiguration`. */
@Serializable
data class BBDebugConfiguration(
    @Serializable(with = UppercaseUUIDSerializer::class) val id: UUID = UUID.randomUUID(),
    val shouldFailCredentialValidation: Boolean = false,
    val shouldFailLogin: Boolean = false,
    val shouldFailVehicleFetch: Boolean = false,
    val shouldFailStatusFetch: Boolean = false,
    val shouldFailPinValidation: Boolean = false,
    val shouldFailLock: Boolean = false,
    val shouldFailUnlock: Boolean = false,
    val shouldFailStartClimate: Boolean = false,
    val shouldFailStopClimate: Boolean = false,
    val shouldFailStartCharge: Boolean = false,
    val shouldFailStopCharge: Boolean = false,
    val customCredentialErrorMessage: String = "Invalid credentials",
    val customPinErrorMessage: String = "Invalid PIN",
) {
    fun toFake() = FakeDebugConfiguration(
        shouldFailCredentialValidation = shouldFailCredentialValidation,
        shouldFailLogin = shouldFailLogin,
        shouldFailVehicleFetch = shouldFailVehicleFetch,
        shouldFailStatusFetch = shouldFailStatusFetch,
        shouldFailPinValidation = shouldFailPinValidation,
        shouldFailLock = shouldFailLock,
        shouldFailUnlock = shouldFailUnlock,
        shouldFailStartClimate = shouldFailStartClimate,
        shouldFailStopClimate = shouldFailStopClimate,
        shouldFailStartCharge = shouldFailStartCharge,
        shouldFailStopCharge = shouldFailStopCharge,
        customCredentialErrorMessage = customCredentialErrorMessage,
        customPinErrorMessage = customPinErrorMessage,
    )
}
