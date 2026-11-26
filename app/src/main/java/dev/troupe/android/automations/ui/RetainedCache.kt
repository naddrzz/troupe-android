package dev.troupe.android.automations.ui

/**
 * One value per key, built on first use. A key is retained while some screen shows it ([retain] / [release]) and is
 * then never evicted. Released keys stay idle, at most [maxIdle] of them: beyond that the least recently released is
 * evicted, [dispose]d and built anew on its next use. [get] takes a value off the idle list until it is released
 * again, so one being composed is not evicted before its screen retains it.
 */
internal class RetainedCache<K : Any, V : Any>(
    private val maxIdle: Int,
    private val create: (K) -> V,
    private val dispose: (V) -> Unit,
) {
    // Guarded by this.
    private val values = HashMap<K, V>()
    private val holds = HashMap<K, Int>()
    /** Released keys, least recently released first. */
    private val idle = LinkedHashSet<K>()

    fun get(key: K): V = synchronized(this) {
        idle.remove(key)
        values.getOrPut(key) { create(key) }
    }

    fun retain(key: K) {
        synchronized(this) {
            idle.remove(key)
            holds[key] = (holds[key] ?: 0) + 1
        }
    }

    fun release(key: K) {
        val evicted = synchronized(this) {
            val left = (holds[key] ?: return) - 1
            if (left > 0) {
                holds[key] = left
                return
            }
            holds.remove(key)
            if (key in values) idle += key
            buildList {
                while (idle.size > maxIdle) {
                    val oldest = idle.first()
                    idle.remove(oldest)
                    values.remove(oldest)?.let(::add)
                }
            }
        }
        evicted.forEach(dispose)
    }

    /** Disposes every value, retained or not. */
    fun clear() {
        val all = synchronized(this) {
            val all = values.values.toList()
            values.clear()
            holds.clear()
            idle.clear()
            all
        }
        all.forEach(dispose)
    }
}
