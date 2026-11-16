package com.phan_tech.ussd_advanced

// sendUssd's legacy subscriptionId argument is a one-based account position.
// Android subscription IDs are opaque IDs and must not be passed to this helper.
internal fun <T> selectUssdPhoneAccount(accounts: List<T>, simPosition: Int): T {
    require(simPosition > 0) { "SIM position must be positive" }
    return accounts.getOrNull(simPosition - 1)
        ?: throw IllegalArgumentException("Selected SIM is unavailable")
}
