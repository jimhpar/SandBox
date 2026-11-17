package com.blackbox.sandbox

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

class SandboxApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isHideRoot(): Boolean = true
            })
        } catch (e: Throwable) {
            Log.e("SandboxApplication", "BlackBoxCore.doAttachBaseContext failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (e: Throwable) {
            Log.e("SandboxApplication", "BlackBoxCore.doCreate failed", e)
        }
    }
}
