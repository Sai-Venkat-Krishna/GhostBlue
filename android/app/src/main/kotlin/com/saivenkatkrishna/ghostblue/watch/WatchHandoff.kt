package com.saivenkatkrishna.ghostblue.watch

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.saivenkatkrishna.ghostblue.data.account.AccountHandoff
import com.saivenkatkrishna.ghostblue.data.account.HandoffAccount
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Hands the phone's accounts to the watch app over Wear OS's Data Layer,
 * whenever they change: iOS's watch reads the phone's store; Android's
 * watch app signs in with what the phone sends. Only GhostBlue, signed with
 * the same key, can read the item. Without a watch or Play services, nothing
 * happens.
 */
object WatchHandoff {
    /** Must match the watch's `HANDOFF_KEY`. */
    const val KEY = "accounts"

    fun start(context: Context, db: GhostBlueDatabase, scope: CoroutineScope) {
        scope.launch {
            db.accounts().observeAll()
                .map { accounts -> accounts.map(AccountHandoff::from) }
                .distinctUntilChanged()
                .collect { send(context, it) }
        }
    }

    suspend fun send(context: Context, accounts: List<HandoffAccount>) {
        try {
            val request = PutDataMapRequest.create(AccountHandoff.PATH).apply {
                dataMap.putByteArray(KEY, AccountHandoff.encode(accounts))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BBLogger.debug(BBLogCategory.APP, "Watch handoff skipped: $error")
        }
    }
}
