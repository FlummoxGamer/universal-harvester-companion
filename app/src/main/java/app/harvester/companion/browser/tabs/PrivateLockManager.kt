package app.harvester.companion.browser.tabs

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import app.harvester.companion.SettingsPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional Biometric / Device Credential lock for Private Tabs.
 * Uses native Android framework BiometricPrompt (minSdk 29+) with zero extra library dependencies.
 */
object PrivateLockManager {
    private val _isLockedFlow = MutableStateFlow(false)
    val isLockedFlow: StateFlow<Boolean> = _isLockedFlow.asStateFlow()

    fun isLockEnabled(context: Context): Boolean {
        return SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_PRIVATE_LOCK_ENABLED, false)
    }

    fun isDeviceAuthAvailable(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isDeviceSecure == true) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            val res = bm?.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            return res == BiometricManager.BIOMETRIC_SUCCESS
        }
        return false
    }

    fun onAppBackgrounded(context: Context, hasPrivateTabs: Boolean) {
        if (hasPrivateTabs && isLockEnabled(context) && isDeviceAuthAvailable(context)) {
            _isLockedFlow.value = true
        }
    }

    fun onLockSettingChanged(enabled: Boolean) {
        if (!enabled) {
            _isLockedFlow.value = false
        }
    }

    fun onAllPrivateClosed() {
        _isLockedFlow.value = false
    }

    fun unlockNow() {
        _isLockedFlow.value = false
    }

    fun promptUnlock(
        context: Context,
        onUnlocked: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!isDeviceAuthAvailable(context)) {
            _isLockedFlow.value = false
            onUnlocked()
            return
        }
        try {
            val executor = ContextCompat.getMainExecutor(context)
            val builder = BiometricPrompt.Builder(context)
                .setTitle("Unlock Private Tabs")
                .setSubtitle("Verify your identity to view private sessions")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }

            val prompt = builder.build()
            val signal = CancellationSignal()
            prompt.authenticate(
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        _isLockedFlow.value = false
                        onUnlocked()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onError(errString?.toString() ?: "Authentication failed")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            onError(e.message ?: "Unable to start authentication")
        }
    }
}
