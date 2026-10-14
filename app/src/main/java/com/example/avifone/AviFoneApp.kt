package com.example.avifone

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

class AviFoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase AI Logic rejects requests without a valid App Check token.
        // The app isn't distributed through Google Play, so Play Integrity can't attest
        // it; instead each install logs a debug secret ("Enter this debug secret into
        // the allow list...") that must be registered in Firebase console > Security >
        // App Check > Apps > Manage debug tokens. Replace with Play Integrity before
        // any wider distribution.
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        }
    }
}
