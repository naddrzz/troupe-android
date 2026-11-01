package dev.troupe.android.lock

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.troupe.android.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class LockAvailability { Available, NoScreenLock, Unsupported }

/** Proves the device owner is present. The seam the lock is tested through: BiometricPrompt can't run on the JVM. */
interface Authenticator {
    fun availability(): LockAvailability

    /** `true` only after a successful prompt; cancel and errors are `false`. */
    suspend fun authenticate(): Boolean
}

class BiometricAuthenticator(private val activity: FragmentActivity) : Authenticator {
    // BIOMETRIC_STRONG or DEVICE_CREDENTIAL isn't supported before API 30; BIOMETRIC_WEAK or DEVICE_CREDENTIAL is.
    private val allowed =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun availability(): LockAvailability = when (BiometricManager.from(activity).canAuthenticate(allowed)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockAvailability.NoScreenLock
        else -> LockAvailability.Unsupported
    }

    override suspend fun authenticate(): Boolean {
        // A recreated activity (theme or locale change) loses the prompt's callback; answer `false` so a new one can open.
        var destroyed: LifecycleEventObserver? = null
        try {
            return suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(false)
                        }
                        // A rejected fingerprint keeps the prompt open, so onAuthenticationFailed needs no handling.
                    },
                )
                destroyed = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY && cont.isActive) cont.resume(false)
                }.also(activity.lifecycle::addObserver)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(activity.getString(R.string.lock_prompt_title))
                        .setAllowedAuthenticators(allowed)
                        .build(),
                )
            }
        } finally {
            destroyed?.let(activity.lifecycle::removeObserver)
        }
    }
}