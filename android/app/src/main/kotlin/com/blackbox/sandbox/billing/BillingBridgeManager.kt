package com.blackbox.sandbox.billing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.android.vending.billing.IInAppBillingService

/**
 * BillingBridgeManager
 * Handles routing and proxying Google Play Billing AIDL calls from isolated cloned
 * processes to the host device's Google Play Store (com.android.vending).
 * Developed for Blackbox THC Sandbox v2.0.1.
 */
class BillingBridgeManager(private val context: Context) {

    private val TAG = "BlackboxSandboxBilling"
    private var hostBillingService: IInAppBillingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            hostBillingService = IInAppBillingService.Stub.asInterface(service)
            isBound = true
            Log.d(TAG, "Host Google Play Billing Service connected successfully.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostBillingService = null
            isBound = false
            Log.w(TAG, "Host Google Play Billing Service disconnected.")
        }
    }

    fun bindHostBilling(): Boolean {
        if (isBound) return true
        val serviceIntent = Intent("com.android.vending.billing.InAppBillingService.BIND").apply {
            setPackage("com.android.vending")
        }
        return try {
            isBound = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Attempting to bind host Google Play Billing: $isBound")
            isBound
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind host Play Store billing service", e)
            false
        }
    }

    fun unbindHostBilling() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding billing service", e)
            }
            isBound = false
            hostBillingService = null
        }
    }

    fun isBillingSupported(apiVersion: Int, targetPkg: String, type: String): Int {
        return hostBillingService?.isBillingSupported(apiVersion, targetPkg, type) ?: 3 // BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE
    }

    fun getSkuDetails(apiVersion: Int, targetPkg: String, type: String, skusBundle: Bundle): Bundle {
        return hostBillingService?.getSkuDetails(apiVersion, targetPkg, type, skusBundle) ?: Bundle().apply {
            putInt("RESPONSE_CODE", 3)
        }
    }

    fun getBuyIntent(apiVersion: Int, targetPkg: String, sku: String, type: String, developerPayload: String): Bundle {
        return hostBillingService?.getBuyIntent(apiVersion, targetPkg, sku, type, developerPayload) ?: Bundle().apply {
            putInt("RESPONSE_CODE", 3)
        }
    }

    fun getPurchases(apiVersion: Int, targetPkg: String, type: String, continuationToken: String?): Bundle {
        return hostBillingService?.getPurchases(apiVersion, targetPkg, type, continuationToken) ?: Bundle().apply {
            putInt("RESPONSE_CODE", 3)
        }
    }

    fun consumePurchase(apiVersion: Int, targetPkg: String, purchaseToken: String): Int {
        return hostBillingService?.consumePurchase(apiVersion, targetPkg, purchaseToken) ?: 3
    }
}
