package com.cloneforge.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val label: String, val packageName: String, val versionName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apps = loadLaunchableApps()
        setContent { MaterialTheme { CloneForgeApp(apps) } }
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
private fun CloneForgeApp(apps: List<InstalledApp>) {
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("CloneForge") }) }) { padding ->
        if (selected == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Installed Apps", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text("Select an app to prepare it for cloning.")
                Spacer(Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    var packageSuffix by remember { mutableStateOf("clone1") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
                status = "Building unsigned clone…"
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        CloneService(context).buildInstalledAppClone(
                            sourcePackage = app.packageName,
                            cloneName = cloneName,
                            cloneIndex = 1,
                            packageSuffix = packageSuffix
                        )
                    }
                    busy = false
                    result.onSuccess { artifact ->
                        status = "Unsigned APK created: ${artifact.apkFile.name}\nPackage: ${artifact.clonePackage}\nPath: ${artifact.apkFile.absolutePath}"
                    }.onFailure { throwable ->
                        status = null
                        error = throwable.message ?: "Clone build failed."
                    }
                }
            },
            enabled = !busy && cloneName.isNotBlank() && packageSuffix.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Building…" else "Build Unsigned Clone")
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
            "This stage rebuilds the APK without a signing certificate. The next engine stage is APK signing before installation.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}