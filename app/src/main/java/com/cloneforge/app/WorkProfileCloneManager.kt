package com.cloneforge.app

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Process
import android.os.UserManager
import java.io.File
import java.io.FileInputStream

object WorkProfileCloneManager {

    fun isRunningInWorkProfile(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isProfileOwnerApp(context.packageName) }.getOrDefault(false)
    }

    fun hasWorkProfile(context: Context): Boolean {
        if (isRunningInWorkProfile(context)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val crossProfile = context.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as? CrossProfileApps
            if (crossProfile != null && runCatching { crossProfile.targetUserProfiles.isNotEmpty() }.getOrDefault(false)) {
                return true
            }
        }
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
        return runCatching { um.userProfiles.size > 1 }.getOrDefault(false)
    }

    fun openCloneForgeInWorkProfile(context: Context): Result<Unit> = runCatching {
        val component = ComponentName(context, MainActivity::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val crossProfile = context.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as? CrossProfileApps
            val target = crossProfile?.targetUserProfiles?.firstOrNull()
            if (crossProfile != null && target != null) {
                crossProfile.startMainActivity(component, target)
                return@runCatching
            }
        }
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: error("UserManager is unavailable.")
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: error("LauncherApps is unavailable.")
        val myHandle = Process.myUserHandle()
        val otherHandle = um.userProfiles.firstOrNull { it != myHandle }
            ?: error("Work Space profile was not found. Please enable Work Space first.")
        launcherApps.startMainActivity(component, otherHandle, null, null)
    }

    fun provisionWorkProfile(context: Context): Result<Unit> = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: error("DevicePolicyManager is unavailable on this device.")
        if (!dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            if (hasWorkProfile(context)) {
                openCloneForgeInWorkProfile(context).getOrThrow()
                return@runCatching
            }
            error(
                "A Work Profile already exists on this phone (for example from Island or Shelter). " +
                    "Remove the existing Work Profile in Android Settings -> Accounts / Users first, " +
                    "or open CloneForge inside that Work Profile."
            )
        }
        val adminComponent = ComponentName(context, CloneForgeAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                adminComponent
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Installs the exact, unmodified Google Play APK (`base.apk` + all `split_config.*.apk` splits)
     * into the current Work Space using Android's multi-APK `PackageInstaller.Session`.
     *
     * Because zero bytes of the original APKs are modified, the original developer's
     * V1/V2/V3 signing certificate and original package name (`com.openai.chatgpt`, etc.)
     * are 100% preserved, so Google Play Integrity (`Preauth PlayIntegrity`), OAuth,
     * Google Sign-In, and JNI native libraries work without any issues.
     */
    fun installOriginalSignedApp(context: Context, source: ApkSource): Result<String> = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, CloneForgeAdminReceiver::class.java)

        val allApkFiles = buildList {
            add(source.sourceApk)
            addAll(source.splitApks.filter { it.exists() && it.isFile })
        }
        require(allApkFiles.isNotEmpty() && source.sourceApk.exists()) {
            "Original APK files for ${source.packageName} could not be accessed."
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(source.packageName)
        }

        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            allApkFiles.forEachIndexed { index, apkFile ->
                val entryName = if (index == 0) "base.apk" else "split_${index}_${apkFile.name}"
                session.openWrite(entryName, 0, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input ->
                        input.copyTo(out)
                    }
                    session.fsync(out)
                }
            }

            val callbackIntent = Intent(context, CloneInstallReceiver::class.java).apply {
                setPackage(context.packageName)
                putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, source.label)
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                pendingFlags
            )
            session.commit(pendingIntent.intentSender)
        }

        if (dpm != null && dpm.isProfileOwnerApp(context.packageName)) {
            runCatching { dpm.enableSystemApp(admin, source.packageName) }
        }

        "Installing original-signature clone of ${source.label} (${allApkFiles.size} APK file(s))…"
    }
}
