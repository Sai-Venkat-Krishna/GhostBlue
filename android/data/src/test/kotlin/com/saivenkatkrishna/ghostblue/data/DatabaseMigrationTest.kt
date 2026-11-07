package com.saivenkatkrishna.ghostblue.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Databases written by earlier versions open in this one with their rows
 * intact. Each old version is built from its exported schema in `schemas/`,
 * as Room's MigrationTestHelper does.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun version1AccountsKeepTheirCredentials() = runTest {
        val name = "migration-v1.db"
        context.deleteDatabase(name)
        createFromSchema(name, version = 1).use { db ->
            db.execSQL(
                "INSERT INTO accounts (id, username, password, refreshToken, pin, brand, region, dateCreated) " +
                    "VALUES ('6F9619FF-8B86-D011-B42D-00CF4FC964FF', 'driver@example.com', 'secret', '', '1234', 'hyundai', 'US', 0)",
            )
        }

        val migrated = Room.databaseBuilder(context, GhostBlueDatabase::class.java, name).build()
        try {
            val account = migrated.accounts().all().single()
            assertEquals("driver@example.com", account.username)
            assertEquals("secret", account.password)
            assertNull(account.rejectedCredentials)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /** An empty database exactly as [version] of the app created it. */
    private fun createFromSchema(name: String, version: Int): SQLiteDatabase {
        val schema = Json.parseToJsonElement(
            File("schemas/${GhostBlueDatabase::class.qualifiedName}/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            fun sql(statement: String) = db.execSQL(statement.replace("\${TABLE_NAME}", table))
            sql(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray?.forEach { sql(it.jsonObject.getValue("createSql").jsonPrimitive.content) }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }
}
