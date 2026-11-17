package com.blackbox.sandbox.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * SandboxWorkProfileBridgeActivity
 * Cross-profile bridge running inside the Work Profile (User 10).
 * Handles privileged profile owner operations:
 * - Offline instant APK provisioning via installExistingPackage
 * - Work Profile complete unprovisioning/wiping via wipeData
 * - Work Profile Google Account sign-in via Settings.ACTION_ADD_ACCOUNT
 */
class SandboxWorkProfileBridgeActivity : Activity() {

    private val TAG = "SandboxBridge"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra("bridge_action") ?: ""
        val targetPkg = intent.getStringExtra("target_pkg") ?: ""

        Log.d(TAG, "Bridge activity launched in profile with action: $action, target: $targetPkg")

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = SandboxDeviceAdminReceiver.getComponentName(this)

        try {
            when (action) {
                "install" -> {
                    if (targetPkg.isNotEmpty()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                dpm.installExistingPackage(admin, targetPkg)
                                dpm.setApplicationHidden(admin, targetPkg, false)
                                Log.d(TAG, "Successfully called installExistingPackage for $targetPkg")
                            } catch (e: Exception) {
                                Log.w(TAG, "installExistingPackage failed, attempting enableSystemApp: ${e.message}")
                                dpm.enableSystemApp(admin, targetPkg)
                            }
                        } else {
                            dpm.enableSystemApp(admin, targetPkg)
                        }
                    }
                }
                "wipe" -> {
                    Log.w(TAG, "Executing Work Profile wipeData(0)...")
                    Toast.makeText(this, "Removing Sandbox Work Profile...", Toast.LENGTH_SHORT).show()
                    dpm.wipeData(0)
                }
                "add_account" -> {
                    Log.d(TAG, "Opening Google Account sign-in in Work Profile...")
                    val accountIntent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                        putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(accountIntent)
                }
                "open_play_store" -> {
                    val storeIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse(if (targetPkg.isNotEmpty()) "market://details?id=$targetPkg" else "https://play.google.com/store")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(storeIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing bridge action $action", e)
        }

        finish()
    }
}
