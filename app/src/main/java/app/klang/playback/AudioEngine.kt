package app.klang.playback

/**
 * Shared, process-wide audio settings and meters.
 *
 * The UI writes the EQ / balance values; [KlangAudioProcessor] reads them on the audio
 * thread and writes the spectrum and waveform back for the visualizer.
 */
object AudioEngine {
    /** Winamp's ten EQ bands, in Hz. */
    val FREQS = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
    const val BAND_COUNT = 10
    const val MAX_DB = 12f

    @Volatile var eqEnabled: Boolean = true
        set(value) { field = value; version++ }
    @Volatile var preampDb: Float = 0f
        set(value) { field = value.coerceIn(-MAX_DB, MAX_DB); version++ }
    private val gains = FloatArray(BAND_COUNT)

    /** Player volume chosen with the knob, 0..1. The service applies it, times the fade. */
    @Volatile var userVolume: Float = 0.7f

    /** Fade-out length at the end of a song (the next one fades in over half of it). 0 = off. */
    @Volatile var fadeMs: Long = 0L

    /** -1 = left only, 0 = centre, +1 = right only. */
    @Volatile var balance: Float = 0f

    /** Bumped whenever EQ parameters change so the audio thread recomputes filters. */
    @Volatile var version: Int = 0
        private set

    fun gainDb(band: Int): Float = gains[band]

    fun setGainDb(band: Int, db: Float) {
        gains[band] = db.coerceIn(-MAX_DB, MAX_DB)
        version++
    }

    fun setAllGains(values: FloatArray) {
        for (i in 0 until BAND_COUNT) gains[i] = values.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB)
        version++
    }

    // ---- meters (written by the audio thread) ----
    const val METER_BANDS = 13
    val levels = FloatArray(METER_BANDS)
    val wave = FloatArray(64)
    @Volatile var lastAudioNanos: Long = 0L

    fun audioIsLive(): Boolean = System.nanoTime() - lastAudioNanos < 250_000_000L
}
