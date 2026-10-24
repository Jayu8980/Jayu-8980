package com.pianoscribe.core.perform

/** How an instrument is played, which decides articulation and chord handling. */
enum class Family { KEYS, WIND, BOWED, PLUCKED }

/** Built-in sound used when no SoundFont preset is available for the instrument. */
enum class SoundModel { PIANO, ELECTRIC_PIANO, FLUTE, CLARINET, VIOLIN, CELLO, GUITAR, HARP, ORGAN }

/**
 * Instruments the transcription can be played on.
 *
 * @param gmProgram General MIDI program (0-based) written to MIDI files and looked up in SoundFonts
 * @param low lowest and [high] highest playable MIDI note
 * @param monophonic plays one note at a time (gets the melody line only)
 * @param maxNotes most notes at once (guitar: 6 strings)
 */
enum class Instrument(
    val label: String,
    val gmProgram: Int,
    val low: Int,
    val high: Int,
    val monophonic: Boolean,
    val family: Family,
    val model: SoundModel,
    val maxNotes: Int = 32,
) {
    PIANO("Piano", 0, 21, 108, false, Family.KEYS, SoundModel.PIANO),
    ELECTRIC_PIANO("Electric piano", 4, 28, 103, false, Family.KEYS, SoundModel.ELECTRIC_PIANO),
    FLUTE("Flute", 73, 60, 96, true, Family.WIND, SoundModel.FLUTE),
    CLARINET("Clarinet", 71, 50, 91, true, Family.WIND, SoundModel.CLARINET),
    VIOLIN("Violin", 40, 55, 100, true, Family.BOWED, SoundModel.VIOLIN),
    CELLO("Cello", 42, 36, 76, true, Family.BOWED, SoundModel.CELLO),
    GUITAR("Nylon guitar", 24, 40, 83, false, Family.PLUCKED, SoundModel.GUITAR, maxNotes = 6),
    HARP("Harp", 46, 24, 103, false, Family.PLUCKED, SoundModel.HARP),
    ORGAN("Church organ", 19, 36, 96, false, Family.KEYS, SoundModel.ORGAN),
    ;

    /** Middle of the range where the instrument sounds best; a melody is moved to sit around it. */
    val sweetSpot: Int get() = (low + high) / 2 - 2
}
