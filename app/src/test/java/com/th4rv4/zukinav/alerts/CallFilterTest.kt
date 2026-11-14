package com.th4rv4.zukinav.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFilterTest {

    @Test fun namesAreContacts() {
        assertFalse(CallFilter.isUnknown("Mom"))
        assertFalse(CallFilter.isUnknown("Rahul Sharma"))
        assertFalse(CallFilter.isUnknown("Office 2"))
    }

    @Test fun numbersAndHiddenAreUnknown() {
        assertTrue(CallFilter.isUnknown("+91 98765 43210"))
        assertTrue(CallFilter.isUnknown("098765-43210"))
        assertTrue(CallFilter.isUnknown("(011) 2345 6789"))
        assertTrue(CallFilter.isUnknown("‪+91 98765 43210‬"))
        assertTrue(CallFilter.isUnknown("Unknown"))
        assertTrue(CallFilter.isUnknown("Private number"))
        assertTrue(CallFilter.isUnknown("No caller ID"))
        assertTrue(CallFilter.isUnknown(null))
        assertTrue(CallFilter.isUnknown(" "))
    }

    @Test fun repeatCallerRingsThrough() {
        val f = CallFilter()
        assertTrue(f.shouldDecline("+91 98765 43210", allowRepeat = true, now = 0))
        // Shown differently the second time, same number.
        assertFalse(f.shouldDecline("098765 43210", allowRepeat = true, now = 60_000))
        // A third call is declined again.
        assertTrue(f.shouldDecline("+91 98765 43210", allowRepeat = true, now = 120_000))
    }

    @Test fun repeatOffAlwaysDeclines() {
        val f = CallFilter()
        assertTrue(f.shouldDecline("9876543210", allowRepeat = false, now = 0))
        assertTrue(f.shouldDecline("9876543210", allowRepeat = false, now = 1_000))
    }

    @Test fun repeatWindowExpires() {
        val f = CallFilter()
        assertTrue(f.shouldDecline("9876543210", allowRepeat = true, now = 0))
        assertTrue(f.shouldDecline("9876543210", allowRepeat = true, now = 6 * 60_000))
    }

    @Test fun contactsNeverDeclined() {
        assertFalse(CallFilter().shouldDecline("Mom", allowRepeat = false, now = 0))
    }

    @Test fun recentlyDeclinedForMissedNotice() {
        val f = CallFilter()
        f.shouldDecline("+919876543210", allowRepeat = true, now = 0)
        assertTrue(f.recentlyDeclined("98765 43210", 30_000))
        assertFalse(f.recentlyDeclined("98765 43210", 90_000))
        assertFalse(f.recentlyDeclined("Mom", 1_000))
    }
}
