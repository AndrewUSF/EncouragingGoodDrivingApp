package edu.usf.steadydrive.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import edu.usf.steadydrive.model.ParticipantPhase

class AudioInterventionController(
    context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
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

        if (previousVolume != null && !audioManager.isVolumeFixed) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                previousVolume ?: 0,
                0,
            )
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        isMuted = false
        currentReason = null
    }

    private fun engage(reason: String) {
        if (isMuted && currentReason == reason) {
            return
        }

        if (audioFocusRequest == null) {
            audioFocusRequest =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
        }

        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.requestAudioFocus(audioFocusRequest!!)
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        isMuted = true
        currentReason = reason
    }
}
