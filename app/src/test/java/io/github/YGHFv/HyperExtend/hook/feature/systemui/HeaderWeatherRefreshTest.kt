package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class HeaderWeatherRefreshTest {
    private val interval = HeaderWeatherPolicy.REFRESH_MS

    @Test fun reopeningRetainsDataAndTheOriginalRefreshDeadline() {
        val refresh = HeaderWeatherRefresh<String>()
        refresh.selectUser(0)
        val token = refresh.begin(100)!!
        assertTrue(refresh.complete(token, 0, "weather"))
        repeat(5) {
            refresh.suspendRequest()
            refresh.selectUser(0)
            assertEquals("weather", refresh.value)
            assertNull(refresh.begin(200 + it.toLong()))
        }
        assertNull(refresh.begin(100 + interval - 1))
        assertNotNull(refresh.begin(100 + interval))
    }

    @Test fun hidingCancelsPendingDeliveryWithoutResettingTheCooldown() {
        val refresh = HeaderWeatherRefresh<String>()
        refresh.selectUser(0)
        val token = refresh.begin(0)!!
        refresh.suspendRequest()
        assertFalse(refresh.accepts(token, 0))
        assertFalse(refresh.complete(token, 0, "late"))
        assertNull(refresh.value)
        assertNull(refresh.begin(interval - 1))
        val replacement = refresh.begin(interval)!!
        assertNotEquals(token, replacement)
        assertFalse(refresh.complete(token, 0, "late"))
        assertTrue(refresh.complete(replacement, 0, "new"))
    }

    @Test fun userSwitchClearsDataAndAllowsAnIndependentImmediateQuery() {
        val refresh = HeaderWeatherRefresh<String>()
        refresh.selectUser(0)
        val ownerToken = refresh.begin(0)!!
        refresh.complete(ownerToken, 0, "owner")
        refresh.selectUser(10)
        assertNull(refresh.value)
        val secondaryToken = refresh.begin(1)!!
        assertFalse(refresh.complete(ownerToken, 0, "owner-late"))
        assertFalse(refresh.complete(secondaryToken, 0, "wrong-user"))
        assertTrue(refresh.accepts(secondaryToken, 10))
        assertTrue(refresh.complete(secondaryToken, 10, "secondary"))
        refresh.selectUser(0)
        assertNull(refresh.value)
        assertNotNull(refresh.begin(2))
    }

    @Test fun disableSafetyAndUnknownUserDiscardAllCachedData() {
        val refresh = HeaderWeatherRefresh<String>()
        assertNull(refresh.begin(0))
        refresh.selectUser(-1)
        assertNull(refresh.begin(0))
        refresh.selectUser(0)
        val token = refresh.begin(0)!!
        refresh.complete(token, 0, "weather")
        refresh.clear()
        assertNull(refresh.value)
        assertNull(refresh.user)
        assertFalse(refresh.accepts(token, 0))
        refresh.selectUser(0)
        assertNotNull(refresh.begin(1))
        refresh.selectUser(null)
        assertNull(refresh.value)
        assertNull(refresh.begin(2))
    }

    @Test fun failedEmptyAndNeverDeliveredQueriesAreRateLimitedButCanRecover() {
        val refresh = HeaderWeatherRefresh<String>()
        refresh.selectUser(0)
        val first = refresh.begin(0)!!
        assertTrue(refresh.complete(first, 0, null))
        assertNull(refresh.begin(1))
        val lost = refresh.begin(interval)!!
        assertNull(refresh.begin(interval + 1))
        val recovered = refresh.begin(2 * interval)!!
        assertFalse(refresh.complete(lost, 0, "late"))
        assertTrue(refresh.complete(recovered, 0, "recovered"))
        assertFalse(refresh.complete(recovered, 0, "duplicate"))
        assertEquals("recovered", refresh.value)
    }

    @Test fun cancelledContextSetupRetainsCooldownAndMonotonicTimeBounds() {
        val refresh = HeaderWeatherRefresh<String>()
        refresh.selectUser(0)
        assertNull(refresh.begin(-1))
        val token = refresh.begin(100)!!
        refresh.suspendRequest()
        assertNull(refresh.begin(101))
        assertFalse(refresh.complete(token, 0, "late"))
        assertNotNull(refresh.begin(99))
    }
}
