package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal class HeaderWeatherRefresh<T> {
    var user: Int? = null
        private set
    var value: T? = null
        private set
    private var lastAttempt: Long? = null
    private var generation = 0L
    private var pending: Long? = null

    fun selectUser(current: Int?) {
        val selected = current?.takeIf { it >= 0 }
        if (selected == user) return
        clear()
        user = selected
    }

    fun begin(now: Long): Long? {
        if (user == null || now < 0) return null
        val previous = lastAttempt
        if (previous != null && now >= previous && now - previous < HeaderWeatherPolicy.REFRESH_MS) return null
        lastAttempt = now
        return (++generation).also { pending = it }
    }

    fun complete(token: Long, currentUser: Int, result: T?): Boolean {
        if (!accepts(token, currentUser)) return false
        pending = null
        value = result
        return true
    }

    fun accepts(token: Long, currentUser: Int) = pending == token && user == currentUser

    fun suspendRequest() {
        pending = null
    }

    fun clear() {
        suspendRequest()
        user = null
        value = null
        lastAttempt = null
    }
}
