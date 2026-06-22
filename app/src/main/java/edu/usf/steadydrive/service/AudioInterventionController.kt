package edu.usf.steadydrive.service

import android.content.Context
import android.media.AudioManager
import edu.usf.steadydrive.model.ParticipantPhase

/**
 * Silences the participant's music by muting the music *stream* and restoring it, rather than
 * grabbing audio focus — audio focus tells players to stop, and most never auto-resume, which was
 * the Phase B "stuck muted" bug.
 *
 * For reliability across OEMs and Android versions it silences with two independent mechanisms
 * (sets the music-stream volume to zero AND flags the stream muted), re-asserts the mute every tick
 * so a stray volume change cannot defeat it mid-drive, and always restores to an audible level so
 * music is never left silent. [resetToBaseline] clears any mute orphaned by a previously killed
 * session before a new one starts.
 */
class AudioInterventionController(
    context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousVolume: Int? = null

    var isMuted: Boolean = false
        private set

    var currentReason: String? = null
        private set

    val mediaState: String
        get() = if (isMuted) "muted" else "unmuted"

    val interventionAction: String
        get() =
            when (currentReason) {
                "speeding" -> "mute_speeding"
                "phase_c" -> "mute_phase_c"
                else -> "none"
            }

    /**
     * Whether the OS reports the music stream as fixed-volume — i.e. the app cannot change/silence
     * it on the current output device. Logged per sample so the data shows when an intended mute
     * could not actually be applied.
     */
    val isVolumeFixed: Boolean
        get() = runCatching { audioManager.isVolumeFixed }.getOrDefault(false)

    /**
     * Current music-stream volume as a 0–100 percentage (null if it can't be read), so the CSV
     * reveals whether a mute physically took effect rather than only what was intended.
     */
    val musicVolumePercent: Int?
        get() {
            val maxVolume =
                runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                    ?: return null
            if (maxVolume <= 0) {
                return null
            }
            val current =
                runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                    ?: return null
            return (current * 100) / maxVolume
        }

    fun update(phase: ParticipantPhase, shouldMuteForSpeeding: Boolean) {
        when (phase) {
            ParticipantPhase.PHASE_A -> release()
            ParticipantPhase.PHASE_B ->
                if (shouldMuteForSpeeding) {
                    engage("speeding")
                } else {
                    release()
                }

            ParticipantPhase.PHASE_C -> engage("phase_c")
        }
    }

    /**
     * Returns audio to a known, audible baseline — including clearing a mute that an earlier, killed
     * session may have left on the stream. Safe to call when nothing is muted; run it at the start
     * and end of every session.
     */
    fun resetToBaseline() {
        unmuteStream()
        previousVolume?.let { applyVolume(it) }
        previousVolume = null
        isMuted = false
        currentReason = null
    }

    fun release() {
        if (!isMuted) {
            currentReason = null
            return
        }

        // Unmute first, then restore the captured level (some devices ignore setStreamVolume while
        // the stream is still flagged muted). Never restore to silence.
        unmuteStream()
        applyVolume(previousVolume ?: defaultAudibleVolume())
        previousVolume = null
        isMuted = false
        currentReason = null
    }

    private fun engage(reason: String) {
        if (isMuted) {
            // Re-assert every tick so a stray volume change cannot un-silence Phase B/C mid-drive.
            applyVolume(0)
            currentReason = reason
            return
        }

        previousVolume =
            runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        applyVolume(0)
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }
        isMuted = true
        currentReason = reason
    }

    private fun unmuteStream() {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    private fun applyVolume(level: Int) {
        if (audioManager.isVolumeFixed) {
            return
        }
        val maxVolume =
            runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                ?: return
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, maxVolume), 0)
        }
    }

    private fun defaultAudibleVolume(): Int {
        val maxVolume =
            runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                ?: return 0
        return (maxVolume / 2).coerceAtLeast(1)
    }
}
