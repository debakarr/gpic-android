package com.gpic.android.ui.screens.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.data.dji.DjiFile
import com.gpic.android.data.dji.DjiScanner
import com.gpic.android.data.progress.FileProgress
import com.gpic.android.data.stats.LiveStats
import com.gpic.android.data.stats.SystemStatsCollector
import com.gpic.android.data.upload.UploadManager
import com.gpic.android.data.upload.UploadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val djiUri: Uri? = null,
    val djiFiles: List<DjiFile> = emptyList(),
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val usbStatus: String = "Plug DJI Action 4 via USB-C",
    val usbConnected: Boolean = false,
    val authEmail: String? = null,
    val hasAuth: Boolean = false,
    val uploadRunning: Boolean = false,
    val progressMap: Map<String, FileProgress> = emptyMap(),
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0L,
    val systemStats: LiveStats? = null,
)

class HomeViewModel(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val scanner: DjiScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var uploadManager: UploadManager? = null
    private var statsJob: kotlinx.coroutines.Job? = null

    init {
        refreshAuth()
        refreshUsb()
        // restore last DJI uri if saved
        val saved = context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE).getString("last_dji_uri", null)
        saved?.let {
            try { _state.value = _state.value.copy(djiUri = Uri.parse(it)) } catch (e: Exception) {}
        }
    }

    fun refreshAuth() {
        val cred = credentialStore.getActiveCredential()
        _state.value = _state.value.copy(
            authEmail = cred?.email,
            hasAuth = cred != null
        )
    }

    fun refreshUsb() {
        val connected = scanner.checkUsbConnected()
        val summary = scanner.usbDeviceSummary()
        _state.value = _state.value.copy(usbConnected = connected, usbStatus = summary)
    }

    fun setDjiUri(uri: Uri) {
        scanner.takePersistablePermission(uri)
        context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE).edit().putString("last_dji_uri", uri.toString()).apply()
        _state.value = _state.value.copy(djiUri = uri)
        scanDji(uri)
    }

    fun scanDji(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, scanError = null)
            try {
                val files = scanner.scanViaSaf(uri)
                _state.value = _state.value.copy(
                    djiFiles = files,
                    totalFiles = files.size,
                    totalBytes = files.sumOf { it.sizeBytes },
                    isScanning = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isScanning = false, scanError = e.message)
            }
        }
    }

    fun rescan() {
        _state.value.djiUri?.let { scanDji(it) }
    }

    fun startUpload(threads: Int = 0, force: Boolean = false, deleteAfter: Boolean = false) {
        val files = _state.value.djiFiles
        if (files.isEmpty()) return
        val cred = credentialStore.getActiveCredential() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(uploadRunning = true, progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0)
            val mgr = UploadManager(context, cred, threads = threads, force = force, deleteAfter = deleteAfter)
            uploadManager = mgr
            // Collect progress
            val job = launch {
                mgr.progressFlow.collect { map ->
                    val tracker = mgr.progress
                    _state.value = _state.value.copy(
                        progressMap = map,
                        completed = tracker.completed,
                        failed = tracker.failed,
                        skipped = tracker.skipped,
                    )
                }
            }
            // Morphe-style live CPU/RAM/network while uploading
            statsJob?.cancel()
            statsJob = launch {
                val collector = SystemStatsCollector(
                    context,
                    uploadedBytesProvider = {
                        try { mgr.progress.files.values.sumOf { it.bytesUploaded } } catch (_: Exception) { 0L }
                    }
                )
                collector.statsFlow().collect { s ->
                    _state.value = _state.value.copy(systemStats = s)
                }
            }
            try {
                mgr.start(files)
            } finally {
                job.cancel()
                statsJob?.cancel()
                _state.value = _state.value.copy(uploadRunning = false)
            }
        }
    }

    fun cancelUpload() {
        uploadManager?.cancel()
        statsJob?.cancel()
        _state.value = _state.value.copy(uploadRunning = false)
    }
}
