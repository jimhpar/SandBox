package com.blackbox.sandbox.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * BridgeWipeActivity
 * Dedicated zero-extras activity enabled ONLY inside the Managed Work Profile.
 * Launching it via LauncherApps.startMainActivity(...) requires no Intent extras
 * and immediately executes DevicePolicyManager.wipeData(0) to delete the Work Profile.
 */
class BridgeWipeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        Log.w("SandboxBridgeWipe", "BridgeWipeActivity launched; isProfileOwner=${dpm?.isProfileOwnerApp(packageName)}")

        if (dpm != null && dpm.isProfileOwnerApp(packageName)) {
            Toast.makeText(this, "Removing Sandbox Dual Space...", Toast.LENGTH_SHORT).show()
            try {
                dpm.wipeData(0)
            } catch (e: Exception) {
                Log.e("SandboxBridgeWipe", "wipeData(0) failed: ${e.message}", e)
            }
        } else {
            try {
                startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        finish()
    }
}
