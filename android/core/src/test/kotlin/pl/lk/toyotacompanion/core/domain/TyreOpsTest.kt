package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tyres (PRD F56, docs/SPEC.md §56); the same test names live in `backend/tests/test_domain_tyres.py`. */
class TyreOpsTest {
    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    private fun d(v: String) = BigDecimal(v)

    private fun day(v: String) = LocalDate.parse(v)

    private fun trip(id: String, start: String, km: String): Trip {
        val begin = Instant.parse(start)
        return Trip(id, begin, begin.plusSeconds(900), km.toDouble(), d("0.5"), null)
    }

    @Test
    fun `parse_skips_broken_entries_and_keeps_the_first_of_a_date`() {
        val parsed = parseTyreChanges("2026-04-15|S|1234;junk;2026-04-15|W|;2025-10-20|W|;2026-13-01|S|;2026-01-02|X|")
        assertEquals(listOf(day("2025-10-20"), day("2026-04-15")), parsed.map { it.date })
        assertEquals(TyreSet.WINTER, parsed.first().set)
        assertEquals(1234, parsed.last().startOdometerKm)
    }

    @Test
    fun `parse_garbage_is_no_changes`() {
        assertEquals(emptyList(), parseTyreChanges(null))
        assertEquals(emptyList(), parseTyreChanges(""))
        assertEquals(emptyList(), parseTyreChanges("!!!;;"))
        assertEquals(emptyList(), parseTyreChanges("2026-04-15|S|-5"))
    }

    @Test
    fun `format_round_trips`() {
        val changes = listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, 1234), TyreChange(day("2025-10-20"), TyreSet.WINTER, null))
        assertEquals(changes.sortedBy { it.date }, parseTyreChanges(formatTyreChanges(changes)))
    }

    @Test
    fun `thresholds_parse_and_garbage_is_absent`() {
        val t = parseTyreThresholds("S:6:15000;W:x:-1")
        assertEquals(TyreThreshold(6, 15000), t.getValue(TyreSet.SUMMER))
        assertEquals(TyreThreshold(null, null), t.getValue(TyreSet.WINTER))
        assertEquals(TyreThreshold(null, null), parseTyreThresholds(null).getValue(TyreSet.SUMMER))
        assertEquals("S:6:15000;W::", formatTyreThresholds(t))
    }

    @Test
    fun `reminder_keys_parse_and_format_back`() {
        assertEquals(setOf("a", "b"), parseTyreReminderKeys(" a,b,,"))
        assertEquals(emptySet(), parseTyreReminderKeys(null))
        assertEquals("a,b", formatTyreReminderKeys(setOf("b", "a")))
    }

    @Test
    fun `usage_assigns_a_trip_to_the_set_of_its_start_date`() {
        val changes = listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null), TyreChange(day("2026-10-20"), TyreSet.WINTER, null))
        val usage = tyreUsage(
            changes,
            listOf(trip("a", "2026-04-20T08:00:00Z", "10.0"), trip("b", "2026-10-25T08:00:00Z", "20.0")),
            warsaw,
        )
        assertEquals(1, usage.sets[0].trips)
        assertEquals(d("10.0"), usage.sets[0].km)
        assertEquals(d("20.0"), usage.sets[1].km)
    }

    @Test
    fun `a_trip_on_the_change_day_belongs_to_the_new_set_in_the_zone_of_the_phone`() {
        // 2026-10-19 23:30 UTC is 2026-10-20 00:30 in Warsaw: the first day of the winter set.
        val changes = listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null), TyreChange(day("2026-10-20"), TyreSet.WINTER, null))
        val usage = tyreUsage(changes, listOf(trip("a", "2026-10-19T23:30:00Z", "7.0")), warsaw)
        assertEquals(0, usage.sets[0].trips)
        assertEquals(1, usage.sets[1].trips)
        assertEquals(0, tyreUsage(changes, listOf(trip("a", "2026-10-19T23:30:00Z", "7.0")), ZoneId.of("UTC")).sets[1].trips)
    }

    @Test
    fun `trips_before_the_first_change_are_counted_apart`() {
        val changes = listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null))
        val usage = tyreUsage(changes, listOf(trip("old", "2026-03-01T08:00:00Z", "30.0"), trip("new", "2026-04-16T08:00:00Z", "5.0")), warsaw)
        assertEquals(1, usage.beforeFirstTrips)
        assertEquals(d("30.0"), usage.beforeFirstKm)
        assertEquals(d("5.0"), usage.sets.single().km)
    }

    @Test
    fun `the_set_on_now_runs_to_today_and_the_earlier_one_ends_at_the_next_change`() {
        val changes = listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null), TyreChange(day("2026-10-20"), TyreSet.WINTER, null))
        val usage = tyreUsage(changes, emptyList(), warsaw)
        assertEquals(day("2026-10-20"), usage.sets[0].until)
        assertNull(usage.sets[1].until)
    }

    @Test
    fun `no_fitting_means_no_usage_and_no_reminder`() {
        val usage = tyreUsage(emptyList(), listOf(trip("a", "2026-04-20T08:00:00Z", "10.0")), warsaw)
        assertTrue(usage.sets.isEmpty())
        assertEquals(1, usage.beforeFirstTrips)
        assertTrue(dueTyreReminders(usage, parseTyreThresholds("S:1:1"), day("2026-12-01")).isEmpty())
    }

    @Test
    fun `reminder_months_count_full_months_from_the_change`() {
        val usage = tyreUsage(listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null)), emptyList(), warsaw)
        val thresholds = parseTyreThresholds("S:6:")
        assertTrue(dueTyreReminders(usage, thresholds, day("2026-10-14")).isEmpty(), "five months and 29 days")
        val due = dueTyreReminders(usage, thresholds, day("2026-10-15"))
        assertEquals(listOf(TyreDue.MONTHS), due.map { it.due })
        assertEquals(6, due.single().months)
    }

    @Test
    fun `reminder_km_uses_the_archive_of_the_set`() {
        val changes = listOf(TyreChange(day("2025-10-20"), TyreSet.WINTER, null), TyreChange(day("2026-04-15"), TyreSet.SUMMER, null))
        val trips = listOf(trip("w", "2026-01-10T08:00:00Z", "9000.0"), trip("s", "2026-05-02T08:00:00Z", "6000.0"))
        val usage = tyreUsage(changes, trips, warsaw)
        // The winter km are not the summer set's: 6000 km is under 7000.
        assertTrue(dueTyreReminders(usage, parseTyreThresholds("S::7000"), day("2026-06-01")).isEmpty())
        val due = dueTyreReminders(usage, parseTyreThresholds("S::5000"), day("2026-06-01"))
        assertEquals(TyreDue.KM, due.single().due)
    }

    @Test
    fun `no_threshold_means_no_reminder`() {
        val usage = tyreUsage(listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null)), listOf(trip("a", "2026-04-20T08:00:00Z", "99999.0")), warsaw)
        assertTrue(dueTyreReminders(usage, parseTyreThresholds("W:1:1"), day("2027-01-01")).isEmpty())
        assertTrue(dueTyreReminders(usage, parseTyreThresholds(null), day("2027-01-01")).isEmpty())
    }

    @Test
    fun `only_the_set_on_now_is_reminded`() {
        val changes = listOf(TyreChange(day("2025-10-20"), TyreSet.WINTER, null), TyreChange(day("2026-04-15"), TyreSet.SUMMER, null))
        val usage = tyreUsage(changes, emptyList(), warsaw)
        // The winter set is off since April: its threshold does not fire, the summer one does not yet.
        assertTrue(dueTyreReminders(usage, parseTyreThresholds("W:1:"), day("2026-06-01")).isEmpty())
    }

    @Test
    fun `reminder_key_is_per_set_and_fitting_day_and_kind`() {
        val usage = tyreUsage(listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null)), emptyList(), warsaw)
        val due = dueTyreReminders(usage, parseTyreThresholds("S:6:"), day("2026-10-15")).single()
        assertEquals("SUMMER:2026-04-15:MONTHS", tyreReminderKey(due))
    }

    @Test
    fun `a_future_fitting_is_not_due_yet`() {
        val usage = tyreUsage(listOf(TyreChange(day("2026-12-01"), TyreSet.SUMMER, null)), emptyList(), warsaw)
        assertTrue(dueTyreReminders(usage, parseTyreThresholds("S:1:"), day("2026-11-11")).isEmpty())
    }

    @Test
    fun `a_new_fitting_must_be_today_or_earlier_and_after_the_last_one`() {
        val existing = listOf(TyreChange(day("2026-04-15"), TyreSet.SUMMER, null))
        assertEquals(TyreChangeCheck.Future, checkTyreChange(existing, day("2026-11-12"), day("2026-11-11")))
        assertEquals(TyreChangeCheck.Duplicate, checkTyreChange(existing, day("2026-04-15"), day("2026-11-11")))
        assertEquals(TyreChangeCheck.BeforeLast, checkTyreChange(existing, day("2026-03-01"), day("2026-11-11")))
        assertEquals(TyreChangeCheck.Ok, checkTyreChange(existing, day("2026-10-20"), day("2026-11-11")))
    }

    @Test
    fun `typed_numbers_are_checked_against_the_maximum`() {
        assertEquals(15000, parseTyreNumber(" 15000 ", TYRE_MAX_KM))
        assertNull(parseTyreNumber("abc", TYRE_MAX_KM))
        assertNull(parseTyreNumber("300001", TYRE_MAX_KM))
    }
}

class TyreRemovalTest {
    private fun day(v: String) = LocalDate.parse(v)

    @Test
    fun `removing_removes_only_the_newest_fitting`() {
        val changes = listOf(TyreChange(day("2026-10-20"), TyreSet.WINTER, null), TyreChange(day("2026-04-15"), TyreSet.SUMMER, null))
        assertEquals(listOf(day("2026-04-15")), withoutLastTyreChange(changes).map { it.date })
        assertEquals(emptyList(), withoutLastTyreChange(emptyList()))
    }

    @Test
    fun `removing_a_change_forgets_only_its_own_reminder_keys`() {
        val keys = setOf("SUMMER:2026-04-15:MONTHS", "SUMMER:2026-04-15:KM", "WINTER:2026-10-20:KM")
        val removed = TyreChange(day("2026-04-15"), TyreSet.SUMMER, null)
        assertEquals(setOf("WINTER:2026-10-20:KM"), tyreKeysWithoutChange(keys, removed))
        // A fitting of another set on the same day keeps its keys.
        assertEquals(keys, tyreKeysWithoutChange(keys, TyreChange(day("2026-04-15"), TyreSet.WINTER, null)))
    }
}
