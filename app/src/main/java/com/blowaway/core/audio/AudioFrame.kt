package com.blowaway.core.audio

data class AudioFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val timestampMillis: Long
) {
    override fun equals(other: Any?): Boolean {
        return other is AudioFrame &&
            samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate &&
            timestampMillis == other.timestampMillis
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMillis.hashCode()
        return result
    }
}
