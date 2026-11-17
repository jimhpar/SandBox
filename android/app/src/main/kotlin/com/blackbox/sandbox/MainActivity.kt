package com.blackbox.sandbox

import android.os.Bundle
import androidx.annotation.NonNull
import com.blackbox.sandbox.core.SandboxManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.blackbox.sandbox/engine"
    private lateinit var sandboxManager: SandboxManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sandboxManager = SandboxManager.getInstance(applicationContext)
        handleShortcutIntent()
    }

    private fun handleShortcutIntent() {
        val intent = intent ?: return
        if (intent.getBooleanExtra("is_sandbox_clone", false)) {
            val pkg = intent.getStringExtra("launch_pkg") ?: intent.getStringExtra("sandbox_pkg")
            val instId = intent.getStringExtra("sandbox_instance_id") ?: ""
            if (!pkg.isNullOrEmpty()) {
                sandboxManager.launchApp(instId, pkg)
            }
        }
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        sandboxManager = SandboxManager.getInstance(applicationContext)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getInstalledApps" -> {
                    try {
                        val apps = sandboxManager.getInstalledApps()
                        result.success(apps)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }
                "cloneApp" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    val customName = call.argument<String>("customName")
                    if (packageName.isNotEmpty()) {
                        val inst = sandboxManager.cloneApp(packageName, customName)
                        result.success(mapOf(
                            "instanceId" to inst.instanceId,
                            "packageName" to inst.packageName,
                            "appName" to inst.appName,
                            "fakeAndroidId" to inst.fakeAndroidId,
                            "fakeDeviceModel" to inst.fakeDeviceModel,
                            "playBillingEnabled" to inst.playBillingEnabled,
                            "storageUsageBytes" to inst.storageUsageBytes,
                            "iconBase64" to inst.iconBase64,
                            "isPrimary" to inst.isPrimary,
                            "badgeColor" to inst.badgeColor
                        ))
                    } else {
                        result.error("INVALID_ARGS", "Package name required", null)
                    }
                }
                "getInstances" -> {
                    val instances = sandboxManager.getInstances().map {
                        mapOf(
                            "instanceId" to it.instanceId,
                            "packageName" to it.packageName,
                            "appName" to it.appName,
                            "fakeAndroidId" to it.fakeAndroidId,
                            "fakeDeviceModel" to it.fakeDeviceModel,
                            "playBillingEnabled" to it.playBillingEnabled,
                            "storageUsageBytes" to it.storageUsageBytes,
                            "iconBase64" to it.iconBase64,
                            "isPrimary" to it.isPrimary,
                            "badgeColor" to it.badgeColor
                        )
                    }
                    result.success(instances)
                }
                "launchApp" -> {
                    val instanceId = call.argument<String>("instanceId") ?: ""
                    val packageName = call.argument<String>("packageName") ?: ""
                    if (packageName.isNotEmpty()) {
                        val success = sandboxManager.launchApp(instanceId, packageName)
                        result.success(success)
                    } else {
                        result.error("INVALID_ARGS", "Package name required", null)
                    }
                }
                "createShortcut" -> {
                    val instanceId = call.argument<String>("instanceId") ?: ""
                    val packageName = call.argument<String>("packageName") ?: ""
                    val appName = call.argument<String>("appName") ?: ""
                    val badgeColor = call.argument<String>("badgeColor") ?: "#FF6D00"
                    val success = sandboxManager.createHomeScreenShortcut(instanceId, packageName, appName, badgeColor)
                    result.success(success)
                }
                "clearCache" -> {
                    val instanceId = call.argument<String>("instanceId") ?: ""
                    val packageName = call.argument<String>("packageName") ?: ""
                    val success = sandboxManager.clearCache(instanceId, packageName)
                    result.success(success)
                }
                "clearData" -> {
                    val instanceId = call.argument<String>("instanceId") ?: ""
                    val packageName = call.argument<String>("packageName") ?: ""
                    val success = sandboxManager.clearData(instanceId, packageName)
                    result.success(success)
                }
                "deleteInstance" -> {
                    val id = call.argument<String>("instanceId") ?: ""
                    val success = sandboxManager.deleteInstance(id)
                    result.success(success)
                }
                "isWorkProfileActive" -> {
                    result.success(sandboxManager.isWorkProfileActive())
                }
                "provisionWorkProfile" -> {
                    val ok = sandboxManager.provisionWorkProfile(this)
                    result.success(ok)
                }
                "removeWorkProfile" -> {
                    val ok = sandboxManager.removeWorkProfile()
                    result.success(ok)
                }
                "openWorkProfileAddAccount" -> {
                    val ok = sandboxManager.openWorkProfileAddAccount()
                    result.success(ok)
                }
                "getWorkProfileCoreApps" -> {
                    val apps = sandboxManager.getWorkProfileCoreApps()
                    result.success(apps)
                }
                "checkRealServicesStatus" -> {
                    val status = sandboxManager.checkRealServicesStatus()
                    result.success(status)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sandboxManager.billingBridge.unbindHostBilling()
    }
}
