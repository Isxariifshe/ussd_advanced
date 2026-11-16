package com.phan_tech.ussd_advanced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UssdPhoneAccountTest {
    @Test fun singleSimPositionOneSelectsOnlyAccount() {
        assertEquals("sim", selectUssdPhoneAccount(listOf("sim"), 1))
    }

    @Test fun dualSimPositionsSelectRequestedAccount() {
        val accounts = listOf("first", "second")
        assertEquals("first", selectUssdPhoneAccount(accounts, 1))
        assertEquals("second", selectUssdPhoneAccount(accounts, 2))
    }

    @Test fun staleDualSimSelectionDoesNotDialRemainingAccount() {
        assertThrows(IllegalArgumentException::class.java) {
            selectUssdPhoneAccount(listOf("remaining"), 2)
        }
    }

    @Test fun removedSimFailsBeforeDialing() {
        assertThrows(IllegalArgumentException::class.java) {
            selectUssdPhoneAccount(emptyList<String>(), 1)
        }
    }

    @Test fun invalidPositionsAndSubscriptionIdsAreRejected() {
        for (position in listOf(Int.MIN_VALUE, -1, 0, 3, 42, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                selectUssdPhoneAccount(listOf("first", "second"), position)
            }
        }
    }
}
