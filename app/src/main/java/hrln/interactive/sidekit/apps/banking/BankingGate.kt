package hrln.interactive.sidekit.apps.banking

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Asks for the phone's screen lock (fingerprint/face if enrolled, else PIN/pattern/password)
 * with the platform [BiometricPrompt] (API 30+), so no androidx.biometric dependency.
 */
class BankingGate(private val context: Context) {
    /** False when the phone has no screen lock, so there's nothing to ask for. */
    val deviceSecure: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Shows the prompt. Returns a signal that cancels it (e.g. when the screen goes away). */
    fun authenticate(onSuccess: () -> Unit, onFailure: (String?) -> Unit): CancellationSignal {
        val cancel = CancellationSignal()
        val prompt = BiometricPrompt.Builder(context)
            .setTitle("Unlock Banking")
            .setSubtitle("Your banking data is encrypted on this phone")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            cancel,
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                    onFailure(if (cancelled) null else errString.toString())
                }
            },
        )
        return cancel
    }
}

/**
 * Runs [onStop] when the whole app stops (screen off, Home, another app), not when Banking moves
 * between its own screens (each of which stops its own back-stack entry).
 */
@Composable
fun OnAppStop(onStop: () -> Unit) {
    val owner = LocalActivity.current as? LifecycleOwner ?: return
    val latest = rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest.value() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
