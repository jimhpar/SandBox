package com.blackbox.sandbox.core

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * SandboxContainerActivity
 * Isolated container host for cloned applications.
 * Directly launches the instance or alerts if deleted.
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

        // Verify that the instance still exists by checking the shared JSON file
        val instancesFile = File(filesDir, "sandbox_instances.json")
        if (instancesFile.exists()) {
            val json = instancesFile.readText()
            if (!json.contains(instanceId)) {
                Toast.makeText(this, "This cloned app has been deleted from Sandbox.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        launchIsolatedApp(packageName, instanceId)
    }

    private fun launchIsolatedApp(targetPkg: String, instanceId: String) {
        try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(targetPkg)

            if (launchIntent == null) {
                Toast.makeText(this, "Could not find launcher for $targetPkg", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            launchIntent.putExtra("is_sandbox_clone", true)
            launchIntent.putExtra("sandbox_instance_id", instanceId)
            launchIntent.putExtra("sandbox_isolated_vfs", true)

            startActivity(launchIntent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error launching target package: $targetPkg", e)
            Toast.makeText(this, "Failed to launch $targetPkg in Sandbox", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
