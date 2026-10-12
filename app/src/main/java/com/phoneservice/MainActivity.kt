package com.phoneservice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phoneservice.call.CallEntry
import com.phoneservice.call.CallSyncWorker
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: CallLogViewModel by viewModels()
    private val prefs by lazy { getSharedPreferences(prefsName, MODE_PRIVATE) }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.onPermissionGranted()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        prefs.edit().putBoolean(askedKey, true).apply()
        if (!granted) return@registerForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onPermissionGranted()
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneServiceTheme {
                val state by viewModel.state.collectAsState()
                val notice by viewModel.notice.collectAsState()
                CallLogScreen(
                    state = state,
                    notice = notice,
                    onAllow = ::requestCallLogAccess,
                    onRefresh = viewModel::refresh,
                    onBattery = ::requestUnrestrictedBattery,
                    onExport = ::exportArchive,
                    onNoticeShown = viewModel::consumeNotice,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResume(permissionNeedsSettings())
    }

    private fun requestCallLogAccess() {
        if (permissionNeedsSettings()) {
            openPermissionSettings()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    private fun permissionNeedsSettings(): Boolean {
        val asked = prefs.getBoolean(askedKey, false)
        return asked &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG) &&
            checkSelfPermission(Manifest.permission.READ_CALL_LOG) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private fun requestUnrestrictedBattery() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(request)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                viewModel.showNotice(getString(R.string.battery_unavailable))
            }
        }
    }

    private fun exportArchive() {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        exportLauncher.launch("calls-$stamp.csv")
    }

    companion object {
        private const val prefsName = "phone_service_ui"
        private const val askedKey = "asked_call_log"
    }
}

@Composable
private fun PhoneServiceTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val night = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && night -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        night -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallLogScreen(
    state: CallLogUiState,
    notice: Notice?,
    onAllow: () -> Unit,
    onRefresh: () -> Unit,
    onBattery: () -> Unit,
    onExport: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice?.id) {
        val text = notice?.text ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onNoticeShown()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.permitted) {
                            stringResource(R.string.status_ready, CallSyncWorker.syncIntervalHours)
                        } else {
                            stringResource(R.string.status_need_permission)
                        },
                    )
                    Text(archiveStatus(state))
                    if (!state.permitted) {
                        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    if (state.needsSettings) R.string.allow_settings else R.string.allow,
                                ),
                            )
                        }
                    } else {
                        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.refresh))
                        }
                        if (state.ignoringBattery) {
                            Text(stringResource(R.string.battery_done))
                        } else {
                            Text(stringResource(R.string.battery_reason))
                            Button(onClick = onBattery, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.battery))
                            }
                        }
                    }
                    if (state.calls.isNotEmpty()) {
                        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.export))
                        }
                    }
                    if (state.calls.isEmpty() && state.permitted) {
                        Text(
                            stringResource(if (state.syncing) R.string.syncing else R.string.empty),
                        )
                    }
                }
            }
            items(state.calls, key = { it.archiveKey }) { entry ->
                CallRow(entry)
            }
        }
    }
}

@Composable
private fun CallRow(entry: CallEntry) {
    Column {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(callTitle(entry), style = MaterialTheme.typography.titleMedium)
            if (entry.name.isNotBlank() && entry.number.isNotBlank()) {
                Text(entry.number, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                callDetails(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun archiveStatus(state: CallLogUiState): String {
    val count = state.calls.size
    return when {
        state.savedAtMillis > 0L -> pluralStringResource(
            R.plurals.last_sync,
            count,
            formatTime(state.savedAtMillis),
            count,
        )
        count > 0 -> pluralStringResource(R.plurals.stored_count, count, count)
        else -> stringResource(R.string.never_synced)
    }
}

@Composable
private fun callTitle(entry: CallEntry): String = when {
    entry.name.isNotBlank() -> entry.name
    entry.number.isNotBlank() -> entry.number
    else -> stringResource(R.string.private_number)
}

@Composable
private fun callDetails(entry: CallEntry): String {
    val duration = entry.durationLabel()
    return listOfNotNull(
        entry.typeLabel(),
        duration.ifBlank { null },
        formatTime(entry.timeMillis),
    ).joinToString(" · ")
}

private fun formatTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMillis))
