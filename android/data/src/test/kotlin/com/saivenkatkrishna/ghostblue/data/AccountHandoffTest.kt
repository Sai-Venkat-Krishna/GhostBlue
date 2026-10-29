package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.AccountControllers
import com.saivenkatkrishna.ghostblue.data.account.AccountHandoff
import com.saivenkatkrishna.ghostblue.data.account.AccountSetup
import com.saivenkatkrishna.ghostblue.data.account.HandoffAccount
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phone-to-watch account handoff and the shared account setup. */
@RunWith(RobolectricTestRunner::class)
class AccountHandoffTest {
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private val test = HandoffAccount("testaccount@betterblue.com", "betterblue", "", Brand.FAKE.rawValue, Region.USA.rawValue)

    @Test fun accountsRoundTrip() {
        val accounts = listOf(test, HandoffAccount("a@b.c", "pw", "1234", "hyundai", "US", "refresh"))
        assertEquals(accounts, AccountHandoff.decode(AccountHandoff.encode(accounts)))
    }

    @Test fun knownAccountsGetThePhonesCredentialsAndNewOnesAreReturned() = runTest {
        val stored = BBAccount("Driver@Example.com", "old", "1111", Brand.HYUNDAI, Region.USA).apply { serializedAuthToken = "token" }
        db.accounts().insert(stored)
        val added = AccountHandoff.apply(
            listOf(HandoffAccount("driver@example.com", "new", "2222", "hyundai", "US"), test),
            db,
        )
        assertEquals(listOf(test), added)
        val updated = db.accounts().byId(stored.id)!!
        assertEquals("new", updated.password)
        assertEquals("2222", updated.pin)
        // A new password means a new sign-in.
        assertNull(updated.serializedAuthToken)
    }

    @Test fun aNewRefreshTokenKeepsTheWatchsSession() = runTest {
        val stored = BBAccount("driver@example.com", "pw", "1111", Brand.HYUNDAI, Region.USA).apply {
            refreshToken = "old"
            serializedAuthToken = "token"
        }
        db.accounts().insert(stored)
        AccountHandoff.apply(listOf(HandoffAccount("driver@example.com", "pw", "1111", "hyundai", "US", "new")), db)
        val updated = db.accounts().byId(stored.id)!!
        assertEquals("new", updated.refreshToken)
        assertEquals("token", updated.serializedAuthToken)
    }

    @Test fun theTestAccountFromThePhoneSignsInWithTestCars() = runTest {
        val controllers = AccountControllers(db, backgroundScope)
        val failures = AccountHandoff.signInNew(listOf(test), db, controllers)
        assertTrue(failures.isEmpty())
        val account = db.accounts().all().single()
        assertEquals(Brand.FAKE, account.brandEnum)
        assertEquals(3, db.vehicles().ownedBy(account.id).size)
        // Sending the same account again changes nothing.
        assertTrue(AccountHandoff.signInNew(listOf(test), db, controllers).isEmpty())
        assertEquals(1, db.accounts().all().size)
    }

    @Test fun aFailedSignInLeavesNoAccount() = runTest {
        val controllers = AccountControllers(db, backgroundScope)
        val result = runCatching {
            AccountSetup.addAccount(db, controllers, "someone@example.com", "pw", "1234", Brand.KIA, Region.USA)
        }
        assertTrue(result.isFailure)
        assertTrue(db.accounts().all().isEmpty())
    }
}
