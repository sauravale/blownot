package com.blowaway.service

import android.content.Context
import android.os.Build
import com.blowaway.core.detection.BlowFeatures
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val MAX_RECORDING_MILLIS = 20_000L

data class RecordingLabState(
    val activeLabel: String = "",
    val isRecording: Boolean = false,
    val clipCount: Int = 0,
    val lastClipPath: String = "",
    val lastExportPath: String = "",
    val status: String = "No clips recorded yet"
)

@Singleton
class RecordingLabRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutableState = MutableStateFlow(RecordingLabState())
    val state: StateFlow<RecordingLabState> = mutableState

    private val rootDir: File by lazy {
        File(context.getExternalFilesDir(null), "recording-lab").apply { mkdirs() }
    }

    private var activeSession: ActiveSession? = null
    private var clipCount = 0

    @Synchronized
    fun start(label: String) {
        stop()
        val cleanLabel = label.ifBlank { "unlabelled" }.sanitizeFilePart()
        activeSession = ActiveSession(
            label = cleanLabel,
            startedAtMillis = System.currentTimeMillis(),
            pcm = ByteArrayOutputStream(256_000)
        )
        mutableState.update {
            it.copy(
                activeLabel = cleanLabel,
                isRecording = true,
                status = "Recording $cleanLabel"
            )
        }
        BlowAwayLog.i("recording lab started label=$cleanLabel")
    }

    @Synchronized
    fun stop(): File? {
        val session = activeSession ?: return null
        activeSession = null
        val clip = writeClip(session)
        clipCount += 1
        mutableState.update {
            it.copy(
                activeLabel = "",
                isRecording = false,
                clipCount = clipCount,
                lastClipPath = clip.wav.absolutePath,
                status = "Saved ${clip.wav.name}"
            )
        }
        BlowAwayLog.i("recording lab saved wav=${clip.wav.absolutePath} metadata=${clip.metadata.absolutePath}")
        return clip.wav
    }

    @Synchronized
    fun clear() {
        activeSession = null
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        clipCount = 0
        mutableState.value = RecordingLabState(status = "Recording lab cleared")
    }

    @Synchronized
    fun observeSamples(
        samples: ShortArray,
        sampleRate: Int,
        features: BlowFeatures,
        confidence: Float,
        speechConfidence: Float,
        noiseFloor: Float,
        reason: String,
        nowMillis: Long
    ) {
        val session = activeSession ?: return
        if (nowMillis - session.startedAtMillis > MAX_RECORDING_MILLIS) {
            stop()
            return
        }
        if (session.sampleRate == 0) session.sampleRate = sampleRate
        session.lastFeatures = features
        session.lastConfidence = confidence
        session.lastSpeechConfidence = speechConfidence
        session.lastNoiseFloor = noiseFloor
        session.lastReason = reason
        for (sample in samples) {
            session.pcm.write(sample.toInt() and 0xff)
            session.pcm.write((sample.toInt() shr 8) and 0xff)
        }
        val seconds = session.pcm.size() / 2f / sampleRate.coerceAtLeast(1)
        mutableState.update {
            it.copy(status = "Recording ${session.label}: %.1fs".format(Locale.US, seconds))
        }
    }

    @Synchronized
    fun exportArchive(): File {
        stop()
        val zip = File(rootDir.parentFile, "blowaway-recording-lab-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            rootDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                    out.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
        }
        mutableState.update { it.copy(lastExportPath = zip.absolutePath, status = "Exported ${zip.name}") }
        BlowAwayLog.i("recording lab exported archive=${zip.absolutePath}")
        return zip
    }

    private fun writeClip(session: ActiveSession): ClipFiles {
        val timestamp = session.startedAtMillis
        val label = session.label
        val wav = File(rootDir, "${timestamp}_$label.wav")
        val metadata = File(rootDir, "${timestamp}_$label.json")
        val pcmBytes = session.pcm.toByteArray()
        writeWav(wav, pcmBytes, session.sampleRate.coerceAtLeast(16_000))
        metadata.writeText(session.toJson(wav.name))
        return ClipFiles(wav, metadata)
    }

    private fun writeWav(file: File, pcmBytes: ByteArray, sampleRate: Int) {
        val byteRate = sampleRate * 2
        FileOutputStream(file).use { out ->
            out.writeAscii("RIFF")
            out.writeIntLe(36 + pcmBytes.size)
            out.writeAscii("WAVE")
            out.writeAscii("fmt ")
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(sampleRate)
            out.writeIntLe(byteRate)
            out.writeShortLe(2)
            out.writeShortLe(16)
            out.writeAscii("data")
            out.writeIntLe(pcmBytes.size)
            out.write(pcmBytes)
        }
    }

    private data class ClipFiles(val wav: File, val metadata: File)

    private data class ActiveSession(
        val label: String,
        val startedAtMillis: Long,
        val pcm: ByteArrayOutputStream,
        var sampleRate: Int = 0,
        var lastFeatures: BlowFeatures? = null,
        var lastConfidence: Float = 0f,
        var lastSpeechConfidence: Float = 0f,
        var lastNoiseFloor: Float = 0f,
        var lastReason: String = ""
    ) {
        fun toJson(wavName: String): String {
            val features = lastFeatures
            return buildString {
                appendLine("{")
                appendLine("  \"file\": \"${wavName.json()}\",")
                appendLine("  \"label\": \"${label.json()}\",")
                appendLine("  \"startedAtMillis\": $startedAtMillis,")
                appendLine("  \"manufacturer\": \"${(Build.MANUFACTURER ?: "unknown").json()}\",")
                appendLine("  \"model\": \"${(Build.MODEL ?: "unknown").json()}\",")
                appendLine("  \"device\": \"${(Build.DEVICE ?: "unknown").json()}\",")
                appendLine("  \"sdk\": ${Build.VERSION.SDK_INT},")
                appendLine("  \"sampleRate\": $sampleRate,")
                appendLine("  \"durationSeconds\": ${"%.3f".format(Locale.US, pcm.size() / 2f / sampleRate.coerceAtLeast(1))},")
                appendLine("  \"detector\": {")
                appendLine("    \"confidence\": ${lastConfidence.format()},")
                appendLine("    \"speechConfidence\": ${lastSpeechConfidence.format()},")
                appendLine("    \"noiseFloor\": ${lastNoiseFloor.format()},")
                appendLine("    \"reason\": \"${lastReason.json()}\",")
                appendLine("    \"rms\": ${features?.rms.format()},")
                appendLine("    \"peak\": ${features?.peak.format()},")
                appendLine("    \"zeroCrossingRate\": ${features?.zeroCrossingRate.format()},")
                appendLine("    \"spectralCentroid\": ${features?.spectralCentroid.format()},")
                appendLine("    \"spectralFlatness\": ${features?.spectralFlatness.format()},")
                appendLine("    \"frameEnergy\": ${features?.frameEnergy.format()},")
                appendLine("    \"clipping\": ${features?.clipping ?: false}")
                appendLine("  }")
                appendLine("}")
            }
        }
    }
}

private fun FileOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

private fun FileOutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun FileOutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}

private fun String.sanitizeFilePart(): String = lowercase(Locale.US)
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .ifBlank { "unlabelled" }

private fun String.json(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun Float?.format(): String = if (this == null) "0.000000" else String.format(Locale.US, "%.6f", this)
