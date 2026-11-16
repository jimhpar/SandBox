package com.blackbox.sandbox.core

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * SandboxDeviceAdminReceiver
 * Enables Work Profile provisioning (Island / Shelter style) for Blackbox THC Sandbox.
 * Automatically activates Google Play Store and Google Play Services inside the isolated Work Profile.
 */
class SandboxDeviceAdminReceiver : DeviceAdminReceiver() {

    private val TAG = "SandboxDeviceAdmin"

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Work Profile provisioning complete! Initializing GMS and Play Store...")

        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = getComponentName(context)

            // Enable Profile
            dpm.setProfileEnabled(admin)
            dpm.setProfileName(admin, "Sandbox Dual Space")

            // Enable Google Play Store & Google Play Services inside the Work Profile
            dpm.enableSystemApp(admin, "com.android.vending")
            dpm.enableSystemApp(admin, "com.google.android.gms")
            dpm.enableSystemApp(admin, "com.google.android.gsf")

            Log.d(TAG, "Google Play Store & GMS successfully enabled in Work Profile.")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling Play Store in Work Profile", e)
        }
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SandboxDeviceAdminReceiver::class.java)
        }
    }
}
