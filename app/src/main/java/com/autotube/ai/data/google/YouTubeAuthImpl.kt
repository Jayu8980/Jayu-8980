package com.autotube.ai.data.google

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.domain.AuthResult
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.YouTubeAuth
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import com.google.android.gms.common.api.ApiException as GmsApiException

/**
 * Google authorization for YouTube uploads through the Identity [AuthorizationClient]
 * (scopes: youtube.upload, youtube.readonly, email). No client secret is involved: Google matches the
 * app by package name + signing SHA-1 against an Android OAuth client in Google Cloud.
 */
@Singleton
class YouTubeAuthImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val http: GoogleHttp,
) : YouTubeAuth {

    /** Last token handed out, kept in memory only so [signOut] can revoke it. */
    @Volatile
    private var lastAccessToken: String? = null

    private val authorizationClient: AuthorizationClient
        get() = Identity.getAuthorizationClient(context)

    override suspend fun authorize(): AuthResult {
        playServicesProblem()?.let { return AuthResult.Failed(it) }
        return try {
            val result = authorizationClient.authorize(buildRequest()).await()
            toAuthResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmsApiException) {
            Log.w(TAG, "YouTube authorization failed with status ${e.statusCode}")
            AuthResult.Failed(messageFor(e.statusCode))
        } catch (e: Exception) {
            Log.w(TAG, "YouTube authorization failed (${e.javaClass.simpleName})")
            AuthResult.Failed(GENERIC_FAILURE)
        }
    }

    override suspend fun handleConsentResult(data: Intent?): AuthResult {
        if (data == null) return AuthResult.Failed(CANCELLED_MESSAGE)
        return try {
            val result = authorizationClient.getAuthorizationResultFromIntent(data)
            toAuthResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmsApiException) {
            Log.w(TAG, "YouTube consent result failed with status ${e.statusCode}")
            AuthResult.Failed(messageFor(e.statusCode))
        } catch (e: Exception) {
            Log.w(TAG, "YouTube consent result could not be read (${e.javaClass.simpleName})")
            AuthResult.Failed(GENERIC_FAILURE)
        }
    }

    override suspend fun signOut(activity: Activity?) {
        settings.update { it.copy(googleAccountEmail = null) }
        // Use the cached token, or fetch one silently (no UI) if access is still granted, so the grant
        // is really revoked and the next sign-in asks for consent again.
        val token = lastAccessToken ?: silentTokenOrNull()
        lastAccessToken = null
        if (token == null) return
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear the cached Google token (${e.javaClass.simpleName})")
            }
            revoke(token)
        }
    }

    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map { Scope(it) })
            .build()

    @Suppress("DEPRECATION")
    private suspend fun toAuthResult(result: AuthorizationResult): AuthResult {
        if (result.hasResolution()) {
            val pending = result.pendingIntent
                ?: return AuthResult.Failed("Google could not show the permission screen. Please try again.")
            return AuthResult.NeedsConsent(pending)
        }
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            return AuthResult.Failed("Google did not return an access token. Sign in to YouTube again.")
        }
        lastAccessToken = token
        val stored = settings.current().googleAccountEmail
        val accountEmail = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
        val email = accountEmail?.takeIf { it.isNotBlank() }
            ?: stored?.takeIf { it.isNotBlank() }
            ?: fetchEmail(token)
        if (email != null && email != stored) {
            settings.update { it.copy(googleAccountEmail = email) }
        }
        return AuthResult.Authorized(token, email)
    }

    private suspend fun silentTokenOrNull(): String? {
        if (playServicesProblem() != null) return null
        return try {
            val result = withTimeoutOrNull(SILENT_TOKEN_TIMEOUT_MS) {
                authorizationClient.authorize(buildRequest()).await()
            } ?: return null
            if (result.hasResolution()) null else result.accessToken?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Reads the account email from the userinfo endpoint (needs the "email" scope). */
    private suspend fun fetchEmail(token: String): String? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(USERINFO_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(USERINFO_URL)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.client.newCall(request).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        null
                    } else {
                        AppJson.decodeFromString(UserInfo.serializer(), body).email?.takeIf { it.isNotBlank() }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Google account email could not be read (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun revoke(token: String) {
        try {
            val request = Request.Builder()
                .url(REVOKE_URL)
                .post(FormBody.Builder().add("token", token).build())
                .build()
            val completed = withTimeoutOrNull(REVOKE_TIMEOUT_MS) {
                http.client.newCall(request).await().use { response ->
                    // 400 means the token was already invalid or revoked, which is fine for a sign-out.
                    if (!response.isSuccessful && response.code != 400) {
                        Log.w(TAG, "Google token revocation returned HTTP ${response.code}")
                    }
                }
            }
            if (completed == null) Log.w(TAG, "Google token revocation timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Google token revocation failed (${e.javaClass.simpleName})")
        }
    }

    private fun playServicesProblem(): String? {
        val code = try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        } catch (e: Exception) {
            ConnectionResult.SERVICE_MISSING
        }
        return when (code) {
            ConnectionResult.SUCCESS -> null
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING ->
                "Google Play services needs an update. Update it from the Play Store, then try again."
            ConnectionResult.SERVICE_DISABLED ->
                "Google Play services is turned off. Turn it on in the phone's app settings, then try again."
            ConnectionResult.SERVICE_MISSING, ConnectionResult.SERVICE_INVALID ->
                "Google Play services is not available on this phone, so YouTube sign-in can't work here. " +
                    "Videos are still saved to your gallery for manual upload."
            else -> "Google Play services is not ready (code $code). Try again in a moment."
        }
    }

    private fun messageFor(statusCode: Int): String = when (statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google sign-in is not configured: add an Android OAuth client with this app's package name " +
                "(${context.packageName}) and SHA-1 in Google Cloud, and enable the YouTube Data API v3 (see README)."
        CommonStatusCodes.NETWORK_ERROR ->
            "Could not reach Google. Check your internet connection and try again."
        CommonStatusCodes.CANCELED, SIGN_IN_CANCELLED -> CANCELLED_MESSAGE
        CommonStatusCodes.SIGN_IN_REQUIRED ->
            "Choose a Google account to connect YouTube."
        CommonStatusCodes.INVALID_ACCOUNT ->
            "That Google account can't be used. Choose another account and try again."
        CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED ->
            "Google sign-in took too long. Please try again."
        CommonStatusCodes.INTERNAL_ERROR, SIGN_IN_FAILED ->
            "Google Play services had a problem signing you in. Please try again in a moment."
        CommonStatusCodes.API_NOT_CONNECTED ->
            "Google Play services is not available on this phone, so YouTube sign-in can't work here."
        else -> "Google sign-in failed (code $statusCode). Please try again."
    }

    @Serializable
    private data class UserInfo(val email: String? = null)

    private companion object {
        const val TAG = "AutoTube"
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/youtube.upload",
            "https://www.googleapis.com/auth/youtube.readonly",
            "email",
        )
        const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val USERINFO_TIMEOUT_MS = 10_000L
        const val SILENT_TOKEN_TIMEOUT_MS = 15_000L
        const val REVOKE_TIMEOUT_MS = 15_000L

        /** GoogleSignInStatusCodes values (kept as literals to avoid the deprecated class). */
        const val SIGN_IN_FAILED = 12500
        const val SIGN_IN_CANCELLED = 12501

        const val CANCELLED_MESSAGE = "Sign-in was cancelled. Tap Connect YouTube to try again."
        const val GENERIC_FAILURE = "Google sign-in failed. Check your connection and try again."
    }
}
