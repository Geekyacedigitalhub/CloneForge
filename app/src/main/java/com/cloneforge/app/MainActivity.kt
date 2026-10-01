package com.cloneforge.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
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

data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String,
    val icon: Bitmap
)

class MainActivity : ComponentActivity() {

    private var refreshLibrary: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        refreshLibrary?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloneLibrary(this).apply {
            cleanupStaleRecords()
            cleanupOrphanedBuildWorkspaces()
        }
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
                InstalledApp(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    versionName = version,
                    icon = drawableToBitmap(info.loadIcon(pm))
                )
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
    var selectedClone by remember { mutableStateOf<CloneRecord?>(null) }
    val cloneLibrary = remember { CloneLibrary(context) }
    var library by remember { mutableStateOf(cloneLibrary.list()) }
    var libraryQuery by remember { mutableStateOf("") }
    var appQuery by remember { mutableStateOf("") }
    var libraryFilter by remember { mutableStateOf("All") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteFeedback by remember { mutableStateOf<String?>(null) }
    var installedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importBusy by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }\n    var showRestoreDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { cloneLibrary.importApk(uri) }
            }
            importBusy = false
            result.onSuccess { imported ->
                cloneLibrary.cleanupStaleRecords()
                library = cloneLibrary.list()
                installedPackages = library.mapNotNull { record ->
                    if (isPackageInstalled(context, record.clonePackage)) record.clonePackage else null
                }.toSet()
                deleteFeedback = if (imported.replacedExisting) {
                    "Updated imported clone " + imported.record.cloneName + " (" + imported.record.clonePackage + ")."
                } else {
                    "Imported " + imported.record.cloneName + " (" + imported.record.clonePackage + ") into the clone library."
                }
            }.onFailure { throwable ->
                deleteFeedback = "Import failed: " + (throwable.message ?: "The selected file could not be imported.")
            }
        }
    }

    val backupCreateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { cloneLibrary.exportBackup(uri) }
            }
            backupBusy = false
            result.onSuccess { count ->
                deleteFeedback = "Backed up $count clone(s) to the selected file."
            }.onFailure { throwable ->
                deleteFeedback = "Backup failed: " + (throwable.message ?: "The backup could not be created.")
            }
        }
    }
    val backupRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { cloneLibrary.restoreBackup(uri) }
            }
            backupBusy = false
            result.onSuccess { count ->
                cloneLibrary.cleanupStaleRecords()
                library = cloneLibrary.list()
                installedPackages = library.mapNotNull { record ->
                    if (isPackageInstalled(context, record.clonePackage)) record.clonePackage else null
                }.toSet()
                deleteFeedback = "Restored $count clone(s) from the selected backup."
            }.onFailure { throwable ->
                deleteFeedback = "Restore failed: " + (throwable.message ?: "The backup could not be restored.")
            }
        }
    }

    fun refreshLibrary() {
        cloneLibrary.cleanupStaleRecords()
        library = cloneLibrary.list()
        installedPackages = library.mapNotNull { record ->
            if (isPackageInstalled(context, record.clonePackage)) record.clonePackage else null
        }.toSet()
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
                    val removedCount = library.size
                    val reclaimedBytes = library.sumOf { java.io.File(it.apkPath).length() }
                    cloneLibrary.deleteAll()
                    library = cloneLibrary.list()
                    deleteFeedback = if (removedCount == 0) {
                        "No clone APKs were available to delete."
                    } else {
                        "Removed $removedCount clone(s) and reclaimed " + formatFileSize(reclaimedBytes) + "."
                    }
                    showDeleteAllDialog = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("CloneForge") }) }) { padding ->
        if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { if (!backupBusy) showRestoreDialog = false },
            title = { Text("Restore CloneForge backup?") },
            text = {
                Text(
                    "The backup will be fully validated before changes are made. " +
                        "If it contains a clone with the same package as an existing clone, " +
                        "the existing clone will be replaced."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        backupRestoreLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*")
                        )
                    },
                    enabled = !backupBusy
                ) {
                    Text("Choose Backup")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreDialog = false },
                    enabled = !backupBusy
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedClone != null) {
            CloneDetailsScreen(
                record = selectedClone!!,
                installed = selectedClone!!.clonePackage in installedPackages,
                contentPadding = padding,
                onBack = { selectedClone = null },
                onDelete = {
                    cloneLibrary.delete(selectedClone!!)
                    selectedClone = null
                    refreshLibrary()
                }
            )
        } else if (selected == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Installed Apps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { importLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
                        enabled = !importBusy && !backupBusy
                    ) {
                        Text(if (importBusy) "Importing…" else "Import APK")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            backupCreateLauncher.launch("CloneForge-backup.zip")
                        },
                        enabled = !backupBusy && !importBusy
                    ) {
                        Text(if (backupBusy) "Working…" else "Backup")
                    }
                    TextButton(
            onClick = { showRestoreDialog = true },
            enabled = !backupBusy && !importBusy
        ) {
            Text("Restore")
        },
                        enabled = !backupBusy && !importBusy
                    ) {
                        Text("Restore")
                    }
                }
                deleteFeedback?.let {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(it, Modifier.padding(14.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    label = { Text("Search installed apps") },
                    placeholder = { Text("App name or package") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val filteredApps = apps.filter { app ->
                    val query = appQuery.trim().lowercase()
                    query.isBlank() || app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query)
                }
                if (library.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Clone Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refreshLibrary() }) { Text("Refresh") }
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
                        val installed = record.clonePackage in installedPackages
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
                            val sourceIcon = remember(record.sourcePackage) {
                                loadApplicationIcon(context, record.sourcePackage)
                            }
                            Card(Modifier.fillMaxWidth().clickable { selectedClone = record }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    sourceIcon?.let { icon ->
                                        Image(
                                            bitmap = icon.asImageBitmap(),
                                            contentDescription = record.cloneName,
                                            modifier = Modifier.size(52.dp)
                                        )
                                        Spacer(Modifier.width(14.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(record.cloneName, style = MaterialTheme.typography.titleMedium)
                                        Text(record.clonePackage, style = MaterialTheme.typography.bodySmall)
                                        Text("Source: " + record.sourcePackage, style = MaterialTheme.typography.bodySmall)
                                        Text("APK: " + file.name, style = MaterialTheme.typography.bodySmall)
                                        Text("Size: " + formatFileSize(file.length()), style = MaterialTheme.typography.bodySmall)
                                        if (record.sha256.isNotBlank()) {
                                            Text("SHA-256: " + record.sha256.take(16) + "…", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(
                                            "Created: " + DateFormat.getDateTimeInstance(
                                                DateFormat.MEDIUM,
                                                DateFormat.SHORT
                                            ).format(Date(record.createdAt)),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            if (record.clonePackage in installedPackages) "Installed" else "APK ready",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (record.clonePackage in installedPackages) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                    TextButton(onClick = { openCloneInstaller(context, file) }) {
                                        Text(
                                            if (record.clonePackage in installedPackages) "Update" else "Install"
                                        )
                                    }
                                    TextButton(onClick = { shareCloneApk(context, file, record.cloneName, record.sourcePackage, record.clonePackage) }) { Text("Share") }
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
                if (filteredApps.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (appQuery.isBlank()) "No launchable apps found." else "No apps match your search.",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (appQuery.isBlank()) {
                                    "CloneForge could not find any other launchable apps on this device."
                                } else {
                                    "Try another app name or package name."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filteredApps) { app ->
                            Card(Modifier.fillMaxWidth().clickable { selected = app }) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        bitmap = app.icon.asImageBitmap(),
                                        contentDescription = app.label,
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
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
            }
        } else {
            CloneSetupScreen(selected!!, Modifier.padding(padding)) { selected = null }
        }
    }
}

@Composable
private fun CloneDetailsScreen(
    record: CloneRecord,
    installed: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val file = remember(record.apkPath) { File(record.apkPath) }
    val sourceIcon = remember(record.sourcePackage) {
        loadApplicationIcon(context, record.sourcePackage)
    }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verification by remember(record.apkPath) { mutableStateOf<ApkFileVerification?>(null) }
    val currentSha256 = remember(record.apkPath, record.sha256) {
        if (record.sha256.isBlank() && file.exists()) sha256(file) else record.sha256
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete clone?") },
            text = { Text("This removes the saved APK and its local build workspace. An installed clone is not uninstalled.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack) { Text("← Back to Clone Library") }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            sourceIcon?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = record.cloneName,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(record.cloneName, style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (installed) "Installed" else "APK ready",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (installed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Clone information", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                DetailRow("Clone package", record.clonePackage)
                DetailRow("Source package", record.sourcePackage)
                DetailRow("APK file", file.name)
                DetailRow("APK size", formatFileSize(file.length()))
                DetailRow(
                    "Created",
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                    ).format(Date(record.createdAt))
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Integrity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("SHA-256", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    currentSha256.ifBlank { "SHA-256 unavailable" },
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (currentSha256.isNotBlank()) {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("CloneForge SHA-256", currentSha256)
                                )
                            }
                        },
                        enabled = currentSha256.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy SHA-256")
                    }
                    OutlinedButton(
                        onClick = {
                            verifying = true
                            verification = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ApkFileVerifier().verify(file)
                                }
                                verification = result
                                verifying = false
                            }
                        },
                        enabled = !verifying && file.exists(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (verifying) "Checking…" else "Verify APK")
                    }
                }
                verification?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    val verificationText = if (result.verified) {
                        "APK structure verified"
                    } else {
                        "APK verification failed"
                    }
                    Text(
                        verificationText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (result.verified) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        buildString {
                            append("Manifest: ")
                            append(if (result.hasManifest) "yes" else "no")
                            append(" • DEX: ")
                            append(result.dexCount)
                            append(" • resources.arsc: ")
                            append(if (result.hasResources) "yes" else "no")
                            result.manifestPackage?.let {
                                append(" • package: ")
                                append(it)
                            }
                            result.error?.let {
                                append(" • ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use this fingerprint to confirm that a shared APK matches the file received by your team.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { openCloneInstaller(context, file) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (installed) "Update Installed Clone" else "Install Clone")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                shareCloneApk(
                    context,
                    file,
                    record.cloneName,
                    record.sourcePackage,
                    record.clonePackage
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share APK")
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Delete Clone", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
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

    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack, enabled = !busy) { Text("← Back") }
        Spacer(Modifier.height(8.dp))
        Text("Clone Setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Version ${app.versionName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
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
                Text("Package identity", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Original",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clone",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when {
                        previewPackage == null -> "Enter a valid package suffix"
                        else -> previewPackage
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (previewPackage == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.height(8.dp))
                when {
                    previewPackage == null -> {
                        Text(
                            "Use a valid suffix such as clone1.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    packageTaken -> {
                        Text(
                            "This clone package is already in use. Choose a different suffix.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {
                        Text(
                            "Package is available for this clone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        preflight?.let { report ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val compatibility = report.compatibility
                    Text("Compatibility check", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (compatibility.supported) {
                            if (!compatibility.hasWarnings) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Text(
                            when {
                                !compatibility.supported -> "BLOCKED — fix required"
                                compatibility.hasWarnings -> "READY WITH WARNINGS"
                                else -> "READY TO CLONE"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                !compatibility.supported -> MaterialTheme.colorScheme.onErrorContainer
                                compatibility.hasWarnings -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(compatibility.title)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${compatibility.issues.size} blocking issue(s) • ${compatibility.warnings.size} warning(s)" + if (compatibility.packageReferenceCount > 0) " • ${compatibility.packageReferenceCount} raw package reference(s)" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        compatibility.issues.forEach { issue ->
                            Text("• $issue", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(6.dp))
                        }
                        compatibility.warnings.forEach { warning ->
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
                                        stage.startsWith("Verifying") -> 0.92f
                                        stage.startsWith("Saving") -> 0.97f
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
                    Text("SHA-256: ${sha256(built.apkFile)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { openCloneInstaller(context, built.apkFile) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Install")
                        }
                        OutlinedButton(
                            onClick = { shareCloneApk(context, built.apkFile, built.cloneName, app.packageName, built.clonePackage) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Share APK")
                        }
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


private fun loadApplicationIcon(context: android.content.Context, packageName: String): Bitmap? =
    runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        drawableToBitmap(info.loadIcon(context.packageManager))
    }.getOrNull()

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
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

private fun shareCloneApk(
    context: android.content.Context,
    apkFile: java.io.File,
    cloneName: String,
    sourcePackage: String,
    clonePackage: String
) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val shareText = buildString {
        appendLine("CloneForge APK")
        appendLine("Clone: $cloneName")
        appendLine("Source package: $sourcePackage")
        appendLine("Clone package: $clonePackage")
        appendLine("APK: ${apkFile.name}")
        appendLine("Size: ${formatFileSize(apkFile.length())}")
        append("SHA-256: ${sha256(apkFile)}")
    }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Clone APK"))
}

private fun sha256(file: java.io.File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

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
