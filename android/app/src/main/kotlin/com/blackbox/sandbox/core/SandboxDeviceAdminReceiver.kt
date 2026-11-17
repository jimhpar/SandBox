package com.blackbox.sandbox.core

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.blackbox.sandbox.MainActivity

/**
 * SandboxDeviceAdminReceiver
 * Enables Work Profile provisioning (Island / Shelter style) for Blackbox THC Sandbox.
 * Automatically activates Google Play Store and Google Play Services inside the isolated Work Profile,
 * registers the CrossProfileIntentFilter for parent->profile commands, and hides OEM system bloatware.
 */
class SandboxDeviceAdminReceiver : DeviceAdminReceiver() {

    private val TAG = "SandboxDeviceAdmin"

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            if (dpm.isProfileOwnerApp(context.packageName)) {
                Log.d(TAG, "Package replaced inside Managed Profile: re-initializing bridge components...")
                initializeManagedProfile(context, dpm)
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (dpm.isProfileOwnerApp(context.packageName)) {
            Log.d(TAG, "onEnabled as ProfileOwner: initializing managed profile policies...")
            initializeManagedProfile(context, dpm)
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Work Profile provisioning complete! Initializing GMS and Play Store...")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        initializeManagedProfile(context, dpm)
    }

    companion object {
        const val ACTION_WORK_PROFILE_BRIDGE = "com.blackbox.sandbox.ACTION_WORK_PROFILE_BRIDGE"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SandboxDeviceAdminReceiver::class.java)
        }

        fun initializeManagedProfile(context: Context, dpm: DevicePolicyManager) {
            try {
                val admin = getComponentName(context)

                // 1. Enable Profile & set name
                try {
                    dpm.setProfileEnabled(admin)
                    dpm.setProfileName(admin, "Sandbox Dual Space")
                } catch (e: Exception) {
                    Log.w("SandboxDeviceAdmin", "setProfileEnabled warning: ${e.message}")
                }

                // 2. Enable only essential Google services in Work Profile
                val coreGooglePackages = listOf(
                    "com.android.vending",
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.google.android.play.games"
                )
                for (pkg in coreGooglePackages) {
                    try {
                        dpm.enableSystemApp(admin, pkg)
                        dpm.setApplicationHidden(admin, pkg, false)
                    } catch (_: Exception) {}
                }

                // 3. Enable SandboxWorkProfileBridgeActivity & BridgeWipeActivity inside Work Profile
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    ComponentName(context, SandboxWorkProfileBridgeActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    ComponentName(context, BridgeWipeActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                // 4. Register CrossProfileIntentFilter so Parent (User 0) can send commands with extras to Work Profile
                val filter = IntentFilter(ACTION_WORK_PROFILE_BRIDGE).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addCrossProfileIntentFilter(
                    admin,
                    filter,
                    DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
                )

                // 5. Hide unwanted OEM bloatware so phone home screen stays clean
                SandboxWorkProfileBridgeActivity.cleanBloatware(context, dpm, admin)

                // 6. Disable MainActivity launcher icon inside Work Profile so Sandbox doesn't show a duplicate briefcase icon
                pm.setComponentEnabledSetting(
                    ComponentName(context, MainActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )

                Log.d("SandboxDeviceAdmin", "Managed profile fully configured with CrossProfileIntentFilter & bloatware hidden.")
            } catch (e: Exception) {
                Log.e("SandboxDeviceAdmin", "Error initializing managed profile", e)
            }
        }
    }
}
