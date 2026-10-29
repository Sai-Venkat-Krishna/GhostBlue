package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import java.time.Instant
import java.util.UUID

/**
 * A signed-in BlueLink account. Port of GhostBlue's SwiftData `BBAccount`;
 * the API client and token cache it holds at run time live in
 * `AccountController`.
 */
@Entity(tableName = "accounts")
class BBAccount() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var username: String = ""
    var password: String = ""

    /**
     * Long-lived refresh token for brands that re-authenticate with it
     * (Hyundai and Kia Europe). Stored as "" rather than null, as on iOS.
     */
    var refreshToken: String = ""
    var pin: String = ""

    /** [Brand] raw value. */
    var brand: String = ""

    /** [Region] raw value. */
    var region: String = ""
    var dateCreated: Instant = Instant.now()
    var rememberMeToken: String? = null

    /** The login token as Swift's `JSONEncoder` writes it, so backups carry it between the apps. */
    var serializedAuthToken: String? = null
    var deviceId: String? = null

    /** Last successful vehicle-list fetch; null when never fetched. */
    var lastVehiclesFetch: Instant? = null

    @Ignore
    constructor(
        username: String,
        password: String,
        pin: String,
        brand: Brand,
        region: Region,
        refreshToken: String = "",
    ) : this() {
        this.username = username
        this.password = password
        this.refreshToken = refreshToken
        this.pin = pin
        this.brand = brand.rawValue
        this.region = region.rawValue
    }

    /** Unknown brands read as Hyundai, as on iOS. */
    val brandEnum: Brand get() = Brand.fromRawValue(brand) ?: Brand.HYUNDAI

    val regionEnum: Region get() = Region.fromRawValue(region) ?: Region.USA

    /** Hyundai USA has a trip endpoint; the test car makes up a week of trips. */
    val supportsEVTripDetails: Boolean
        get() = brandEnum == Brand.FAKE || (brandEnum == Brand.HYUNDAI && regionEnum == Region.USA)

    // Never print credentials.
    override fun toString(): String = "BBAccount(id=$id, brand=$brand, region=$region)"
}
