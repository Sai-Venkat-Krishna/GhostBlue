package com.saivenkatkrishna.ghostblue.data.account

import com.saivenkatkrishna.ghostblue.data.model.BBAccount

/** Where account problems the owner has to fix go. The app installs its notifier; without one they are dropped. */
fun interface AccountAlertSink {
    /** The service refused [account]'s password; GhostBlue stops logging in until it changes. */
    fun loginRejected(account: BBAccount)
}

object AccountAlerts {
    @Volatile
    var sink: AccountAlertSink = AccountAlertSink { }
}
