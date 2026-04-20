package edu.usf.steadydrive.model

enum class ParticipantPhase(val wireValue: String) {
    PHASE_A("PHASE_A"),
    PHASE_B("PHASE_B"),
    PHASE_C("PHASE_C");

    companion object {
        fun fromWireValue(value: String?): ParticipantPhase? =
            entries.firstOrNull { it.wireValue == value }
    }
}
