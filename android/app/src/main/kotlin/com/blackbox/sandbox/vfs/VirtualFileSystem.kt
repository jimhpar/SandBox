package com.blackbox.sandbox.vfs

import android.content.Context
import java.io.File

/**
 * VirtualFileSystem (VFS)
 * Provides isolated storage roots, redirecting app data, cache, databases, and
 * external files for each cloned instance inside Sandbox v2.0.1.
 * Publisher: Blackbox THC
 */
class VirtualFileSystem(private val context: Context) {

    private val baseSandboxDir: File = File(context.filesDir, "sandbox_storage")

    init {
        if (!baseSandboxDir.exists()) {
            baseSandboxDir.mkdirs()
        }
    }

    /**
     * Returns the isolated root directory for a specific cloned instance.
     */
    fun getInstanceDir(instanceId: String, packageName: String): File {
        val dir = File(baseSandboxDir, "inst_${instanceId}/$packageName")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolves virtual data path: /data/data/<pkg> -> sandbox_storage/inst_<id>/<pkg>/data
     */
    fun getDataDir(instanceId: String, packageName: String): File {
        val dataDir = File(getInstanceDir(instanceId, packageName), "data")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    /**
     * Resolves virtual cache path: /data/data/<pkg>/cache
     */
    fun getCacheDir(instanceId: String, packageName: String): File {
        val cacheDir = File(getDataDir(instanceId, packageName), "cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    /**
     * Resolves virtual files path: /data/data/<pkg>/files
     */
    fun getFilesDir(instanceId: String, packageName: String): File {
        val filesDir = File(getDataDir(instanceId, packageName), "files")
        if (!filesDir.exists()) {
            filesDir.mkdirs()
        }
        return filesDir
    }

    /**
     * Cleans up all data for a specific instance.
     */
    fun wipeInstanceData(instanceId: String, packageName: String): Boolean {
        val instDir = File(baseSandboxDir, "inst_${instanceId}/$packageName")
        return if (instDir.exists()) {
            instDir.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Calculates the disk usage of an instance in bytes.
     */
    fun getInstanceStorageUsage(instanceId: String, packageName: String): Long {
        val instDir = File(baseSandboxDir, "inst_${instanceId}/$packageName")
        if (!instDir.exists()) return 0L
        return getFolderSize(instDir)
    }

    private fun getFolderSize(dir: File): Long {
        var size: Long = 0
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return size
    }
}
