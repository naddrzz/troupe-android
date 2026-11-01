package dev.troupe.android.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/** Emits each time a network becomes the default one: Wi-Fi back on, a switch to mobile data. */
interface NetworkMonitor {
    val available: Flow<Unit>
}

class AndroidNetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) : NetworkMonitor {
    override val available: Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {
    @Binds fun networkMonitor(monitor: AndroidNetworkMonitor): NetworkMonitor
}
