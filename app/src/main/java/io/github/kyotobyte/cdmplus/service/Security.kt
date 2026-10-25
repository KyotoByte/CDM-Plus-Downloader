package io.github.kyotobyte.cdmplus.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256 key kept in the Android Keystore for the password vault. */
object KeystoreKey {
    private const val ALIAS = "cdmplus_vault_v1"
    fun get(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
}

/** Fingerprint / face / screen-lock check before passwords are shown or filled. Unlocked for 5 minutes. */
object BioLock {
    @Volatile private var unlockedUntil = 0L
    const val REQ_CONFIRM = 4711
    private var pending: ((Boolean) -> Unit)? = null

    fun isUnlocked() = System.currentTimeMillis() < unlockedUntil

    fun unlock(activity: Activity, reason: String, done: (Boolean) -> Unit) {
        if (isUnlocked()) { done(true); return }
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) { unlockedUntil = System.currentTimeMillis() + 300_000; done(true); return } // no screen lock set
        if (Build.VERSION.SDK_INT >= 28) {
            val b = BiometricPrompt.Builder(activity).setTitle("Unlock passwords").setSubtitle(reason)
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else if (Build.VERSION.SDK_INT >= 29) @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else b.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> done(false) }
            b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { unlockedUntil = System.currentTimeMillis() + 300_000; done(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done(false) }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Unlock passwords", reason) ?: run { done(true); return }
            pending = done
            @Suppress("DEPRECATION") activity.startActivityForResult(i, REQ_CONFIRM)
        }
    }

    /** Called from the activity's onActivityResult (Android 7-8). */
    fun onConfirmResult(ok: Boolean) { if (ok) unlockedUntil = System.currentTimeMillis() + 300_000; pending?.invoke(ok); pending = null }
}
