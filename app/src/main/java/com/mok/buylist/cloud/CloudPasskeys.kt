package com.mok.buylist.cloud

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import com.mok.buylist.BuildConfig
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal enum class PasskeyStage { IDLE, PREPARING, CONFIRMING, VERIFYING }
internal data class CloudPasskey(val id: String, val name: String, val createdAt: String, val lastUsedAt: String?)
internal class PasskeyApiException(val code: String, val status: Int): Exception("Passkey request failed: $status")

/**
 * Native Credential Manager + official Supabase Auth WebAuthn endpoints.
 * Challenges and verification belong to Auth; never save passwords, private keys,
 * credential responses or bearer tokens in application storage or logs.
 */
internal class CloudPasskeys(
    private val token: suspend () -> String?,
    private val user: () -> String?,
    private val acceptSession: suspend (UserSession) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun request(method: String, route: String, authenticated: Boolean, body: JsonObject? = null): JsonElement {
        val bearer = if (authenticated) requireNotNull(token()) else null
        return withContext(Dispatchers.IO) {
            check(BuildConfig.SUPABASE_URL.startsWith("https://"))
            val connection = URL(BuildConfig.SUPABASE_URL + "/auth/v1/passkeys/" + route).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
                connection.setRequestProperty("Accept", "application/json")
                if (bearer != null) connection.setRequestProperty("Authorization","Bearer $bearer")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type","application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val success = status in 200..299
                val raw = (if(success) connection.inputStream else connection.errorStream)?.use { stream ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while(true) {
                        val count = stream.read(buffer)
                        if(count < 0) break
                        check(bytes.size() + count <= 1_048_576)
                        bytes.write(buffer,0,count)
                    }
                    bytes.toString(Charsets.UTF_8.name())
                }.orEmpty()
                if (!success) {
                    val code = runCatching { json.parseToJsonElement(raw).jsonObject.string("error_code") }.getOrNull().orEmpty()
                    throw PasskeyApiException(code,status)
                }
                if(raw.isBlank()) JsonNull else json.parseToJsonElement(raw)
            } finally { connection.disconnect() }
        }
    }

    private fun verifyBody(options: JsonObject, credential: String) = buildJsonObject {
        put("challenge_id", requireNotNull(options.string("challenge_id")))
        put("credential",json.parseToJsonElement(credential).jsonObject)
    }

    suspend fun signIn(activity: Activity, stage: (PasskeyStage) -> Unit) {
        check(user() == null)
        stage(PasskeyStage.PREPARING)
        val challenge = request("POST","authentication/options",false,buildJsonObject{}).jsonObject
        val options = challenge.getValue("options").jsonObject
        stage(PasskeyStage.CONFIRMING)
        val response = CredentialManager.create(activity).getCredential(
            context=activity, request=GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.toString())))
        ).credential as? PublicKeyCredential ?: error("Unexpected credential type")
        stage(PasskeyStage.VERIFYING)
        val result = request("POST","authentication/verify",false,verifyBody(challenge,response.authenticationResponseJson))
        val session = json.decodeFromJsonElement(UserSession.serializer(), result)
        check(session.user != null && session.refreshToken.isNotBlank() && session.accessToken.isNotBlank())
        acceptSession(session)
    }

    suspend fun register(activity: Activity, stage: (PasskeyStage) -> Unit) {
        val uid = requireNotNull(user())
        stage(PasskeyStage.PREPARING)
        val challenge = request("POST","registration/options",true,buildJsonObject{}).jsonObject
        stage(PasskeyStage.CONFIRMING)
        val response = CredentialManager.create(activity).createCredential(
            context=activity, request=CreatePublicKeyCredentialRequest(challenge.getValue("options").jsonObject.toString())
        ) as? CreatePublicKeyCredentialResponse ?: error("Unexpected credential type")
        check(user() == uid)
        stage(PasskeyStage.VERIFYING)
        request("POST","registration/verify",true,verifyBody(challenge,response.registrationResponseJson))
        check(user() == uid)
    }

    suspend fun list(): List<CloudPasskey> = request("GET","",true).jsonArray.map {
        val row=it.jsonObject
        CloudPasskey(UUID.fromString(requireNotNull(row.string("id"))).toString(),
            row.string("friendly_name").orEmpty(),row.string("created_at").orEmpty(),row.string("last_used_at"))
    }

    suspend fun remove(id: String) {
        request("DELETE",UUID.fromString(id).toString(),true)
    }

    suspend fun clearCredentialState(activity: Activity) {
        // Tell the system credential provider the sign-in session ended, but do not remove saved passkeys.
        CredentialManager.create(activity).clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
    }
}

internal fun passkeyError(error: Throwable): String = when(error) {
    is androidx.credentials.exceptions.GetCredentialCancellationException,
    is androidx.credentials.exceptions.CreateCredentialCancellationException -> ""
    is androidx.credentials.exceptions.NoCredentialException -> "Ключ доступа не найден. Войдите с паролем."
    is PasskeyApiException -> when {
        error.code == "passkey_disabled" || error.status == 404 -> "Ключи доступа пока недоступны на сервере"
        error.status == 429 -> "Слишком много попыток. Попробуйте позднее."
        error.status == 401 || error.status == 403 -> "Не удалось подтвердить доступ. Войдите снова."
        else -> "Не удалось подтвердить ключ доступа"
    }
    is java.io.IOException -> "Нет соединения. Попробуйте снова."
    else -> "Не удалось использовать ключ доступа"
}
