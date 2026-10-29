package com.saivenkatkrishna.ghostblue.data.account

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One account as the phone hands it to the watch: what signing in needs, nothing else. */
@Serializable
data class HandoffAccount(
    val username: String,
    val password: String,
    val pin: String,
    val brand: String,
    val region: String,
    val refreshToken: String = "",
)

/**
 * The phone's accounts sent to the watch over Wear OS's Data Layer, which
 * only the same app, signed with the same key, can read. Applying them adds
 * accounts the watch doesn't have and updates the credentials of those it
 * does; it never removes one (the watch signs out on its own).
 */
object AccountHandoff {
    /** The Data Layer path the phone writes and the watch listens to. */
    const val PATH = "/ghostblue/accounts"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HandoffAccount.serializer())

    fun from(account: BBAccount) = HandoffAccount(
        username = account.username,
        password = account.password,
        pin = account.pin,
        brand = account.brand,
        region = account.region,
        refreshToken = account.refreshToken,
    )

    fun encode(accounts: List<HandoffAccount>): ByteArray = json.encodeToString(serializer, accounts).encodeToByteArray()

    fun decode(data: ByteArray): List<HandoffAccount> = json.decodeFromString(serializer, data.decodeToString())

    /** Same login: username (any case), brand and region. */
    fun matches(account: BBAccount, handoff: HandoffAccount): Boolean =
        account.username.equals(handoff.username, ignoreCase = true) &&
            account.brand == handoff.brand && account.region == handoff.region

    /**
     * Stores [accounts]: new ones are returned for the caller to sign in
     * (with [AccountSetup]); known ones get the phone's current credentials.
     */
    suspend fun apply(accounts: List<HandoffAccount>, db: GhostBlueDatabase): List<HandoffAccount> = db.withTransaction {
        val stored = db.accounts().all()
        val added = mutableListOf<HandoffAccount>()
        for (handoff in accounts) {
            val existing = stored.firstOrNull { matches(it, handoff) }
            if (existing == null) {
                added += handoff
                continue
            }
            val credentialsChanged = existing.password != handoff.password || existing.pin != handoff.pin
            val tokenChanged = handoff.refreshToken.isNotEmpty() && existing.refreshToken != handoff.refreshToken
            if (credentialsChanged || tokenChanged) {
                existing.password = handoff.password
                existing.pin = handoff.pin
                if (tokenChanged) existing.refreshToken = handoff.refreshToken
                // New credentials mean a new sign-in; the watch's session outlives the phone's new refresh token.
                if (credentialsChanged) existing.serializedAuthToken = null
                db.accounts().update(existing)
            }
        }
        added
    }

    /** Signs in each new account from the phone; returns the ones that failed, with why. */
    suspend fun signInNew(
        accounts: List<HandoffAccount>,
        db: GhostBlueDatabase,
        controllers: AccountControllers,
    ): List<Pair<HandoffAccount, Exception>> {
        val failures = mutableListOf<Pair<HandoffAccount, Exception>>()
        for (handoff in apply(accounts, db)) {
            try {
                AccountSetup.addAccount(
                    db,
                    controllers,
                    handoff.username,
                    handoff.password,
                    handoff.pin,
                    Brand.fromRawValue(handoff.brand) ?: Brand.HYUNDAI,
                    Region.fromRawValue(handoff.region) ?: Region.USA,
                )
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += handoff to error
            }
        }
        return failures
    }
}
