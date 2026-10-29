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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        val accounts = listOf(test, HandoffAccount("a@b.c", "pw", "1234", "hyundai", "US"))
        assertEquals(accounts, AccountHandoff.decode(AccountHandoff.encode(accounts)))
    }

    @Test fun knownAccountsGetThePhonesCredentialsAndNewOnesAreReturned() = runTest {
        val stored = BBAccount("Driver@Example.com", "old", "1111", Brand.HYUNDAI, Region.USA).apply { serializedAuthToken = "token" }
        db.accounts().insert(stored)
        val changes = AccountHandoff.apply(
            listOf(HandoffAccount("driver@example.com", "new", "2222", "hyundai", "US"), test),
            db,
        )
        assertEquals(listOf(test), changes.added)
        assertEquals(listOf(stored.id), changes.updated.map { it.id })
        val updated = db.accounts().byId(stored.id)!!
        assertEquals("new", updated.password)
        assertEquals("2222", updated.pin)
        // A new password means a new sign-in.
        assertNull(updated.serializedAuthToken)
    }

    @Test fun newCredentialsReplaceTheClientHoldingTheOldPassword() = runTest {
        val stored = BBAccount("driver@example.com", "old", "1111", Brand.HYUNDAI, Region.USA)
        db.accounts().insert(stored)
        val controllers = AccountControllers(db, backgroundScope)
        val before = controllers.controller(stored.id)
        AccountHandoff.signInNew(listOf(HandoffAccount("driver@example.com", "new", "1111", "hyundai", "US")), db, controllers)
        assertNotSame(before, controllers.controller(stored.id))
        // Unchanged credentials keep it.
        val current = controllers.controller(stored.id)
        AccountHandoff.signInNew(listOf(HandoffAccount("driver@example.com", "new", "1111", "hyundai", "US")), db, controllers)
        assertSame(current, controllers.controller(stored.id))
    }

    @Test fun theTestAccountIsTheSameWhicheverBrandItWasAddedUnder() = runTest {
        val controllers = AccountControllers(db, backgroundScope)
        assertTrue(AccountHandoff.signInNew(listOf(test), db, controllers).isEmpty())
        // A phone that added it under Hyundai (as an iOS backup keeps it) sends "hyundai".
        val asHyundai = test.copy(brand = Brand.HYUNDAI.rawValue)
        assertTrue(AccountHandoff.apply(listOf(asHyundai), db).added.isEmpty())
        assertEquals(AccountHandoff.key(test), AccountHandoff.key(asHyundai))
        // Sent twice in one payload, it's still one account.
        assertEquals(1, AccountHandoff.apply(listOf(test.copy(username = "other@example.com"), test.copy(username = "Other@example.com")), db).added.size)
    }

    @Test fun anEmptyListOfTestCarsAddsNone() = runTest {
        val controllers = AccountControllers(db, backgroundScope)
        val account = AccountSetup.addAccount(db, controllers, test.username, test.password, "", Brand.FAKE, Region.USA, testCars = emptyList())
        assertTrue(db.vehicles().ownedBy(account.id).isEmpty())
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
