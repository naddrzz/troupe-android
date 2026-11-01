package dev.troupe.android.pairing

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.troupe.android.R
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.troupeHttpClient
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.servers.TinkTokenCipher
import dev.troupe.android.terminal.net.ClientIdentityStore
import dev.troupe.android.terminal.net.TerminalSocketFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PairScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The real ViewModel; a refused address never reaches the network or storage. */
    private fun pairViewModel(): PairViewModel {
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "pair-screen-test.preferences_pb")
        }
        val client = troupeHttpClient()
        return PairViewModel(
            ServerApi(client),
            TerminalSocketFactory(client, ClientIdentityStore(dataStore)),
            PairedServerRepository(dataStore, TinkTokenCipher(context)),
        )
    }

    @Test fun manualEntryShowsCleartextError() {
        val viewModel = pairViewModel()
        compose.setContent { TroupeTheme { PairScreen(onPaired = {}, onBack = {}, viewModel = viewModel) } }

        compose.onNodeWithText(context.getString(R.string.pair_tab_manual)).performClick()
        compose.onNodeWithText(context.getString(R.string.pair_address_label)).performTextInput("http://8.8.8.8:8080")
        compose.onNodeWithText(context.getString(R.string.pair_token_label)).performTextInput("x")
        compose.onNode(hasText(context.getString(R.string.pair_submit)) and hasClickAction()).performClick()

        compose.onNodeWithText("Use https:// for 8.8.8.8", substring = true).assertIsDisplayed()
    }
}
