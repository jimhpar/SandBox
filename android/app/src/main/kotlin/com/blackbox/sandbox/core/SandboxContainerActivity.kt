package com.blackbox.sandbox.core

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * SandboxContainerActivity
 * Isolated container host for cloned applications.
 * Directly routes launch to SandboxManager to ensure Work Profile / isolated environment execution.
 */
class SandboxContainerActivity : Activity() {

    private val TAG = "SandboxContainer"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("sandbox_pkg")
        val instanceId = intent.getStringExtra("sandbox_instance_id")

        if (packageName.isNullOrEmpty() || instanceId.isNullOrEmpty()) {
            Toast.makeText(this, "Invalid Sandbox launch parameters.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val sandboxManager = SandboxManager.getInstance(applicationContext)
        val success = sandboxManager.launchApp(instanceId, packageName)
        if (!success) {
            Toast.makeText(this, "Failed to launch $packageName in isolated Sandbox.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
