package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.domain.EconomyInterval
import pl.lk.toyotacompanion.core.domain.EconomyStatus
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.sync.ArchiveImportCounts
import pl.lk.toyotacompanion.core.sync.RefuelChange

private val ZONE = ZoneOffset.UTC

/** Counts with every optional part of the summary, so the sweep sees all of them. */
private fun counts(updated: Int = 0, skipped: Int = 0, other: Int = 0) = ArchiveImportCounts(
    vehiclesAdded = 1,
    tripsAdded = 3,
    refuelsAdded = 2,
    refuelsUpdated = updated,
    refuelsSkipped = skipped,
    readingsAdded = 4,
    otherVehicleTrips = other,
    tripsCosted = 3,
)

private fun status(fresh: Boolean) = SyncStatus(
    state = if (fresh) {
        SyncState(vin = "VIN1", lastSuccessAt = Instant.parse("2026-10-29T10:00:00Z"))
    } else {
        SyncState(
            vin = "VIN1",
            lastErrorAt = Instant.parse("2026-10-29T11:00:00Z"),
            lastErrorKind = SyncErrorKind.NETWORK,
        )
    },
    tripsStored = 3,
    readingsStored = 2,
    pendingRefuels = 1,
)

/** Every error has a Polish message; no message leaks a secret (docs/SPEC.md §9, docs/SECURITY.md). */
class TextsTest {
    @Test
    fun `every login error has a message`() {
        for (error in LoginError.entries) {
            val text = Texts.loginError(error)
            assertTrue(text.isNotBlank(), error.name)
            assertTrue(text.first().isUpperCase(), "${error.name}: $text")
        }
    }

    @Test
    fun `rejected login does not blame the password alone`() {
        // Toyota also answers 5xx as a login failure, see docs/SPEC.md §9.1.
        assertTrue("odrzuciła" in Texts.loginError(LoginError.INVALID_CREDENTIALS))
    }

    @Test
    fun `every sync error kind has a message`() {
        for (kind in SyncErrorKind.entries) {
            assertTrue(Texts.syncError(kind).isNotBlank(), kind.name)
        }
    }

    @Test
    fun `freshness is current or not current`() {
        assertEquals("aktualne", Texts.freshness(status(true)))
        assertEquals("nieaktualne", Texts.freshness(status(false)))
    }

    @Test
    fun `last success is a timestamp or missing`() {
        assertEquals("Ostatnia synchronizacja: 2026-10-29 10:00", Texts.lastSuccess(status(true), ZONE))
        assertEquals("Ostatnia synchronizacja: brak", Texts.lastSuccess(status(false), ZONE))
    }

    @Test
    fun `messages carry no secret`() {
        val all = LoginError.entries.map { Texts.loginError(it) } +
            SyncErrorKind.entries.map { Texts.syncError(it) } +
            listOf(Texts.freshness(status(true)), Texts.STATE_NEVER, Texts.STATE_NEEDS_RELOGIN) +
            listOf(Texts.STAT_TRIPS, Texts.STAT_READINGS, Texts.STAT_PENDING_REFUELS) +
            listOf(Texts.APP_NAME, Texts.LOADING, Texts.REFRESH_BUTTON, Texts.LOGOUT_CONFIRM_BODY) +
            listOf(Texts.lastSuccess(status(true), ZONE), Texts.lastSuccess(status(false), ZONE)) +
            // Lista tras (docs/SPEC.md §10): tu trafia kazdy nowy tekst i kazda formatowana wartosc.
            listOf(
                Texts.TRIPS_TITLE,
                Texts.BACK,
                Texts.TRIPS_EMPTY,
                Texts.TRIPS_READ_ERROR,
                Texts.NO_PRICE,
                Texts.DEFAULT_PRICE_NOTE,
                Texts.tripsWithoutCost(3),
            ) +
            TripsFormatting.monthNames() +
            listOf(
                TripsFormatting.money(BigDecimal("3.675")),
                TripsFormatting.pricePerLiter(BigDecimal("6.14")),
                TripsFormatting.liters(BigDecimal("0.6")),
                TripsFormatting.kilometers(12.34),
                TripsFormatting.startDateTime(Instant.parse("2026-10-29T14:05:00Z"), ZONE),
                TripsFormatting.monthLabel(MonthKey(2026, 10)),
            ) +
            // Cena tankowania (docs/SPEC.md §11): kazdy nowy tekst i kazda formatowana wartosc.
            listOf(
                Texts.REFUELS_TITLE,
                Texts.REFUELS_EMPTY,
                Texts.REFUELS_READ_ERROR,
                Texts.refuelPrompt(3),
                Texts.REFUEL_FORM_TITLE,
                Texts.REFUEL_FIELD_PRICE,
                Texts.REFUEL_FIELD_LITERS,
                Texts.REFUEL_FIELD_TOTAL,
                Texts.REFUEL_FILLED_FULL,
                Texts.REFUEL_SAVE,
                Texts.REFUEL_DISMISS,
                Texts.REFUEL_DISMISS_TITLE,
                Texts.REFUEL_DISMISS_BODY,
                Texts.REFUEL_DISMISS_YES,
                Texts.REFUEL_DISMISS_NO,
                Texts.REFUEL_NUMBER_ERROR,
                Texts.REFUEL_POSITIVE_ERROR,
                Texts.REFUEL_COMBINATION_ERROR,
                Texts.REFUEL_WRITE_ERROR,
                Texts.REFUEL_SAVE_FAILED,
                Texts.REFUEL_STALE_ENTRY,
                requireNotNull(Texts.refuelChangeError(RefuelChange.Invalid)),
                requireNotNull(Texts.refuelChangeError(RefuelChange.NoVehicle)),
            ) +
            listOf(
                RefuelFormatting.time(Instant.parse("2026-10-29T14:05:00Z"), ZONE),
                RefuelFormatting.levelJump(20, 80),
                RefuelFormatting.odometer(12_345.0),
            ) +
            // Liters estimate of PRD F2a (docs/SPEC.md §2a, §11.2).
            listOf(
                Texts.REFUEL_ESTIMATE_NEEDS_PRICE,
                Texts.REFUEL_USE_ESTIMATE,
                Texts.REFUEL_LITERS_FROM_ESTIMATE,
                Texts.REFUEL_LITERS_FROM_RECEIPT,
                Texts.refuelEstimateHint(RefuelFormatting.liters(BigDecimal("14.190"))),
                RefuelFormatting.litersInput(BigDecimal("14.190")),
                RefuelFormatting.liters(BigDecimal("14.190")),
            ) +
            // Domyślna cena paliwa (docs/SPEC.md §12.3).
            listOf(
                Texts.DEFAULT_PRICE_TITLE,
                Texts.DEFAULT_PRICE_PROMPT_UNSET,
                Texts.defaultPricePrompt(null),
                Texts.defaultPricePrompt(BigDecimal("6.19")),
                Texts.DEFAULT_PRICE_HINT,
                Texts.DEFAULT_PRICE_REMOVE,
                Texts.DEFAULT_PRICE_REMOVE_TITLE,
                Texts.DEFAULT_PRICE_REMOVE_BODY,
                Texts.DEFAULT_PRICE_REMOVE_YES,
                Texts.DEFAULT_PRICE_REMOVE_NO,
                Texts.DEFAULT_PRICE_EMPTY_ERROR,
            ) +
            // Pytanie o cenę tankowania (docs/SPEC.md §13).
            listOf(
                Texts.REFUEL_NOTIFICATION_CHANNEL,
                Texts.REFUEL_NOTIFICATION_TITLE,
                Texts.refuelNotificationText("30.10 14:05", "20% \u2192 80%"),
                Texts.refuelPromptBody("30.10 14:05", "20% \u2192 80%"),
                Texts.REFUEL_PROMPT_LATER,
                Texts.REFUEL_PROMPT_MORE,
            ) +
            // Kopia archiwum (docs/SPEC.md §14): kazdy nowy tekst i kazda formatowana wartosc.
            listOf(
                Texts.BACKUP_TITLE,
                Texts.BACKUP_EXPORT,
                Texts.BACKUP_IMPORT,
                Texts.BACKUP_WARNING_TITLE,
                Texts.BACKUP_WARNING_BODY,
                Texts.BACKUP_WARNING_OK,
                Texts.BACKUP_WARNING_CANCEL,
                Texts.BACKUP_EXPLANATION,
                Texts.backupExported(3),
                Texts.backupImported(counts(updated = 5, skipped = 7, other = 9)),
                Texts.BACKUP_UNSUPPORTED,
                Texts.BACKUP_CORRUPT,
                Texts.BACKUP_TOO_LARGE,
                Texts.BACKUP_FAILED,
                Texts.lastBackup("31.10.2026"),
                Texts.BACKUP_NONE_YET,
                Texts.BACKUP_REMINDER_NEVER,
                Texts.backupReminderOverdue(31),
            ) +
            // Arkusz tras (docs/SPEC.md §14.4): kazdy nowy tekst i kazda formatowana wartosc.
            listOf(
                Texts.CSV_SECTION,
                Texts.CSV_EXPLANATION,
                Texts.CSV_EXPORT,
                Texts.CSV_NO_TRIPS,
                Texts.csvExported(3),
                Texts.CSV_HEADER.joinToString(";"),
                Texts.CSV_SOURCE_REFUEL,
                Texts.CSV_SOURCE_DEFAULT,
                csvDateTime(Instant.parse("2026-10-29T14:05:00Z"), ZONE),
            ) +
            // Dziennik tankowan (docs/SPEC.md §15): kazdy nowy tekst i kazda formatowana wartosc.
            listOf(
                Texts.LOG_TITLE,
                Texts.LOG_EMPTY,
                Texts.LOG_HINT,
                Texts.LOG_READ_ERROR,
                Texts.LOG_SOURCE_RECEIPT,
                Texts.LOG_SOURCE_DERIVED,
                Texts.LOG_SOURCE_ESTIMATE,
                Texts.LOG_CURRENT,
                Texts.economyCompared("5,00 l/100 km", "4,50 l/100 km", "+11,1 %"),
                Texts.economyTrips("600,0 km"),
                Texts.economyOdometer("612,4 km"),
                Texts.economyEv("70,3 km"),
                Texts.economyComputerSoFar("4,50 l/100 km"),
                Texts.ECONOMY_OPEN,
                Texts.ECONOMY_PENDING,
                Texts.ECONOMY_DISMISSED,
                Texts.ECONOMY_MISSING_RECEIPT,
                Texts.ECONOMY_NO_TRIPS,
                Texts.ECONOMY_TOO_SHORT,
            ) +
            listOf(
                RefuelLogFormatting.consumption(BigDecimal("4.82")),
                RefuelLogFormatting.difference(BigDecimal("11.1")),
                RefuelLogFormatting.difference(BigDecimal("-11.1")),
                RefuelLogFormatting.difference(BigDecimal.ZERO),
                RefuelLogFormatting.distance(BigDecimal("612.4")),
                requireNotNull(
                    RefuelLogFormatting.litersLine(
                        Refuel(
                            id = "refuel-2",
                            ts = Instant.parse("2026-10-29T14:05:00Z"),
                            odometerKm = 12_345.0,
                            levelBefore = 20,
                            levelAfter = 90,
                            liters = BigDecimal("30.000"),
                            pricePerLiter = BigDecimal("6.190"),
                            totalCost = null,
                            status = RefuelStatus.CONFIRMED,
                            litersSource = LitersSource.RECEIPT,
                            filledToFull = true,
                        ),
                    ),
                ),
                RefuelLogFormatting.economyLine(logInterval(EconomyStatus.OK)),
                RefuelLogFormatting.economyLine(logInterval(EconomyStatus.MISSING_RECEIPT)),
                RefuelLogFormatting.detailLine(logInterval(EconomyStatus.OPEN)),
            )
        for (text in all) {
            for (forbidden in listOf("token", "Token", "VIN1", "p@ss", "secret")) {
                assertFalse(forbidden in text, "$forbidden in: $text")
            }
        }
    }

    @Test
    fun `the refuel prompt counts the waiting entries`() {
        assertEquals("Podaj cenę tankowania (3)", Texts.refuelPrompt(3))
    }

    @Test
    fun `every refused refuel write has a message and a success has none`() {
        assertNull(Texts.refuelChangeError(RefuelChange.Done(refuel(), 3)))
        for (change in listOf(RefuelChange.Invalid, RefuelChange.NotFound, RefuelChange.NotPending, RefuelChange.NoVehicle)) {
            val text = requireNotNull(Texts.refuelChangeError(change)) { change.toString() }
            assertTrue(text.isNotBlank(), change.toString())
            assertTrue(text.first().isUpperCase(), "${change}: $text")
        }
    }


    @Test
    fun `the default price prompt shows the stored price`() {
        assertEquals(Texts.DEFAULT_PRICE_PROMPT_UNSET, Texts.defaultPricePrompt(null))

        val prompt = Texts.defaultPricePrompt(BigDecimal("6.19"))
        assertTrue(prompt.startsWith(Texts.DEFAULT_PRICE_TITLE), prompt)
        assertTrue(prompt.endsWith(TripsFormatting.pricePerLiter(BigDecimal("6.19"))), prompt)
    }


    @Test
    fun `the estimate hint names the sensor and shows the counted liters`() {
        assertEquals("Szacunek z czujnika: ok. 14,19 l", Texts.refuelEstimateHint("14,19 l"))
        // The line under the liters field is the hint over the same formatting the field gets.
        assertEquals(
            "Szacunek z czujnika: ok. 14,19 l",
            Texts.refuelEstimateHint(RefuelFormatting.liters(BigDecimal("14.190"))),
        )
    }

    @Test
    fun `the files screen is named for the copy and for the export`() {
        assertEquals("Kopia i eksport", Texts.BACKUP_TITLE)
        assertEquals("Arkusz kalkulacyjny", Texts.CSV_SECTION)
        assertTrue("CSV" in Texts.CSV_EXPORT, Texts.CSV_EXPORT)
        assertEquals("Brak tras do eksportu.", Texts.CSV_NO_TRIPS)
        assertTrue("GPS" in Texts.CSV_EXPLANATION && "VIN" in Texts.CSV_EXPLANATION, Texts.CSV_EXPLANATION)
        assertTrue("trasy: 3" in Texts.csvExported(3), Texts.csvExported(3))
    }

    @Test
    fun `the csv header cells and the price sources are fixed words`() {
        // The unit lives in the header, so the cells below stay plain numbers.
        assertEquals(8, Texts.CSV_HEADER.size)
        assertEquals(listOf("Start", "Koniec", "Dystans [km]", "Paliwo [l]", "Dystans EV [km]", "Cena [zł/l]", "Koszt [zł]", "Źródło ceny"), Texts.CSV_HEADER)
        assertEquals("tankowanie", Texts.CSV_SOURCE_REFUEL)
        assertEquals("cena domy\u015Blna", Texts.CSV_SOURCE_DEFAULT)
    }

    @Test
    fun `the import summary shows only what happened`() {
        val quiet = Texts.backupImported(counts())
        assertTrue("trasy: 3" in quiet, quiet)
        assertTrue("tankowania: 2" in quiet, quiet)
        assertTrue("odczyty paliwa: 4" in quiet, quiet)
        for (absent in listOf("Uzupełnione ceny", "Pominięte tankowania", "Trasy innego pojazdu")) {
            assertTrue(absent !in quiet, "$absent in: $quiet")
        }

        val loud = Texts.backupImported(counts(updated = 5, skipped = 7, other = 9))
        assertTrue("Uzupełnione ceny tankowań: 5." in loud, loud)
        assertTrue("Pominięte tankowania (odrzucone w tym telefonie): 7." in loud, loud)
        assertTrue("Trasy innego pojazdu: 9" in loud, loud)
    }

    @Test
    fun `every backup text is a polish sentence`() {
        for (text in listOf(
            Texts.BACKUP_TITLE,
            Texts.BACKUP_EXPORT,
            Texts.BACKUP_IMPORT,
            Texts.BACKUP_WARNING_TITLE,
            Texts.BACKUP_WARNING_BODY,
            Texts.BACKUP_WARNING_OK,
            Texts.BACKUP_WARNING_CANCEL,
            Texts.BACKUP_EXPLANATION,
            Texts.backupExported(3),
            Texts.backupImported(counts()),
            Texts.BACKUP_UNSUPPORTED,
            Texts.BACKUP_CORRUPT,
            Texts.BACKUP_TOO_LARGE,
            Texts.BACKUP_FAILED,
            Texts.lastBackup("31.10.2026"),
            Texts.BACKUP_NONE_YET,
            Texts.BACKUP_REMINDER_NEVER,
            Texts.backupReminderOverdue(31),
        )) {
            assertTrue(text.isNotBlank(), text)
            assertTrue(text.first().isUpperCase(), text)
        }
    }

    @Test
    fun `the backup messages say what was kept and the reminder counts days`() {
        assertEquals("Zapisano kopię archiwum (trasy: 3).", Texts.backupExported(3))
        assertEquals("Ostatnia kopia archiwum: 31.10.2026", Texts.lastBackup("31.10.2026"))
        assertTrue("31 dni" in Texts.backupReminderOverdue(31), Texts.backupReminderOverdue(31))
        for (text in listOf(Texts.BACKUP_UNSUPPORTED, Texts.BACKUP_CORRUPT, Texts.BACKUP_TOO_LARGE)) {
            assertTrue("Nic nie zostało zapisane" in text, text)
        }
    }

    @Test
    fun `the log names the liter sources and every reason without a comparison`() {
        assertEquals("Dziennik tankowań", Texts.LOG_TITLE)
        assertEquals("Brak potwierdzonych tankowań.", Texts.LOG_EMPTY)
        assertEquals("Bieżący odcinek", Texts.LOG_CURRENT)
        assertEquals("z paragonu", Texts.LOG_SOURCE_RECEIPT)
        assertEquals("wyliczone z paragonu", Texts.LOG_SOURCE_DERIVED)
        assertEquals("szacunek z czujnika", Texts.LOG_SOURCE_ESTIMATE)
        // The hint is the only place that teaches the two conditions of PRD F9.
        assertTrue("Do pełna" in Texts.LOG_HINT, Texts.LOG_HINT)
        assertTrue("paragon" in Texts.LOG_HINT, Texts.LOG_HINT)
        assertTrue("100 km" in Texts.ECONOMY_TOO_SHORT, Texts.ECONOMY_TOO_SHORT)

        val compared = Texts.economyCompared("5,00 l/100 km", "4,50 l/100 km", "+11,1 %")
        assertTrue("faktyczne 5,00 l/100 km" in compared, compared)
        assertTrue("komputer 4,50 l/100 km" in compared, compared)
        assertTrue("+11,1 %" in compared, compared)
    }

    @Test
    fun `every refuel log reason is a polish sentence`() {
        for (text in listOf(
            Texts.LOG_TITLE,
            Texts.LOG_EMPTY,
            Texts.LOG_HINT,
            Texts.LOG_READ_ERROR,
            Texts.LOG_CURRENT,
            Texts.ECONOMY_OPEN,
            Texts.ECONOMY_PENDING,
            Texts.ECONOMY_DISMISSED,
            Texts.ECONOMY_MISSING_RECEIPT,
            Texts.ECONOMY_NO_TRIPS,
            Texts.ECONOMY_TOO_SHORT,
        )) {
            assertTrue(text.isNotBlank(), text)
            assertTrue(text.first().isUpperCase(), text)
        }
        // The three words that name where the liters came from are lowercase fragments, not sentences.
        for (text in listOf(
            Texts.LOG_SOURCE_RECEIPT,
            Texts.LOG_SOURCE_DERIVED,
            Texts.LOG_SOURCE_ESTIMATE,
        )) {
            assertTrue(text.isNotBlank(), text)
            assertTrue(text.first().isLowerCase(), text)
        }
    }

    @Test
    fun `the notification line says when and how much and asks for the price`() {
        val text = Texts.refuelNotificationText("30.10 14:05", Texts.refuelPromptBody("30.10 14:05", "x"))

        assertTrue(text.startsWith("30.10 14:05"), text)
        assertTrue(text.endsWith(". Podaj cenę za litr."), text)
        assertEquals("30.10 14:05 · x", Texts.refuelPromptBody("30.10 14:05", "x"))
    }

}

/** Minimal entry for the message checks; the values are not formatted here. */
private fun refuel(): Refuel = Refuel(
    id = "refuel-1",
    ts = Instant.parse("2026-10-29T14:05:00Z"),
    odometerKm = 12_345.0,
    levelBefore = 20,
    levelAfter = 80,
    liters = null,
    pricePerLiter = null,
    totalCost = null,
    status = RefuelStatus.PENDING,
)

/** An interval of the shape `fuelEconomyIntervals` returns, for the text of its status. */
private fun logInterval(status: EconomyStatus): EconomyInterval = EconomyInterval(
    startRefuelId = "a",
    endRefuelId = if (status == EconomyStatus.OPEN) null else "b",
    status = status,
    trips = 3,
    distanceKm = BigDecimal("600.0"),
    evDistanceKm = BigDecimal("70.3"),
    computerLiters = BigDecimal("27.000"),
    actualLiters = BigDecimal("30.000"),
    odometerKm = BigDecimal("612.4"),
    computerLPer100Km = BigDecimal("4.50"),
    actualLPer100Km = BigDecimal("5.00"),
    differencePct = BigDecimal("11.1"),
)
