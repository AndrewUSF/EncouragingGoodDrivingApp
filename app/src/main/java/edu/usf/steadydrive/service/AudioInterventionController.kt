package edu.usf.steadydrive.service

import android.content.Context
import android.media.AudioManager
import edu.usf.steadydrive.model.ParticipantPhase

/**
 * Silences the participant's music by muting the music *stream* and restoring it, instead of
 * grabbing audio focus.
 *
 * The previous implementation requested permanent `AUDIOFOCUS_GAIN` before muting. That tells the
 * music app (Spotify, YouTube Music, etc.) to *stop*, and most players do not auto-resume when the
 * focus is later abandoned — so in Phase B the music never came back after the first speeding event
 * ("stayed muted for the rest of the drive"). Phase A never mutes and Phase C mutes once and stays
 * muted, so only Phase B exposed the problem.
 *
 * Muting the stream volume leaves the player running (silently), so the audio returns the instant we
 * unmute — exactly the Phase B behavior we want.
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

    fun release() {
        if (!isMuted) {
            currentReason = null
            return
        }

        // Unmute first, then restore the captured level: some devices ignore setStreamVolume while
        // the stream is still flagged muted.
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        previousVolume?.let { level ->
            if (!audioManager.isVolumeFixed) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            }
        }
        previousVolume = null
        isMuted = false
        currentReason = null
    }

    private fun engage(reason: String) {
        // Already silenced (e.g. Phase C re-asserting every tick) — never re-capture the volume,
        // otherwise we would save the muted level and "restore" silence later.
        if (isMuted) {
            currentReason = reason
            return
        }

        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        isMuted = true
        currentReason = reason
    }
}
