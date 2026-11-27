package com.mok.buylist.cloud

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import javax.crypto.Cipher

/** A per-operation Keystore authorization, not merely a UI gate. */
@RequiresApi(30)
internal fun authenticateCloudAdmin(
    activity: Activity,
    cipher: Cipher,
    cancellation: CancellationSignal,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit,
) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val manager = activity.getSystemService(BiometricManager::class.java)
    if (manager?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("Настройте системный PIN-код, пароль или отпечаток пальца.")
        return
    }
    BiometricPrompt.Builder(activity)
        .setTitle("Подтвердите доступ")
        .setSubtitle("Администрирование облачного хранилища")
        .setAllowedAuthenticators(authenticators)
        .setConfirmationRequired(true)
        .build()
        .authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized != null) onSuccess(authorized)
                    else onError("Не удалось подтвердить доступ к ключу.")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError("Подтверждение не выполнено: $errString")
                }
            })
}
