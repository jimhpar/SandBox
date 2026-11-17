package com.blackbox.sandbox.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.android.apksig.ApkSigner
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ApkClonerEngine
 * True offline device-to-device APK repackaging & package renaming cloner:
 * - Zero internet download: reads base.apk + split APKs directly from device storage
 * - Patches AndroidManifest.xml (package, authorities, permissions, relative classes)
 * - Preserves uncompressed 4-byte aligned resources.arsc & .so libs for Android 11-14
 * - Signs with APK Signature Scheme v1/v2/v3 using bundled Sandbox keystore
 * - Installs via Android PackageInstaller session as a completely independent app
 */
class ApkClonerEngine(private val context: Context) {

    companion object {
        private const val TAG = "ApkClonerEngine"
        const val ACTION_INSTALL_STATUS = "com.blackbox.sandbox.INSTALL_CLONE_STATUS"
    }

    fun cloneAndInstallApk(
        originalPackage: String,
        newPackage: String,
        cloneSuffix: String,
        onProgress: (Double, String) -> Unit
    ): Boolean {
        val workDir = File(context.cacheDir, "clone_tmp_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onProgress(0.10, "Reading $originalPackage from device storage (Offline)...")
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(originalPackage, 0)

            val sourceApks = mutableListOf<Pair<String, File>>()
            sourceApks.add("base.apk" to File(appInfo.sourceDir))
            appInfo.splitSourceDirs?.forEachIndexed { idx, path ->
                val f = File(path)
                if (f.exists()) {
                    sourceApks.add("split_$idx.apk" to f)
                }
            }

            val signedApks = mutableListOf<Pair<String, File>>()
            val totalApks = sourceApks.size

            for ((idx, pair) in sourceApks.withIndex()) {
                val (apkName, srcFile) = pair
                val stepBase = 0.15 + (0.60 * idx / totalApks)
                onProgress(stepBase, "Copying & patching $apkName ($newPackage)...")

                val patchedFile = File(workDir, "patched_$apkName")
                repackAndPatchApk(srcFile, patchedFile, originalPackage, newPackage, cloneSuffix)

                val signProgress = 0.15 + (0.60 * (idx + 0.6) / totalApks)
                onProgress(signProgress, "Signing $apkName (APK v2/v3)...")

                val signedFile = File(workDir, "signed_$apkName")
                signApk(patchedFile, signedFile)
                patchedFile.delete()
                signedApks.add(apkName to signedFile)
            }

            onProgress(0.82, "Starting Android PackageInstaller for $newPackage...")
            installSession(signedApks, newPackage)
            onProgress(0.95, "Confirm installation prompt to finish cloning...")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clone $originalPackage -> $newPackage", e)
            return false
        } finally {
            // Schedule cleanup after installer reads session streams (session copies files immediately)
            workDir.deleteRecursively()
        }
    }

    private fun repackAndPatchApk(
        srcApk: File,
        dstApk: File,
        oldPackage: String,
        newPackage: String,
        cloneSuffix: String
    ) {
        ZipFile(srcApk).use { zipIn ->
            FileOutputStream(dstApk).use { fos ->
                BufferedOutputStream(fos, 64 * 1024).use { bos ->
                    val countingOut = object : java.io.FilterOutputStream(bos) {
                        var bytesWritten = 0L
                        override fun write(b: Int) {
                            out.write(b)
                            bytesWritten++
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            out.write(b, off, len)
                            bytesWritten += len
                        }
                    }
                    ZipOutputStream(countingOut).use { zipOut ->
                        val buffer = ByteArray(32 * 1024)
                        val entries = zipIn.entries()

                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name

                            // Strip old APK signatures
                            if (name.startsWith("META-INF/") &&
                                (name.endsWith(".SF") || name.endsWith(".RSA") ||
                                        name.endsWith(".DSA") || name.endsWith(".EC") ||
                                        name == "META-INF/MANIFEST.MF")
                            ) {
                                continue
                            }

                            if (name == "AndroidManifest.xml") {
                                val rawManifest = zipIn.getInputStream(entry).readBytes()
                                val patchedManifest = try {
                                    AxmlPatcher.patchManifest(rawManifest, oldPackage, newPackage, cloneSuffix)
                                } catch (e: Exception) {
                                    Log.w(TAG, "AXML patch fallback on ${srcApk.name}: ${e.message}")
                                    rawManifest
                                }
                                val outEntry = ZipEntry("AndroidManifest.xml")
                                outEntry.method = ZipEntry.DEFLATED
                                zipOut.putNextEntry(outEntry)
                                zipOut.write(patchedManifest)
                                zipOut.closeEntry()
                            } else if (entry.method == ZipEntry.STORED) {
                                // Preserve STORED entries (resources.arsc, .so) with exact 4-byte alignment
                                zipOut.flush()
                                val headerStart = countingOut.bytesWritten
                                val data = zipIn.getInputStream(entry).readBytes()
                                val outEntry = ZipEntry(name).apply {
                                    method = ZipEntry.STORED
                                    size = data.size.toLong()
                                    compressedSize = data.size.toLong()
                                    crc = CRC32().apply { update(data) }.value
                                }
                                val nameBytesLen = name.toByteArray(Charsets.UTF_8).size
                                val dataStartNoExtra = headerStart + 30L + nameBytesLen
                                val alignPad = ((4 - (dataStartNoExtra % 4)) % 4).toInt()
                                if (alignPad > 0) {
                                    outEntry.extra = ByteArray(alignPad)
                                }
                                zipOut.putNextEntry(outEntry)
                                zipOut.write(data)
                                zipOut.closeEntry()
                            } else {
                                val outEntry = ZipEntry(name).apply {
                                    method = ZipEntry.DEFLATED
                                }
                                zipOut.putNextEntry(outEntry)
                                BufferedInputStream(zipIn.getInputStream(entry)).use { bis ->
                                    var read: Int
                                    while (bis.read(buffer).also { read = it } != -1) {
                                        zipOut.write(buffer, 0, read)
                                    }
                                }
                                zipOut.closeEntry()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun signApk(unsignedApk: File, signedApk: File) {
        val ks = KeyStore.getInstance("PKCS12")
        context.assets.open("sandbox_signer.p12").use { stream ->
            ks.load(stream, "sandbox123".toCharArray())
        }
        val alias = "sandbox"
        val privateKey = ks.getKey(alias, "sandbox123".toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "SANDBOX",
            privateKey,
            listOf(cert)
        ).build()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(21)
            .build()

        signer.sign()
    }

    private fun installSession(apks: List<Pair<String, File>>, newPackage: String) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(newPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        try {
            val buffer = ByteArray(64 * 1024)
            for ((name, file) in apks) {
                session.openWrite(name, 0, file.length()).use { out ->
                    FileInputStream(file).use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                    session.fsync(out)
                }
            }

            val callbackIntent = Intent(ACTION_INSTALL_STATUS).apply {
                setPackage(context.packageName)
                putExtra("cloned_pkg", newPackage)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}
