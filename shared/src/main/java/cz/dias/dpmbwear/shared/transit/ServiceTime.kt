package cz.dias.dpmbwear.shared.transit

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Čas podle GTFS v pásmu Europe/Prague, nezávisle na pásmu zařízení. */
object ServiceTime {

    val ZONE: ZoneId = ZoneId.of("Europe/Prague")

    /**
     * Referenční okamžik dne provozu: místní poledne minus 12 hodin.
     *
     * Časy v stop_times se počítají od něj, ne od půlnoci. Ve dnech změny času
     * to dělá hodinu rozdílu a GTFS to takhle definuje právě kvůli nim.
     */
    fun dayStart(day: LocalDate): Long =
        ZonedDateTime.of(day, LocalTime.NOON, ZONE).toEpochSecond() - 12 * 3600

    fun localDate(epochSeconds: Long): LocalDate =
        Instant.ofEpochSecond(epochSeconds).atZone(ZONE).toLocalDate()

    fun gtfsDate(day: LocalDate): Int = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth

    fun parseGtfsDate(value: String?): LocalDate? {
        val v = value?.trim()?.toIntOrNull() ?: return null
        return runCatching { LocalDate.of(v / 10000, (v / 100) % 100, v % 100) }.getOrNull()
    }

    /** Včera, dnes, zítra. Spoje po půlnoci patří do předchozího dne provozu. */
    fun neighbours(day: LocalDate): List<LocalDate> = listOf(day.minusDays(1), day, day.plusDays(1))
}
