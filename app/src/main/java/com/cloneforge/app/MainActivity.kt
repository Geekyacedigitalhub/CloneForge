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
import androidx.compose.ui.unit.dp

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
    var cloneName by remember { mutableStateOf("${app.label} Clone") }
    var packageSuffix by remember { mutableStateOf(".clone1") }
    Column(modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))
        Text("Clone Setup", style = MaterialTheme.typography.headlineMedium)
        Text("${app.label} • ${app.packageName}")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(cloneName, { cloneName = it }, label = { Text("Clone name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(packageSuffix, { packageSuffix = it }, label = { Text("Package suffix") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Prepare Clone") }
        Spacer(Modifier.height(12.dp))
        Text("Foundation stage: app discovery and clone configuration. APK transformation is the next engine stage.", style = MaterialTheme.typography.bodySmall)
    }
}
