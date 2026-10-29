package com.saivenkatkrishna.ghostblue.wear.handoff

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.saivenkatkrishna.ghostblue.data.account.AccountHandoff
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.wear.wearGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** The key under [AccountHandoff.PATH] holding the encoded accounts. */
const val HANDOFF_KEY = "accounts"

/**
 * Receives the phone's accounts over the Data Layer and signs in the new
 * ones on the watch. Runs when the phone writes them, even with the watch
 * app closed.
 */
class AccountListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val payloads = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == AccountHandoff.PATH }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getByteArray(HANDOFF_KEY) }
        events.release()
        // This runs on the listener's own thread, which may block while the watch signs in.
        for (payload in payloads) runBlocking { withTimeoutOrNull(90_000) { receive(this@AccountListenerService, payload) } }
    }

    companion object {
        suspend fun receive(context: Context, payload: ByteArray) {
            val accounts = runCatching { AccountHandoff.decode(payload) }.getOrNull() ?: return
            val failures = context.wearGraph.model.receive(accounts)
            BBLogger.info(BBLogCategory.APP, "Watch: received ${accounts.size} accounts from the phone, $failures failed to sign in")
        }

        /** On launch: whatever the phone last wrote, in case it changed while the watch app wasn't listening. */
        suspend fun syncFromPhone(context: Context) {
            runCatching {
                val items = Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*${AccountHandoff.PATH}")).await()
                val payloads = items.mapNotNull { DataMapItem.fromDataItem(it).dataMap.getByteArray(HANDOFF_KEY) }
                items.release()
                payloads.forEach { receive(context, it) }
            }
        }
    }
}
