package com.cloneforge.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val label: String, val packageName: String, val versionName: String)

class MainActivity : ComponentActivity() {

    private var refreshLibrary: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        refreshLibrary?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloneLibrary(this).cleanupStaleRecords()
        val apps = loadLaunchableApps()
        setContent {
            MaterialTheme {
                CloneForgeApp(
                    apps = apps,
                    onLibraryReady = { refreshLibrary = it }
                )
            }
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { resolve ->
                val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (info.packageName == packageName) return@mapNotNull null
                val version = runCatching { pm.getPackageInfo(info.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
                InstalledApp(pm.getApplicationLabel(info).toString(), info.packageName, version)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}

@Composable
private fun CloneForgeApp(
    apps: List<InstalledApp>,
    onLibraryReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    val cloneLibrary = remember { CloneLibrary(context) }
    var library by remember { mutableStateOf(cloneLibrary.list()) }

    fun refreshLibrary() {
        cloneLibrary.cleanupStaleRecords()
        library = cloneLibrary.list()
    }

    LaunchedEffect(Unit) {
        onLibraryReady { refreshLibrary() }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("CloneForge") }) }) { padding ->
        if (selected == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Installed Apps", style = MaterialTheme.typography.headlineSmall)
                if (library.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("Clone Library", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(library) { record ->
                            val file = java.io.File(record.apkPath)
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(record.cloneName, style = MaterialTheme.typography.titleMedium)
                                        Text(record.clonePackage, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            if (isPackageInstalled(context, record.clonePackage)) "Installed" else "APK ready",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isPackageInstalled(context, record.clonePackage)) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                    TextButton(onClick = { openCloneInstaller(context, file) }) {
                                        Text(
                                            if (isPackageInstalled(context, record.clonePackage)) "Update" else "Install"
                                        )
                                    }
                                    TextButton(onClick = {
                                        cloneLibrary.delete(record)
                                        library = cloneLibrary.list()
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Select an app to prepare it for cloning.")
                Spacer(Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(apps) { app ->
                        Card(Modifier.fillMaxWidth().clickable { selected = app }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                    Text("Version ${app.versionName}", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { selected = app }) { Text("Clone") }
                            }
                        }
                    }
                }
            }
        } else {
            CloneSetupScreen(selected!!, Modifier.padding(padding)) { selected = null }
        }
    }
}

@Composable
private fun CloneSetupScreen(app: InstalledApp, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cloneName by remember { mutableStateOf("${app.label} Clone") }
    var packageSuffix by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var artifact by remember { mutableStateOf<CloneArtifact?>(null) }

    LaunchedEffect(app.packageName) {
        val nextIndex = withContext(Dispatchers.IO) {
            CloneService(context).nextAvailableCloneIndexForUi(app.packageName)
        }
        packageSuffix = "clone$nextIndex"
    }

    Column(modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack, enabled = !busy) { Text("← Back") }
        Spacer(Modifier.height(8.dp))
        Text("Clone Setup", style = MaterialTheme.typography.headlineMedium)
        Text("${app.label} • ${app.packageName}")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = cloneName,
            onValueChange = { cloneName = it; error = null },
            label = { Text("Clone name") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = packageSuffix,
            onValueChange = { packageSuffix = it; error = null },
            label = { Text("Package suffix") },
            supportingText = { Text("Example: clone1 → ${app.packageName}.clone1") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                busy = true
                status = "Building and signing clone…"
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        CloneService(context).buildInstalledAppClone(
                            sourcePackage = app.packageName,
                            cloneName = cloneName,
                            packageSuffix = packageSuffix
                        )
                    }
                    busy = false
                    result.onSuccess { built ->
                        artifact = built
                        status = "Signed APK created: ${built.apkFile.name}\nPackage: ${built.clonePackage}\nPath: ${built.apkFile.absolutePath}"
                    }.onFailure { throwable ->
                        status = null
                        error = throwable.message ?: "Clone build failed."
                    }
                }
            },
            enabled = !busy && cloneName.isNotBlank() && packageSuffix.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Building…" else "Build & Sign Clone")
        }

        artifact?.let { built ->
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { openCloneInstaller(context, built.apkFile) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Install Clone") }
        }

        status?.let {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "The APK was rebuilt and signed with CloneForge’s on-device signing key.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun isPackageInstalled(context: android.content.Context, packageName: String): Boolean =
    runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

private fun openCloneInstaller(context: android.content.Context, apkFile: java.io.File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(settingsIntent)
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
}
