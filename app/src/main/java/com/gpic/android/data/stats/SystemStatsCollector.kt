package com.gpic.android.data.stats

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.RandomAccessFile

/**
 * Collects live CPU / RAM / network stats every [intervalMs].
 *
 * - CPU: /proc/stat delta (device overall) with app-CPU fallback via getElapsedCpuTime.
 *   /proc/stat is unreadable on some devices (SELinux) which previously stuck UI at 0%.
 * - RAM/storage: ActivityManager.MemoryInfo + StatFs(filesDir) — same as Morphe DeviceStats.
 * - Disk: volume used/total + app cacheDir/filesDir du (temp upload copies).
 * - App RAM: ActivityManager.getProcessMemoryInfo(pid) PSS.
 * - Network: TrafficStats total + UID deltas → B/s.
 * - Upload throughput: supplied via [uploadedBytesProvider] (sum of FileProgress.bytesUploaded).
 */
class SystemStatsCollector(
    private val context: Context,
    private val intervalMs: Long = 1000L,
    private val historySize: Int = 60,
    private val uploadedBytesProvider: (() -> Long)? = null,
) {
    fun statsFlow(): Flow<LiveStats> = flow {
        var prevCpuTotal = 0L
        var prevCpuIdle = 0L
        var firstCpu = true
        var prevAppCpuTime = android.os.Process.getElapsedCpuTime()
        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        var prevRx = TrafficStats.getTotalRxBytes()
        var prevTx = TrafficStats.getTotalTxBytes()
        val uid = Process.myUid()
        var prevAppRx = TrafficStats.getUidRxBytes(uid)
        var prevAppTx = TrafficStats.getUidTxBytes(uid)
        var prevTime = System.currentTimeMillis()
        var prevUploaded = uploadedBytesProvider?.invoke() ?: 0L

        val cpuHist = ArrayDeque<Float>()
        val memHist = ArrayDeque<Float>()
        val txHist = ArrayDeque<Long>()
        val rxHist = ArrayDeque<Long>()
        val diskHist = ArrayDeque<Float>()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        while (true) {
            val now = System.currentTimeMillis()
            val dtSec = ((now - prevTime).coerceAtLeast(1) / 1000f)

            // CPU: device overall via /proc/stat, fallback to app CPU via elapsedCpuTime.
            // /proc/stat can be unreadable on some OEMs/SELinux policies (returns null -> stuck 0%).
            val cpu = readCpuDelta(prevCpuTotal, prevCpuIdle, firstCpu)
            var cpuPct = 0f
            if (cpu != null) {
                prevCpuTotal = cpu.total
                prevCpuIdle = cpu.idle
                cpuPct = cpu.pct
            }
            // App-CPU fallback: meaningful even when device stat is blocked, and more
            // relevant for hashing/upload work. Convert ms CPU time -> % of wall time.
            var appCpuPct = 0f
            try {
                val curAppCpu = android.os.Process.getElapsedCpuTime()
                val dCpuMs = (curAppCpu - prevAppCpuTime).coerceAtLeast(0)
                // dtSec already computed; elapsedCpuTime is ms across all threads.
                appCpuPct = (dCpuMs / (dtSec * 1000f) * 100f).coerceIn(0f, 100f * numCores)
                prevAppCpuTime = curAppCpu
            } catch (_: Exception) {}
            // Prefer device stat when available; if it stays 0 while app is busy, show app-normalized.
            // Normalize app CPU to single-core-equivalent device % for display consistency.
            val appNormalized = (appCpuPct / numCores).coerceIn(0f, 100f)
            if (cpu == null || (cpuPct < 0.5f && appNormalized > 1f)) {
                cpuPct = appNormalized
            }
            firstCpu = false

            // RAM / storage
            val mem = ActivityManager.MemoryInfo()
            try { am.getMemoryInfo(mem) } catch (_: Exception) {}
            var appPssMb = 0f
            try {
                val pids = intArrayOf(Process.myPid())
                val infos = am.getProcessMemoryInfo(pids)
                if (infos.isNotEmpty()) appPssMb = infos[0].totalPss / 1024f
            } catch (_: Exception) {}
            var storAvail = 0L; var storTotal = 0L
            try {
                val sf = StatFs(context.filesDir.absolutePath)
                storAvail = sf.availableBytes
                storTotal = sf.totalBytes
            } catch (_: Exception) {}
            // App-specific disk usage: temp SAF copies live in cacheDir during upload.
            // Lightweight du; guarded so a huge cache can't stall the 1s sampler.
            var cacheBytes = 0L; var filesBytes = 0L
            try { cacheBytes = dirSize(context.cacheDir) } catch (_: Exception) {}
            try { filesBytes = dirSize(context.filesDir) } catch (_: Exception) {}

            // Network
            val curRx = TrafficStats.getTotalRxBytes()
            val curTx = TrafficStats.getTotalTxBytes()
            val curAppRx = TrafficStats.getUidRxBytes(uid)
            val curAppTx = TrafficStats.getUidTxBytes(uid)
            fun speed(cur: Long, prev: Long): Long {
                if (cur == TrafficStats.UNSUPPORTED.toLong() || prev == TrafficStats.UNSUPPORTED.toLong()) return 0L
                return ((cur - prev).coerceAtLeast(0) / dtSec).toLong()
            }
            val rxSpeed = speed(curRx, prevRx)
            val txSpeed = speed(curTx, prevTx)
            val appRxSpeed = speed(curAppRx, prevAppRx)
            val appTxSpeed = speed(curAppTx, prevAppTx)
            prevRx = curRx; prevTx = curTx; prevAppRx = curAppRx; prevAppTx = curAppTx
            prevTime = now

            // Upload throughput from UploadManager progress
            val uploaded = uploadedBytesProvider?.invoke() ?: 0L
            val upSpeed = ((uploaded - prevUploaded).coerceAtLeast(0) / dtSec).toLong()
            prevUploaded = uploaded

            // histories
            fun <T> push(q: ArrayDeque<T>, v: T) { q.addLast(v); while (q.size > historySize) q.removeFirst() }
            push(cpuHist, cpuPct)
            val ramPct = if (mem.totalMem > 0) (mem.totalMem - mem.availMem).toFloat() / mem.totalMem * 100f else 0f
            push(memHist, ramPct)
            push(txHist, txSpeed)
            push(rxHist, rxSpeed)
            val diskPct = if (storTotal > 0) (storTotal - storAvail).toFloat() / storTotal * 100f else 0f
            push(diskHist, diskPct)

            emit(
                LiveStats(
                    cpuPercent = cpuPct,
                    ramAvailBytes = mem.availMem,
                    ramTotalBytes = mem.totalMem,
                    appPssMb = appPssMb,
                    storageAvailBytes = storAvail,
                    storageTotalBytes = storTotal,
                    appCacheBytes = cacheBytes,
                    appFilesBytes = filesBytes,
                    lowMemory = mem.lowMemory,
                    rxSpeedBps = rxSpeed,
                    txSpeedBps = txSpeed,
                    appRxSpeedBps = appRxSpeed,
                    appTxSpeedBps = appTxSpeed,
                    totalRxBytes = if (curRx < 0) 0 else curRx,
                    totalTxBytes = if (curTx < 0) 0 else curTx,
                    uploadSpeedBps = upSpeed,
                    uploadedBytes = uploaded,
                    cpuHistory = cpuHist.toList(),
                    memHistory = memHist.toList(),
                    txHistory = txHist.toList(),
                    rxHistory = rxHist.toList(),
                    diskHistory = diskHist.toList(),
                )
            )
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.Default)

    private fun dirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        try {
            // filesDir/cacheDir are small (config + temp copies); walk is cheap vs 1s interval.
            // Guard against runawayideo: cap at 20k entries.
            var count = 0
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    total += f.length()
                    if (++count > 20000) return total
                }
            }
        } catch (_: Exception) {}
        return total
    }

    private data class CpuRead(val total: Long, val idle: Long, val pct: Float)

    private fun readCpuDelta(prevTotal: Long, prevIdle: Long, first: Boolean): CpuRead? {
        try {
            RandomAccessFile("/proc/stat", "r").use { f ->
                val line = f.readLine() ?: return null
                // cpu  user nice system idle iowait irq softirq steal guest guest_nice
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5 || parts[0] != "cpu") return null
                val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (nums.size < 4) return null
                val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
                val total = nums.sum()
                if (first) return CpuRead(total, idle, 0f)
                val dTotal = (total - prevTotal).coerceAtLeast(1)
                val dIdle = (idle - prevIdle).coerceAtLeast(0)
                val pct = ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f)
                return CpuRead(total, idle, pct)
            }
        } catch (_: Exception) {
            return null
        }
    }
}
