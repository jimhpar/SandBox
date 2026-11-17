package com.blackbox.sandbox.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * SandboxWorkProfileBridgeActivity
 * Cross-profile bridge running inside the Work Profile.
 * Receives implicit intents forwarded via DevicePolicyManager.addCrossProfileIntentFilter:
 * - "install": Offline instant APK provisioning via installExistingPackage
 * - "install_and_launch": Provisions APK into Work Profile and immediately launches it
 * - "wipe": Complete Work Profile removal via dpm.wipeData(0)
 * - "add_account": Work Profile Google Account sign-in via Settings.ACTION_ADD_ACCOUNT
 * - "clean_bloatware": Hides OEM bloatware & system apps from the launcher
 */
class SandboxWorkProfileBridgeActivity : Activity() {

    private val TAG = "SandboxBridge"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.getStringExtra("bridge_action") ?: ""
        val targetPkg = intent?.getStringExtra("target_pkg") ?: ""

        Log.d(TAG, "Bridge activity launched in profile with action: $action, target: $targetPkg")

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            finish()
            return
        }
        val admin = SandboxDeviceAdminReceiver.getComponentName(this)

        try {
            when (action) {
                "install" -> {
                    if (targetPkg.isNotEmpty()) {
                        provisionPackageInProfile(dpm, admin, targetPkg)
                    }
                    cleanBloatware(this, dpm, admin)
                }
                "install_and_launch" -> {
                    if (targetPkg.isNotEmpty()) {
                        provisionPackageInProfile(dpm, admin, targetPkg)
                        val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            Log.d(TAG, "Provisioned and launched $targetPkg inside Work Profile!")
                        } else {
                            val storeIntent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("market://details?id=$targetPkg")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(storeIntent)
                        }
                    }
                    cleanBloatware(this, dpm, admin)
                }
                "wipe" -> {
                    Log.w(TAG, "Executing Work Profile wipeData(0)...")
                    Toast.makeText(this, "Removing Sandbox Dual Space...", Toast.LENGTH_SHORT).show()
                    if (dpm.isProfileOwnerApp(packageName)) {
                        dpm.wipeData(0)
                    } else {
                        val settingsIntent = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(settingsIntent)
                    }
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
                        data = Uri.parse(if (targetPkg.isNotEmpty()) "market://details?id=$targetPkg" else "https://play.google.com/store")
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

    private fun provisionPackageInProfile(dpm: DevicePolicyManager, admin: ComponentName, targetPkg: String): Boolean {
        if (!dpm.isProfileOwnerApp(packageName)) {
            Log.w(TAG, "Not profile owner; cannot call installExistingPackage for $targetPkg")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val ok = dpm.installExistingPackage(admin, targetPkg)
                    dpm.setApplicationHidden(admin, targetPkg, false)
                    Log.d(TAG, "installExistingPackage($targetPkg) = $ok")
                    ok
                } catch (e: Exception) {
                    Log.w(TAG, "installExistingPackage failed, trying enableSystemApp: ${e.message}")
                    dpm.enableSystemApp(admin, targetPkg)
                    dpm.setApplicationHidden(admin, targetPkg, false)
                    true
                }
            } else {
                dpm.enableSystemApp(admin, targetPkg)
                dpm.setApplicationHidden(admin, targetPkg, false)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to provision $targetPkg in profile", e)
            false
        }
    }

    companion object {
        fun cleanBloatware(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
            try {
                if (!dpm.isProfileOwnerApp(context.packageName)) {
                    return
                }
                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(0)
                val keepPackages = setOf(
                    "com.android.vending",
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.google.android.play.games",
                    "com.android.settings",
                    "com.android.systemui",
                    "com.android.providers.downloads",
                    "com.google.android.inputmethod.latin",
                    "ridmik.keyboard",
                    context.packageName
                )

                for (app in installedApps) {
                    val pkg = app.packageName
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !keepPackages.contains(pkg)) {
                        try {
                            // Only hide apps that actually have a launcher activity on the home screen
                            val hasLauncher = pm.getLaunchIntentForPackage(pkg) != null
                            if (hasLauncher && !dpm.isApplicationHidden(admin, pkg)) {
                                dpm.setApplicationHidden(admin, pkg, true)
                                Log.d("SandboxBridge", "Hidden OEM launcher bloatware from profile: $pkg")
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
