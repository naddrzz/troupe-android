package dev.troupe.android.pairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.troupe.android.R
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.ViewOnlyRegistry
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
import java.util.UUID

/** Compose UI tests for V2 [ConnectServerScreen]: cleartext rejection in Paste & Manual, and QR sheet (#3). */
@RunWith(AndroidJUnit4::class)
class ConnectServerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun connectViewModel(): ConnectServerViewModel {
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "connect-screen-test-${UUID.randomUUID()}.preferences_pb")
        }
        val client = troupeHttpClient()
        return ConnectServerViewModel(
            ServerApi(client),
            TerminalSocketFactory(client, ClientIdentityStore(dataStore)),
            PairedServerRepository(dataStore, TinkTokenCipher(context)),
            ViewOnlyRegistry(),
        )
    }

    @Test fun manualSheetShowsCleartextErrorForPublicHttp() {
        val viewModel = connectViewModel()
        compose.setContent {
            TroupeTheme {
                ConnectServerScreen(
                    viewModel = viewModel,
                    canNavigateBack = false,
                    onBack = {},
                    onConnected = { _, _ -> },
                    onNeedsSetup = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.connect_manual)).performClick()
        compose.onNodeWithText(context.getString(R.string.pair_address_label)).performTextInput("http://8.8.8.8:8080")
        compose.onAllNodesWithText(context.getString(R.string.connect_submit)).onLast().performClick()

        compose.onNodeWithText("Use https:// for 8.8.8.8", substring = true).assertIsDisplayed()
    }

    @Test fun pasteFieldShowsCleartextErrorForPublicHttp() {
        val viewModel = connectViewModel()
        compose.setContent {
            TroupeTheme {
                ConnectServerScreen(
                    viewModel = viewModel,
                    canNavigateBack = false,
                    onBack = {},
                    onConnected = { _, _ -> },
                    onNeedsSetup = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.connect_link_label)).performTextInput("http://8.8.8.8:8080/#token=x")
        compose.onNode(hasText(context.getString(R.string.connect_submit)) and hasClickAction()).performClick()

        compose.onNodeWithText("Use https:// for 8.8.8.8", substring = true).assertIsDisplayed()
    }

    @Test fun scanQrSheetShowsCameraRationaleWhenPermissionNotGranted() {
        val viewModel = connectViewModel()
        compose.setContent {
            TroupeTheme {
                ConnectServerScreen(
                    viewModel = viewModel,
                    canNavigateBack = false,
                    onBack = {},
                    onConnected = { _, _ -> },
                    onNeedsSetup = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.connect_scan_qr)).performClick()

        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) {
            compose.onNodeWithText(context.getString(R.string.pair_scan_rationale)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.pair_scan_allow)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.pair_scan_use_paste)).assertIsDisplayed()
        }
    }
}
