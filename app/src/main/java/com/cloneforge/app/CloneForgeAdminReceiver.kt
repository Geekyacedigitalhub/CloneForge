package com.cloneforge.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class CloneForgeAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, CloneForgeAdminReceiver::class.java)
        if (dpm != null) {
            runCatching {
                dpm.setProfileName(admin, "CloneForge Workspace")
                dpm.setProfileEnabled(admin)
                dpm.enableSystemApp(admin, context.packageName)
            }
        }
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(launch) }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(context, CloneForgeAdminReceiver::class.java)
        if (dpm.isProfileOwnerApp(context.packageName)) {
            runCatching {
                dpm.setProfileName(admin, "CloneForge Workspace")
                dpm.setProfileEnabled(admin)
            }
        }
    }
}
