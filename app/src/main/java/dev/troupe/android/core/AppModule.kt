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
import dev.troupe.android.servers.TokenCipher
import dev.troupe.android.terminal.TerminalCursorStore
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

    /** One cipher for every secret at rest: two instances could each create the keyset on first use. */
    @Provides @Singleton
    fun tokenCipher(@ApplicationContext context: Context): TokenCipher = TinkTokenCipher(context)

    @Provides @Singleton
    fun pairedServers(store: DataStore<Preferences>, cipher: TokenCipher) = PairedServerRepository(store, cipher)

    @Provides @Singleton
    fun terminalCursors(store: DataStore<Preferences>, cipher: TokenCipher) = TerminalCursorStore(store, cipher)
}
