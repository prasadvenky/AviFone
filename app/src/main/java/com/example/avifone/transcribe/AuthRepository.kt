package com.example.avifone.transcribe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

data class SignedInUser(val name: String?, val email: String?)

/** Google Sign-In (Credential Manager) -> Firebase Auth. */
class AuthRepository(private val context: Context) {

    /** False when the app was built without google-services.json. */
    val isConfigured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val credentialManager = CredentialManager.create(context)
    private val _user = MutableStateFlow<SignedInUser?>(null)
    val user: StateFlow<SignedInUser?> = _user

    init {
        if (isConfigured) {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                _user.value = auth.currentUser?.let { SignedInUser(it.displayName, it.email) }
            }
        }
    }

    /** Shows the Google account picker. Returns an error message, or null on success or cancel. */
    suspend fun signIn(activity: Activity): String? {
        if (!isConfigured) return "Firebase isn't set up in this build"
        val clientId = webClientId() ?: return "Google sign-in isn't enabled for this Firebase project"
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
                .build()
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return "Unexpected sign-in response"
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            FirebaseAuth.getInstance()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await()
            null
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (_: NoCredentialException) {
            "No Google account on this device. Add one in Settings → Accounts."
        } catch (e: GetCredentialException) {
            "Sign-in failed: ${e.message}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Sign-in failed: ${e.message}"
        }
    }

    suspend fun signOut() {
        if (!isConfigured) return
        FirebaseAuth.getInstance().signOut()
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
    }

    // The google-services plugin generates this string from google-services.json, so it
    // only exists in builds that have that file; look it up by name instead of R.string.
    @SuppressLint("DiscouragedApi")
    private fun webClientId(): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }
}
