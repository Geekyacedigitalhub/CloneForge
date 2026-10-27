package com.cloneforge.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class CloneInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "App"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(
                    context,
                    "$pkgName cloned into Work Space with original signature!",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                val detail = message?.takeIf { it.isNotBlank() } ?: "status=$status"
                Toast.makeText(
                    context,
                    "Install failed ($detail)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
