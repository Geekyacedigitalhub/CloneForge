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
import java.io.File
import java.text.DateFormat
import java.util.Date
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
        CloneLibrary(this).apply {\n            cleanupStaleRecords()\n            cleanupOrphanedBuildWorkspaces()\n        }
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
    var libraryQuery by remember { mutableStateOf("") }
    var libraryFilter by remember { mutableStateOf("All") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    fun refreshLibrary() {
        cloneLibrary.cleanupStaleRecords()
        library = cloneLibrary.list()
    }

    LaunchedEffect(Unit) {
        onLibraryReady { refreshLibrary() }
    }
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete all clones?") },
            text = { Text("This deletes saved clone APKs and build workspaces. Installed clones are not uninstalled.") },
            confirmButton = {
                TextButton(onClick = {
                    library.forEach { cloneLibrary.delete(it) }
                    library = cloneLibrary.list()
                    showDeleteAllDialog = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("CloneForge") }) }) { padding ->
        if (selected == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Installed Apps", style = MaterialTheme.typography.headlineSmall)
                if (library.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Clone Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showDeleteAllDialog = true }) { Text("Delete all") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = libraryQuery,
                        onValueChange = { libraryQuery = it },
                        label = { Text("Search clones") },
                        placeholder = { Text("Name, package, or source") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Installed", "APK Ready").forEach { filter ->
                            FilterChip(
                                selected = libraryFilter == filter,
                                onClick = { libraryFilter = filter },
                                label = { Text(filter) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val totalLibraryBytes = library.sumOf { record ->
                        java.io.File(record.apkPath).length()
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Clone storage", style = MaterialTheme.typography.labelLarge)
                                Text(formatFileSize(totalLibraryBytes), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "${library.size} clone" + if (library.size == 1) "" else "s",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val filteredLibrary = library.filter { record ->
                        val installed = isPackageInstalled(context, record.clonePackage)
                        val matchesFilter = when (libraryFilter) {
                            "Installed" -> installed
                            "APK Ready" -> !installed
                            else -> true
                        }
                        val query = libraryQuery.trim().lowercase()
                        matchesFilter && (
                            query.isBlank() ||
                                record.cloneName.lowercase().contains(query) ||
                                record.clonePackage.lowercase().contains(query) ||
                                record.sourcePackage.lowercase().contains(query)
                        )
                    }
                    if (filteredLibrary.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    if (libraryQuery.isBlank() && libraryFilter == "All") {
                                        "No clones available yet."
                                    } else {
                                        "No clones match this search or filter."
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    if (libraryQuery.isBlank() && libraryFilter == "All") {
                                        "Build a clone from an installed app to see it here."
                                    } else {
                                        "Try another search term or switch the status filter."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(filteredLibrary) { record ->
                            val file = java.io.File(record.apkPath)
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(record.cloneName, style = MaterialTheme.typography.titleMedium)
                                        Text(record.clonePackage, style = MaterialTheme.typography.bodySmall)
                                        Text("Source: " + record.sourcePackage, style = MaterialTheme.typography.bodySmall)
                                        Text("APK: " + file.name, style = MaterialTheme.typography.bodySmall)
                                        Text("Size: " + formatFileSize(file.length()), style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "Created: " + DateFormat.getDateTimeInstance(
                                                DateFormat.MEDIUM,
                                                DateFormat.SHORT
                                            ).format(Date(record.createdAt)),
                                            style = MaterialTheme.typography.bodySmall
                                        )
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
    var progress by remember { mutableStateOf(0f) }
    var artifact by remember { mutableStateOf<CloneArtifact?>(null) }
    var preflight by remember { mutableStateOf<ClonePreflight?>(null) }
    var preflightError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(app.packageName) {
        preflight = null
        preflightError = null
        artifact = null
        status = null
        error = null
        progress = 0f

        val nextIndex = withContext(Dispatchers.IO) {
            CloneService(context).nextAvailableCloneIndexForUi(app.packageName)
        }
        packageSuffix = "clone$nextIndex"

        val info = runCatching {
            context.packageManager.getApplicationInfo(app.packageName, 0)
        }.getOrNull()

        if (info == null) {
            preflightError = "Unable to inspect the selected installed app."
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            ClonePreflightService().inspect(File(info.sourceDir), app.packageName)
        }.onSuccess {
            preflight = it
            preflightError = null
        }.onFailure {
            preflight = null
            preflightError = it.message ?: "Unable to inspect app compatibility."
        }
    }

    Column(modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack, enabled = !busy) { Text("← Back") }
        Spacer(Modifier.height(8.dp))
        Text("Clone Setup", style = MaterialTheme.typography.headlineMedium)
        Text("${app.label} • ${app.packageName}")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = cloneName,
            onValueChange = {
                cloneName = it
                error = null
                artifact = null
                status = null
                progress = 0f
            },
            label = { Text("Clone name") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = packageSuffix,
            onValueChange = {
                packageSuffix = it
                error = null
                artifact = null
                status = null
                progress = 0f
            },
            label = { Text("Package suffix") },
            supportingText = { Text("Example: clone1 → ${app.packageName}.clone1") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        Spacer(Modifier.height(8.dp))
        val previewPackage = PackageIdentity.validate(app.packageName, packageSuffix.trim()).getOrNull()
        val cloneLibrary = remember { CloneLibrary(context) }
        val packageTaken = previewPackage?.let { isPackageInstalled(context, it) || cloneLibrary.findByClonePackage(it) != null } == true
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Package preview", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        previewPackage == null -> "Enter a valid package suffix"
                        packageTaken -> "Package already in use: $previewPackage"
                        else -> previewPackage
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (previewPackage == null || packageTaken) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
        preflight?.let { report ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Compatibility check", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(report.compatibility.title)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${report.compatibility.issues.size} blocking issue(s) • ${report.compatibility.warnings.size} warning(s)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        report.compatibility.issues.forEach { issue ->
                            Text("• $issue", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(6.dp))
                        }
                        report.compatibility.warnings.forEach { warning ->
                            Text("• $warning", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
        preflightError?.let {
            Spacer(Modifier.height(12.dp))
            Text("Compatibility check unavailable: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        if (preflight == null && preflightError == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Running compatibility check…",
                style = MaterialTheme.typography.bodySmall
            )
        } else if (preflight?.compatibility?.supported == false) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Fix the blocking compatibility issues above before building this clone.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = {
                busy = true
                status = "Starting clone build…"
                progress = 0.02f
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        CloneService(context).buildInstalledAppClone(
                            sourcePackage = app.packageName,
                            cloneName = cloneName,
                            packageSuffix = packageSuffix,
                            onProgress = { stage ->
                                scope.launch {
                                    status = stage
                                    progress = when {
                                        stage.startsWith("Validating") -> 0.10f
                                        stage.startsWith("Inspecting") -> 0.25f
                                        stage.startsWith("Preparing") -> 0.40f
                                        stage.startsWith("Rebuilding") -> 0.65f
                                        stage.startsWith("Signing") -> 0.85f
                                        stage.startsWith("Saving") -> 0.95f
                                        else -> progress
                                    }
                                }
                            }
                        )
                    }
                    busy = false
                    result.onSuccess { built ->
                        artifact = built
                        progress = 1f
                        status = "Signed APK created: ${built.apkFile.name}
Package: ${built.clonePackage}
Path: ${built.apkFile.absolutePath}"
                    }.onFailure { throwable ->
                        progress = 0f
                        status = null
                        error = throwable.message ?: "Clone build failed."
                    }
                }
            },
            enabled = !busy &&
                cloneName.isNotBlank() &&
                packageSuffix.isNotBlank() &&
                previewPackage != null &&
                !packageTaken &&
                preflight?.compatibility?.supported == true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Building…" else "Build & Sign Clone")
        }

        artifact?.let { built ->
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Clone ready", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(built.cloneName, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Package: ${built.clonePackage}", style = MaterialTheme.typography.bodyMedium)
                    Text("APK: ${built.apkFile.name}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { openCloneInstaller(context, built.apkFile) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Install Clone")
                    }
                }
            }
        }

        if (busy || progress > 0f) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
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


private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (value >= 100.0) {
        String.format(java.util.Locale.getDefault(), "%.0f %s", value, units[unitIndex])
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unitIndex])
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
