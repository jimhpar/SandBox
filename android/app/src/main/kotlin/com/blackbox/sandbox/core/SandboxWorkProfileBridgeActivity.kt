package com.blackbox.sandbox.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
 * - Automatic OEM bloatware & system app hiding (Music, Compass, Camera, etc.)
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
                                dpm.setApplicationHidden(admin, targetPkg, false)
                            }
                        } else {
                            dpm.enableSystemApp(admin, targetPkg)
                            dpm.setApplicationHidden(admin, targetPkg, false)
                        }
                    }
                    cleanBloatware(this, dpm, admin)
                }
                "wipe" -> {
                    Log.w(TAG, "Executing Work Profile wipeData(0)...")
                    Toast.makeText(this, "Removing Sandbox Work Profile...", Toast.LENGTH_SHORT).show()
                    dpm.wipeData(0)
                }
                "clean_bloatware" -> {
                    Log.d(TAG, "Cleaning OEM bloatware from profile...")
                    cleanBloatware(this, dpm, admin)
                }
                "add_account" -> {
                    Log.d(TAG, "Opening Google Account sign-in in Work Profile...")
                    val accountIntent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                        putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(accountIntent)
                    cleanBloatware(this, dpm, admin)
                }
                "open_play_store" -> {
                    val storeIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse(if (targetPkg.isNotEmpty()) "market://details?id=$targetPkg" else "https://play.google.com/store")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(storeIntent)
                }
                else -> {
                    cleanBloatware(this, dpm, admin)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing bridge action $action", e)
        }

        finish()
    }

    companion object {
        fun cleanBloatware(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
            try {
                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(0)
                val keepPackages = setOf(
                    "com.android.vending",
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.google.android.play.games",
                    context.packageName
                )

                for (app in installedApps) {
                    val pkg = app.packageName
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !keepPackages.contains(pkg)) {
                        try {
                            if (!dpm.isApplicationHidden(admin, pkg)) {
                                dpm.setApplicationHidden(admin, pkg, true)
                                Log.d("SandboxBridge", "Hidden OEM bloatware app from profile: $pkg")
                            }
                        } catch (e: Exception) {
                            Log.w("SandboxBridge", "Could not hide $pkg: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SandboxBridge", "Error in cleanBloatware", e)
            }
        }
    }
}
