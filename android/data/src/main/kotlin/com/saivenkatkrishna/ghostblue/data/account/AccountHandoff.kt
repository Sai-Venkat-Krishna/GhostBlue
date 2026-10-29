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
)

/** What applying the phone's accounts changed: new ones to sign in, and known ones whose credentials changed. */
data class HandoffChanges(val added: List<HandoffAccount>, val updated: List<BBAccount>)

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
    )

    fun encode(accounts: List<HandoffAccount>): ByteArray = json.encodeToString(serializer, accounts).encodeToByteArray()

    fun decode(data: ByteArray): List<HandoffAccount> = json.decodeFromString(serializer, data.decodeToString())

    /** Same login: username (any case), brand and region. The test account is the Fake brand, whichever brand it was added under. */
    fun matches(account: BBAccount, handoff: HandoffAccount): Boolean =
        account.username.equals(handoff.username, ignoreCase = true) &&
            effectiveBrand(account.username, account.password, account.brand) == effectiveBrand(handoff.username, handoff.password, handoff.brand) &&
            account.region == handoff.region

    private fun effectiveBrand(username: String, password: String, brand: String): Brand =
        AccountSetup.effectiveBrand(username, password, Brand.fromRawValue(brand) ?: Brand.HYUNDAI)

    /** One login, for remembering accounts across handoffs (see the watch's sign-out and failed sign-ins). */
    fun key(handoff: HandoffAccount): String =
        listOf(handoff.username.lowercase(), effectiveBrand(handoff.username, handoff.password, handoff.brand).rawValue, handoff.region).joinToString("|")

    fun key(account: BBAccount): String = key(from(account))

    /**
     * Stores [accounts]: new ones are returned for the caller to sign in
     * (with [AccountSetup]); known ones get the phone's current credentials,
     * and a new sign-in.
     */
    suspend fun apply(accounts: List<HandoffAccount>, db: GhostBlueDatabase): HandoffChanges = db.withTransaction {
        val stored = db.accounts().all()
        val added = mutableListOf<HandoffAccount>()
        val updated = mutableListOf<BBAccount>()
        for (handoff in accounts) {
            val existing = stored.firstOrNull { matches(it, handoff) }
            if (existing == null) {
                if (added.none { key(it) == key(handoff) }) added += handoff
            } else if (existing.password != handoff.password || existing.pin != handoff.pin) {
                existing.password = handoff.password
                existing.pin = handoff.pin
                existing.serializedAuthToken = null
                db.accounts().update(existing)
                updated += existing
            }
        }
        HandoffChanges(added, updated)
    }

    /**
     * Applies [accounts] and signs in the new ones; a known account whose
     * credentials changed drops its client, which holds the old password.
     * Returns the new ones that failed, with why.
     */
    suspend fun signInNew(
        accounts: List<HandoffAccount>,
        db: GhostBlueDatabase,
        controllers: AccountControllers,
    ): List<Pair<HandoffAccount, Exception>> {
        val changes = apply(accounts, db)
        changes.updated.forEach { controllers.forget(it.id) }
        val failures = mutableListOf<Pair<HandoffAccount, Exception>>()
        for (handoff in changes.added) {
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
