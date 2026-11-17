package com.blackbox.sandbox.core

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.blackbox.sandbox.billing.BillingBridgeManager
import com.blackbox.sandbox.vfs.VirtualFileSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class ClonedInstance(
    val instanceId: String,
    val packageName: String,
    val appName: String,
    val fakeAndroidId: String,
    val fakeDeviceModel: String,
    val playBillingEnabled: Boolean = true,
    val storageUsageBytes: Long = 0L,
    val iconBase64: String = "",
    val isPrimary: Boolean = true,
    val badgeColor: String = "#FF6D00",
    val clonedPackageName: String = packageName
)

/**
 * SandboxManager (Hybrid Offline APK Cloner + Work Profile Engine)
 * - Standalone APK Cloner: Copies device APK offline, renames package (.sb1, .sb2...), signs v2/v3, installs independently.
 * - Work Profile (Optional): Native GMS & Play Store isolation.
 * Developed for Blackbox THC Sandbox v2.1.0.
 */
class SandboxManager(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SandboxManager? = null

        fun getInstance(context: Context): SandboxManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SandboxManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val TAG = "BlackboxSandbox"
    val vfs = VirtualFileSystem(context)
    val billingBridge = BillingBridgeManager(context)
    private val apkCloner = ApkClonerEngine(context)
    private val cloneExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val instancesFile = File(context.filesDir, "sandbox_instances.json")

    private val instances = mutableMapOf<String, ClonedInstance>()

    @Volatile
    var cloneProgressListener: ((Map<String, Any>) -> Unit)? = null

    private var pendingCloneMeta: ClonedInstance? = null

    private val installStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ApkClonerEngine.ACTION_INSTALL_STATUS) return
            val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
            val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            val clonedPkg = intent.getStringExtra("cloned_pkg") ?: ""
            Log.d(TAG, "PackageInstaller status=$status for $clonedPkg msg=$msg")

            when (status) {
                android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirmIntent != null) {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(confirmIntent)
                            emitCloneProgress(0.90, "Confirm Android install prompt...", false, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start install confirmation", e)
                            emitCloneProgress(1.0, "Install prompt error: ${e.message}", true, false)
                        }
                    }
                }
                android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                    pendingCloneMeta?.let { meta ->
                        if (meta.clonedPackageName == clonedPkg || clonedPkg.isEmpty()) {
                            instances[meta.instanceId] = meta
                            savePersistedInstances()
                            createHomeScreenShortcut(meta.instanceId, meta.clonedPackageName, meta.appName, meta.badgeColor)
                            pendingCloneMeta = null
                        }
                    }
                    emitCloneProgress(1.0, "Cloned app installed & ready!", true, true)
                }
                else -> {
                    pendingCloneMeta = null
                    val errText = if (msg.isNotEmpty()) msg else "Installation failed (code $status)"
                    emitCloneProgress(1.0, errText, true, false)
                }
            }
        }
    }

    init {
        loadPersistedInstances()
        billingBridge.bindHostBilling()
        ensureBridgeState()
        registerInstallReceiver()
    }

    private fun registerInstallReceiver() {
        try {
            val filter = android.content.IntentFilter(ApkClonerEngine.ACTION_INSTALL_STATUS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(installStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(installStatusReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerInstallReceiver warning: ${e.message}")
        }
    }

    private fun emitCloneProgress(progress: Double, message: String, done: Boolean, success: Boolean) {
        Handler(Looper.getMainLooper()).post {
            cloneProgressListener?.invoke(
                mapOf(
                    "progress" to progress,
                    "message" to message,
                    "done" to done,
                    "success" to success
                )
            )
        }
    }

    private fun ensureBridgeState() {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            if (dpm.isProfileOwnerApp(context.packageName)) {
                SandboxDeviceAdminReceiver.initializeManagedProfile(context, dpm)
            } else {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, SandboxWorkProfileBridgeActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, BridgeWipeActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureBridgeState warning: ${e.message}")
        }
    }

    private fun loadPersistedInstances() {
        if (!instancesFile.exists()) return
        try {
            val jsonString = instancesFile.readText()
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pkg = obj.getString("packageName")
                val inst = ClonedInstance(
                    instanceId = obj.getString("instanceId"),
                    packageName = pkg,
                    appName = obj.getString("appName"),
                    fakeAndroidId = obj.getString("fakeAndroidId"),
                    fakeDeviceModel = obj.getString("fakeDeviceModel"),
                    playBillingEnabled = obj.optBoolean("playBillingEnabled", true),
                    storageUsageBytes = obj.optLong("storageUsageBytes", 0L),
                    iconBase64 = obj.optString("iconBase64", ""),
                    isPrimary = obj.optBoolean("isPrimary", true),
                    badgeColor = obj.optString("badgeColor", "#FF6D00"),
                    clonedPackageName = obj.optString("clonedPackageName", pkg)
                )
                instances[inst.instanceId] = inst
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted instances", e)
        }
    }

    private fun savePersistedInstances() {
        try {
            val array = JSONArray()
            for (inst in instances.values) {
                val obj = JSONObject().apply {
                    put("instanceId", inst.instanceId)
                    put("packageName", inst.packageName)
                    put("clonedPackageName", inst.clonedPackageName)
                    put("appName", inst.appName)
                    put("fakeAndroidId", inst.fakeAndroidId)
                    put("fakeDeviceModel", inst.fakeDeviceModel)
                    put("playBillingEnabled", inst.playBillingEnabled)
                    put("storageUsageBytes", inst.storageUsageBytes)
                    put("iconBase64", inst.iconBase64)
                    put("isPrimary", inst.isPrimary)
                    put("badgeColor", inst.badgeColor)
                }
                array.put(obj)
            }
            instancesFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist instances", e)
        }
    }

    fun getWorkProfileUserHandle(): UserHandle? {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return null
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return null
        val myHandle = Process.myUserHandle()

        for (profile in userManager.userProfiles) {
            if (profile == myHandle) continue
            // Explicitly ignore Xiaomi MIUI/HyperOS XSpace (User 999)
            if (profile.toString().contains("999")) {
                continue
            }
            try {
                // A valid managed Work Profile must have Google Play Store or Sandbox installed inside it
                val vendingActs = launcherApps.getActivityList("com.android.vending", profile)
                val sandboxActs = launcherApps.getActivityList(context.packageName, profile)
                val hasVendingPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val info = launcherApps.getApplicationInfo("com.android.vending", 0, profile)
                        info != null && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                if (vendingActs.isNotEmpty() || sandboxActs.isNotEmpty() || hasVendingPkg) {
                    Log.d(TAG, "Discovered valid Managed Work Profile: $profile")
                    return profile
                }
            } catch (e: Exception) {
                Log.d(TAG, "Ignoring non-accessible profile $profile: ${e.message}")
            }
        }
        return null
    }

    fun isWorkProfileActive(): Boolean {
        return getWorkProfileUserHandle() != null
    }

    private fun sendBridgeCommand(action: String, targetPkg: String? = null): Boolean {
        val workProfile = getWorkProfileUserHandle() ?: return false
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return false

        val extras = Bundle().apply {
            putString("bridge_action", action)
            if (!targetPkg.isNullOrEmpty()) {
                putString("target_pkg", targetPkg)
            }
        }

        // Primary: Launch SandboxWorkProfileBridgeActivity inside the Work Profile via LauncherApps
        try {
            launcherApps.startMainActivity(
                ComponentName(context.packageName, "com.blackbox.sandbox.core.SandboxWorkProfileBridgeActivity"),
                workProfile,
                null,
                extras
            )
            Log.d(TAG, "Dispatched bridge command ($action, target=$targetPkg) to SandboxWorkProfileBridgeActivity in $workProfile")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "LauncherApps BridgeActivity ($action) failed: ${e.message}")
        }

        // Fallback 1: Try MainActivity in Work Profile
        try {
            launcherApps.startMainActivity(
                ComponentName(context.packageName, "com.blackbox.sandbox.MainActivity"),
                workProfile,
                null,
                extras
            )
            Log.d(TAG, "Dispatched bridge command ($action) to MainActivity in $workProfile")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "LauncherApps MainActivity ($action) failed: ${e.message}")
        }

        // Fallback 2: CrossProfileIntentFilter implicit Intent
        return try {
            val intent = Intent(SandboxDeviceAdminReceiver.ACTION_WORK_PROFILE_BRIDGE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtras(extras)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "All cross-profile bridge dispatch methods failed for $action: ${e.message}")
            false
        }
    }

    fun provisionWorkProfile(activity: Activity): Boolean {
        return try {
            val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                    SandboxDeviceAdminReceiver.getComponentName(context)
                )
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                activity.startActivityForResult(intent, 51001)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Work Profile provisioning", e)
            false
        }
    }

    fun removeWorkProfile(): Boolean {
        val workProfile = getWorkProfileUserHandle()
        if (workProfile == null) {
            Log.d(TAG, "No active Work Profile to remove.")
            return true
        }

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        var sent = false
        if (launcherApps != null) {
            // Dedicated zero-extras BridgeWipeActivity (never affected by LauncherApps opts stripping)
            try {
                launcherApps.startMainActivity(
                    ComponentName(context.packageName, "com.blackbox.sandbox.core.BridgeWipeActivity"),
                    workProfile,
                    null,
                    null
                )
                sent = true
                Log.d(TAG, "Dispatched zero-extras BridgeWipeActivity inside $workProfile")
            } catch (e: Exception) {
                Log.w(TAG, "BridgeWipeActivity failed: ${e.message}; trying CrossProfileIntentFilter...")
                sent = sendBridgeCommand("wipe")
            }
        }

        // Only clear Work Profile-backed instances; keep standalone APK clones intact
        instances.entries.removeIf { it.value.clonedPackageName == it.value.packageName }
        savePersistedInstances()
        return sent
    }

    fun cleanWorkProfileBloatware(): Boolean {
        if (getWorkProfileUserHandle() == null) return false
        return sendBridgeCommand("clean_bloatware")
    }

    fun openWorkProfileAddAccount(): Boolean {
        if (getWorkProfileUserHandle() == null) return false
        return sendBridgeCommand("add_account")
    }

    fun installAppIntoWorkProfile(packageName: String): Boolean {
        val workProfile = getWorkProfileUserHandle() ?: return false
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return false

        return try {
            val existing = launcherApps.getActivityList(packageName, workProfile)
            if (existing.isNotEmpty()) {
                Log.d(TAG, "Package $packageName is already installed in Work Profile.")
                return true
            }
            sendBridgeCommand("install", packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing $packageName into Work Profile via bridge", e)
            false
        }
    }

    fun getWorkProfileCoreApps(): List<Map<String, Any>> {
        val workProfile = getWorkProfileUserHandle() ?: return emptyList()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return emptyList()
        val pm = context.packageManager

        // Strict whitelist: Only the essential Google services needed to run apps & games
        val corePackages = listOf(
            "com.android.vending",           // Google Play Store
            "com.google.android.gms",        // Google Play Services (GMS)
            "com.google.android.play.games", // Google Play Games
            "com.google.android.gsf"         // Google Services Framework
        )

        val resultList = mutableListOf<Map<String, Any>>()
        val activityMap = mutableMapOf<String, android.content.pm.LauncherActivityInfo>()

        try {
            val activities = launcherApps.getActivityList(null, workProfile)
            for (act in activities) {
                val pkgName = act.applicationInfo.packageName
                if (corePackages.contains(pkgName) && !activityMap.containsKey(pkgName)) {
                    activityMap[pkgName] = act
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Work Profile activities", e)
        }

        for (pkgName in corePackages) {
            try {
                val act = activityMap[pkgName]
                if (act != null) {
                    val label = act.label.toString()
                    val iconBase64 = drawableToBase64(act.getBadgedIcon(0))
                    resultList.add(mapOf(
                        "packageName" to pkgName,
                        "appName" to label,
                        "iconBase64" to iconBase64,
                        "componentName" to act.componentName.flattenToString(),
                        "isPlayStore" to (pkgName == "com.android.vending")
                    ))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val appInfo = launcherApps.getApplicationInfo(pkgName, 0, workProfile)
                    if (appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0) {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        val rawIcon = pm.getApplicationIcon(appInfo)
                        val badgedIcon = pm.getUserBadgedIcon(rawIcon, workProfile)
                        val iconBase64 = drawableToBase64(badgedIcon)
                        resultList.add(mapOf(
                            "packageName" to pkgName,
                            "appName" to label,
                            "iconBase64" to iconBase64,
                            "componentName" to "",
                            "isPlayStore" to (pkgName == "com.android.vending")
                        ))
                    }
                }
            } catch (_: Exception) {
                // Skip if not present in profile
            }
        }

        return resultList.distinctBy { it["packageName"] }
    }

    fun getInstalledApps(): List<Map<String, Any>> {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<Map<String, Any>>()

        for (app in installed) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem || app.packageName == "com.google.android.youtube" || app.packageName == "com.google.android.apps.photos") {
                if (app.packageName == context.packageName) continue
                // Exclude already-cloned .sb1/.sb2 packages from the source app list
                if (instances.values.any { it.clonedPackageName == app.packageName && it.clonedPackageName != it.packageName }) continue

                val name = pm.getApplicationLabel(app).toString()
                val iconDrawable = try {
                    pm.getApplicationIcon(app)
                } catch (e: Exception) {
                    pm.defaultActivityIcon
                }
                val iconBase64 = drawableToBase64(iconDrawable)
                val apkFile = File(app.sourceDir)
                val apkSize = if (apkFile.exists()) apkFile.length() else 0L

                appList.add(mapOf(
                    "packageName" to app.packageName,
                    "appName" to name,
                    "iconBase64" to iconBase64,
                    "apkSizeBytes" to apkSize
                ))
            }
        }
        return appList.sortedBy { (it["appName"] as String).lowercase() }
    }

    fun cloneAppAsync(packageName: String, customName: String?): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val baseAppName = pm.getApplicationLabel(appInfo).toString()

            var cloneIdx = 1
            while (true) {
                val candidate = "$packageName.sb$cloneIdx"
                val existsInInstances = instances.values.any { it.clonedPackageName == candidate }
                val existsOnDevice = try {
                    pm.getPackageInfo(candidate, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                if (!existsInInstances && !existsOnDevice) break
                cloneIdx++
            }

            val clonedPkg = "$packageName.sb$cloneIdx"
            val isPrimary = (cloneIdx == 1)
            val badgeColor = if (isPrimary) "#FF6D00" else "#FF2D75"
            val appName = if (!customName.isNullOrEmpty()) customName else "$baseAppName (SB$cloneIdx)"

            val instanceId = UUID.randomUUID().toString().substring(0, 8)
            val fakeAndroidId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            val sampleModels = listOf("Samsung Galaxy S24 Ultra", "Google Pixel 9 Pro", "OnePlus 12", "Xiaomi 14 Ultra")
            val fakeModel = sampleModels.random()

            val iconDrawable = pm.getApplicationIcon(appInfo)
            val iconBase64 = drawableToBase64(iconDrawable)
            val apkFile = File(appInfo.sourceDir)
            val initialSize = if (apkFile.exists()) apkFile.length() else 0L

            val meta = ClonedInstance(
                instanceId = instanceId,
                packageName = packageName,
                appName = appName,
                fakeAndroidId = fakeAndroidId,
                fakeDeviceModel = fakeModel,
                playBillingEnabled = true,
                storageUsageBytes = initialSize,
                iconBase64 = iconBase64,
                isPrimary = isPrimary,
                badgeColor = badgeColor,
                clonedPackageName = clonedPkg
            )
            pendingCloneMeta = meta

            cloneExecutor.execute {
                emitCloneProgress(0.05, "Preparing offline clone for $baseAppName...", false, true)
                val started = apkCloner.cloneAndInstallApk(packageName, clonedPkg, "SB$cloneIdx") { p, msg ->
                    emitCloneProgress(p, msg, false, true)
                }
                if (!started) {
                    pendingCloneMeta = null
                    emitCloneProgress(1.0, "Failed to repackage $baseAppName APK.", true, false)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "cloneAppAsync error", e)
            emitCloneProgress(1.0, "Error: ${e.message}", true, false)
            false
        }
    }

    fun cloneApp(packageName: String, customName: String?): ClonedInstance {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        val baseAppName = pm.getApplicationLabel(appInfo).toString()

        val existingInstances = instances.values.filter { it.packageName == packageName }
        val isPrimary = existingInstances.isEmpty()
        val badgeColor = if (isPrimary) "#FF6D00" else "#FF2D75"

        val appName = if (!customName.isNullOrEmpty()) {
            customName
        } else if (isPrimary) {
            "$baseAppName (Primary SB)"
        } else {
            "$baseAppName (Clone ${existingInstances.size + 1})"
        }

        val instanceId = UUID.randomUUID().toString().substring(0, 8)
        val fakeAndroidId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val sampleModels = listOf("Samsung Galaxy S24 Ultra", "Google Pixel 9 Pro", "OnePlus 12", "Xiaomi 14 Ultra")
        val fakeModel = sampleModels.random()

        val iconDrawable = pm.getApplicationIcon(appInfo)
        val iconBase64 = drawableToBase64(iconDrawable)

        val apkFile = File(appInfo.sourceDir)
        val initialSize = if (apkFile.exists()) apkFile.length() else 0L

        vfs.getDataDir(instanceId, packageName)

        val instance = ClonedInstance(
            instanceId = instanceId,
            packageName = packageName,
            appName = appName,
            fakeAndroidId = fakeAndroidId,
            fakeDeviceModel = fakeModel,
            playBillingEnabled = isPrimary,
            storageUsageBytes = initialSize,
            iconBase64 = iconBase64,
            isPrimary = isPrimary,
            badgeColor = badgeColor
        )

        instances[instanceId] = instance
        savePersistedInstances()

        // Local instant provisioning into Work Profile if Primary
        if (isPrimary) {
            installAppIntoWorkProfile(packageName)
        }

        createHomeScreenShortcut(instanceId, packageName, appName, badgeColor)

        return instance
    }

    fun getInstances(): List<ClonedInstance> {
        val pm = context.packageManager
        val workProfile = getWorkProfileUserHandle()

        // Dynamic OS Discovery: Query actual apps present in Work Profile
        if (workProfile != null) {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (launcherApps != null) {
                try {
                    val activities = launcherApps.getActivityList(null, workProfile)
                    val corePackages = setOf(
                        "com.android.vending", "com.google.android.gms",
                        "com.google.android.contacts", "com.android.contacts",
                        "com.google.android.documentsui", "com.android.documentsui",
                        "com.google.android.gm", "com.android.dialer",
                        "com.google.android.dialer", context.packageName
                    )

                    for (act in activities) {
                        val pkg = act.applicationInfo.packageName
                        val isSystemApp = (act.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        // Only user-installed apps (like Hay Day, games, social) are added to Cloned Apps (Tab 1).
                        // System/preinstalled apps (Camera, Chrome, Calculator, Play Store, etc.) stay in Work Space Core (Tab 2).
                        if (!isSystemApp && pkg != context.packageName) {
                            val alreadyTracked = instances.values.any { it.packageName == pkg }
                            if (!alreadyTracked) {
                                val instId = UUID.randomUUID().toString().substring(0, 8)
                                val label = act.label.toString()
                                val iconBase64 = drawableToBase64(act.getBadgedIcon(0))
                                val newInst = ClonedInstance(
                                    instanceId = instId,
                                    packageName = pkg,
                                    appName = "$label (Primary SB)",
                                    fakeAndroidId = UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                                    fakeDeviceModel = "Google Pixel 9 Pro",
                                    playBillingEnabled = true,
                                    storageUsageBytes = 0L,
                                    iconBase64 = iconBase64,
                                    isPrimary = true,
                                    badgeColor = "#FF6D00"
                                )
                                instances[instId] = newInst
                                savePersistedInstances()
                                Log.d(TAG, "Auto-discovered user clone in Work Profile: $pkg ($label)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error during dynamic Work Profile sync: ${e.message}")
                }
            }
        }

        // Ensure system apps are excluded from Cloned Apps tab
        val removed = instances.entries.removeIf { entry ->
            try {
                val info = pm.getApplicationInfo(entry.value.packageName, 0)
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: Exception) {
                false
            }
        }
        if (removed) {
            savePersistedInstances()
        }

        return instances.values.map { inst ->
            val vfsUsage = vfs.getInstanceStorageUsage(inst.instanceId, inst.packageName)
            val apkSize = try {
                val appInfo = pm.getApplicationInfo(inst.packageName, 0)
                File(appInfo.sourceDir).length()
            } catch (e: Exception) {
                0L
            }
            val totalSize = if (vfsUsage > 0) (apkSize + vfsUsage) else (if (inst.storageUsageBytes > 0) inst.storageUsageBytes else apkSize)
            inst.copy(storageUsageBytes = totalSize)
        }
    }

    fun launchApp(instanceId: String, packageName: String): Boolean {
        val inst = instances[instanceId]
        val clonedPkg = inst?.clonedPackageName ?: packageName
        val isPrimary = inst?.isPrimary ?: true

        Log.d(TAG, "launchApp called for $packageName (clonedPkg=$clonedPkg, instanceId=$instanceId, isPrimary=$isPrimary)")

        // 0. Standalone Repackaged APK Clone (runs without Work Profile!)
        if (clonedPkg != packageName) {
            return try {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(clonedPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "Launched standalone cloned package: $clonedPkg")
                    true
                } else {
                    Log.w(TAG, "Standalone cloned package $clonedPkg is not installed on device")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching standalone cloned package $clonedPkg", e)
                false
            }
        }

        // 1. Primary Clone: Work Profile Isolation
        if (isPrimary) {
            val workProfile = getWorkProfileUserHandle()
            if (workProfile != null) {
                val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                if (launcherApps != null) {
                    try {
                        val activities = launcherApps.getActivityList(packageName, workProfile)
                        if (activities.isNotEmpty()) {
                            launcherApps.startMainActivity(activities[0].componentName, workProfile, null, null)
                            Log.d(TAG, "Successfully launched $packageName in Work Profile $workProfile!")
                            return true
                        } else {
                            Log.i(TAG, "Package $packageName not yet in Work Profile $workProfile; triggering instant install_and_launch...")
                            val dispatched = sendBridgeCommand("install_and_launch", packageName)
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    val actsAfter = launcherApps.getActivityList(packageName, workProfile)
                                    if (actsAfter.isNotEmpty()) {
                                        launcherApps.startMainActivity(actsAfter[0].componentName, workProfile, null, null)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Delayed launch check: ${e.message}")
                                }
                            }, 800)
                            return dispatched
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching $packageName in Work Profile", e)
                    }
                }
            } else {
                Log.w(TAG, "Work Profile Dual Space is not active yet when launching $packageName")
                return false
            }
        }

        // 2. Secondary Clones (Multi-Instance):
        // Never fall back to launching the original User 0 app; route to Work Profile if active
        val workProfile = getWorkProfileUserHandle()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (workProfile != null && launcherApps != null) {
            try {
                val activities = launcherApps.getActivityList(packageName, workProfile)
                if (activities.isNotEmpty()) {
                    launcherApps.startMainActivity(activities[0].componentName, workProfile, null, null)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Secondary Work Profile launch check failed: ${e.message}")
            }
        }
        Log.w(TAG, "Blocked launching User 0 original app for $packageName; isolated container required.")
        return false
    }

    fun createHomeScreenShortcut(instanceId: String, packageName: String, appName: String, badgeColor: String = "#FF6D00"): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!shortcutManager.isRequestPinShortcutSupported) {
                return false
            }

            return try {
                val pm = context.packageManager
                val targetPkg = instances[instanceId]?.clonedPackageName ?: packageName
                val appInfo = try {
                    pm.getApplicationInfo(targetPkg, 0)
                } catch (_: Exception) {
                    pm.getApplicationInfo(packageName, 0)
                }
                val originalDrawable = pm.getApplicationIcon(appInfo)
                val originalBitmap = drawableToBitmap(originalDrawable)
                val badgedBitmap = createBadgedBitmap(originalBitmap, badgeColor)

                val launchIntent = if (targetPkg != packageName && pm.getLaunchIntentForPackage(targetPkg) != null) {
                    pm.getLaunchIntentForPackage(targetPkg)!!.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                } else {
                    Intent(context, SandboxContainerActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        putExtra("sandbox_pkg", packageName)
                        putExtra("sandbox_instance_id", instanceId)
                        putExtra("is_sandbox_clone", true)
                    }
                }

                val shortcutId = "sb_${instanceId}_${packageName}"
                val shortcut = ShortcutInfo.Builder(context, shortcutId)
                    .setShortLabel(appName)
                    .setLongLabel("$appName [Sandbox]")
                    .setIcon(Icon.createWithBitmap(badgedBitmap))
                    .setIntent(launchIntent)
                    .build()

                shortcutManager.requestPinShortcut(shortcut, null)
                Log.d(TAG, "Shortcut request sent for $appName with badge color: $badgeColor")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create home screen shortcut", e)
                false
            }
        }
        return false
    }

    fun createBadgedBitmap(original: Bitmap, badgeColorHex: String): Bitmap {
        val width = original.width
        val height = original.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        canvas.drawBitmap(original, 0f, 0f, null)

        val badgeRadius = width * 0.22f
        val cx = badgeRadius + (width * 0.04f)
        val cy = height - badgeRadius - (height * 0.04f)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(badgeColorHex)
            style = Paint.Style.FILL
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = width * 0.035f
        }

        canvas.drawCircle(cx, cy, badgeRadius, badgePaint)
        canvas.drawCircle(cx, cy, badgeRadius, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = badgeRadius * 1.05f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val fontMetrics = textPaint.fontMetrics
        val textY = cy - (fontMetrics.descent + fontMetrics.ascent) / 2
        canvas.drawText("SB", cx, textY, textPaint)

        return output
    }

    fun clearCache(instanceId: String, packageName: String): Boolean {
        val cacheDir = vfs.getCacheDir(instanceId, packageName)
        return cacheDir.deleteRecursively().also {
            cacheDir.mkdirs()
        }
    }

    fun clearData(instanceId: String, packageName: String): Boolean {
        val success = vfs.wipeInstanceData(instanceId, packageName)
        vfs.getDataDir(instanceId, packageName)
        return success
    }

    fun deleteInstance(instanceId: String): Boolean {
        val inst = instances[instanceId] ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                val shortcutId = "sb_${instanceId}_${inst.packageName}"
                shortcutManager?.disableShortcuts(
                    listOf(shortcutId),
                    "This cloned app has been removed from Sandbox."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error disabling shortcut", e)
            }
        }

        if (inst.clonedPackageName != inst.packageName) {
            try {
                val uninstallIntent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${inst.clonedPackageName}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(uninstallIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Uninstall intent error for ${inst.clonedPackageName}: ${e.message}")
            }
        }

        vfs.wipeInstanceData(inst.instanceId, inst.packageName)
        instances.remove(instanceId)
        savePersistedInstances()
        return true
    }

    fun checkRealServicesStatus(): Map<String, Any> {
        val pm = context.packageManager
        var gmsInstalled = false
        var gmsVersion = "Not Available"
        try {
            val pkgInfo = pm.getPackageInfo("com.google.android.gms", 0)
            gmsInstalled = true
            gmsVersion = pkgInfo.versionName ?: "Active"
        } catch (e: Exception) {
            gmsInstalled = false
        }

        var playStoreInstalled = false
        try {
            pm.getPackageInfo("com.android.vending", 0)
            playStoreInstalled = true
        } catch (e: Exception) {
            playStoreInstalled = false
        }

        val isBillingBound = billingBridge.bindHostBilling()

        return mapOf(
            "gmsActive" to gmsInstalled,
            "gmsVersion" to gmsVersion,
            "playBillingActive" to (playStoreInstalled && isBillingBound),
            "provider" to if (playStoreInstalled) "Google Play Store (Phonesky)" else "Unavailable",
            "accountSyncActive" to gmsInstalled,
            "workProfileActive" to isWorkProfileActive()
        )
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun drawableToBase64(drawable: Drawable): String {
        return try {
            val bitmap = drawableToBitmap(drawable)
            val scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 85, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}
