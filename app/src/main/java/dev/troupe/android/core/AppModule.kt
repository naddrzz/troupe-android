package dev.troupe.android.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.troupe.android.core.net.troupeHttpClient
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.servers.TinkTokenCipher
import okhttp3.OkHttpClient
import javax.inject.Singleton

private val Context.troupeDataStore: DataStore<Preferences> by preferencesDataStore(name = "troupe")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun httpClient(): OkHttpClient = troupeHttpClient()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.troupeDataStore

    @Provides @Singleton
    fun pairedServers(store: DataStore<Preferences>, @ApplicationContext context: Context) =
        PairedServerRepository(store, TinkTokenCipher(context))
}
