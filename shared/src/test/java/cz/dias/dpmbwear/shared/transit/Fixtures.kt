package cz.dias.dpmbwear.shared.transit

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Syntetický jízdní řád ve stylu IDS JMK – stejný jako server/tests/conftest.py.
 *
 * Linka 1 (T1): Mendlovo nám. U300Z1 08:00 → Česká U100Z1 08:05/08:06 → Hlavní nádraží U200Z1 08:10 → Konečná U400Z1 08:15
 * Linka 6 (T2): Česká U100Z2 08:20 → Hlavní nádraží U200Z1 08:25
 * Noční N1 (T3): Česká U100Z1 24:10 → Hlavní nádraží U200Z1 24:15 (patří do předchozího dne provozu)
 * T4: jako T2, ale 27. 10. 2026 zrušený přes calendar_dates
 */
object Fixtures {

    val FILES = mapOf(
        "routes.txt" to """
            route_id,route_short_name,route_long_name,route_type
            L1D1,1,,0
            L6D1,6,,0
            L99D1,N1,,3
        """,
        "calendar.txt" to """
            service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
            ALL,1,1,1,1,1,1,1,20260101,20271231
        """,
        "calendar_dates.txt" to """
            service_id,date,exception_type
            SOMETIMES,20261028,1
            SOMETIMES,20261027,2
        """,
        "trips.txt" to """
            route_id,service_id,trip_id,trip_headsign
            L1D1,ALL,T1,Konečná
            L6D1,ALL,T2,"Hlavní nádraží"
            L99D1,ALL,T3,
            L6D1,SOMETIMES,T4,Zrušený
        """,
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
            T1,08:00:00,08:00:00,U300Z1,1,0
            T1,08:05:00,08:06:00,U100Z1,2,0
            T1,08:10:00,08:10:00,U200Z1,3,0
            T1,08:15:00,08:15:00,U400Z1,4,1
            T2,08:25:00,08:25:00,U200Z1,2,0
            T2,08:20:00,08:20:00,U100Z2,1,0
            T3,24:10:00,24:10:00,U100Z1,1,0
            T3,24:15:00,24:15:00,U200Z1,2,0
            T4,08:20:00,08:20:00,U100Z2,1,0
            T4,08:25:00,08:25:00,U200Z1,2,0
        """,
        "stops.txt" to """
            stop_id,stop_name,stop_lat,stop_lon,location_type
            U300Z1,Mendlovo náměstí,49.1910,16.5930,0
            U100Z1,Česká,49.1960,16.6060,0
            U100Z2,Česká,49.1962,16.6064,0
            U200Z1,Hlavní nádraží,49.1900,16.6110,0
            U400Z1,Konečná,49.1850,16.6200,0
            U999,Nepoužitá,49.0,16.0,0
        """,
    )

    /** Zip s CRLF konci řádků a BOM v prvním souboru – tak, jak GTFS v praxi chodí. */
    fun zipBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            FILES.entries.forEachIndexed { i, (name, content) ->
                z.putNextEntry(ZipEntry(name))
                val text = content.trimIndent().trim().replace("\n", "\r\n") + "\r\n"
                z.write(((if (i == 0) "﻿" else "") + text).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    val timetable: Timetable by lazy {
        val zip = File.createTempFile("gtfs", ".zip").apply { deleteOnExit(); writeBytes(zipBytes()) }
        val out = ByteArrayOutputStream()
        GtfsCompiler.compile(zip, out, version = "test")
        Timetable.read(ByteBuffer.wrap(out.toByteArray()))
    }

    fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ServiceTime.ZONE).toEpochSecond()

    fun feed(base64: String): FeedSnapshot = GtfsRealtimeDecoder.decode(Base64.getDecoder().decode(base64))

    // Feedy vyrobené oficiální knihovnou gtfs-realtime-bindings (Python),
    // aby se vlastní dekodér ověřil proti referenční implementaci.
    const val PADDED_NOW = 1793084670L
    const val PADDED = "CgsKAzIuMBj+mYHXBhIwCgJlMCIqCgQKAlQxEgoNJcZEQhXHy4RBIAIo/pmB1wY6CFUwMTAwWjAxQgQSAnYw"
    const val GEOMETRY_NOW = 1793084670L
    const val GEOMETRY = "CgsKAzIuMBj+mYHXBhImCgJlMCIgCgQKAlQxEgoNJcZEQhXHy4RBIAIo/pmB1wZCBBICdjA="
    const val STOPPED_NOW = 1793084940L
    const val STOPPED = "CgsKAzIuMBiMnIHXBhIuCgJlMCIoCgQKAlQxEgoNtMhEQhUX2YRBIAEojJyB1wY6BlUxMDBaMUIEEgJ2MA=="
    const val EARLY_NOW = 1793084430L
    const val EARLY = "CgsKAzIuMBiOmIHXBhIuCgJlMCIoCgQKAlQxEgoNJcZEQhXHy4RBIAIojpiB1wY6BlUxMDBaMUIEEgJ2MA=="
    const val TOO_EARLY_NOW = 1793083350L
    const val TOO_EARLY = "CgsKAzIuMBjWj4HXBhIuCgJlMCIoCgQKAlQxEgoNJcZEQhXHy4RBIAIo1o+B1wY6BlUxMDBaMUIEEgJ2MA=="
    const val DUPLICATE_NOW = 1793084670L
    const val DUPLICATE = "CgsKAzIuMBj+mYHXBhIwCgJlMCIqCgQKAlQxEgoNJcZEQhXHy4RBIAIo/pmB1wY6BlUxMDBaMUIGEgQxMjM0EjAKAmUxIioKBAoCVDISCg0lxkRCFcfLhEEgAij+mYHXBjoGVTEwMFoyQgYSBDEyMzQ="
    const val TRIP_UPDATE_NOW = 1793084640L
    const val TRIP_UPDATE = "CgsKAzIuMBjgmYHXBhIeCgN0dTAaFwoOCgJUMRoIMjAyNjEwMjcSBRoDCPABEiYKA3R1MRofCg4KAlQyGggyMDI2MTAyNxINGgsIxP//////////AQ=="
}
