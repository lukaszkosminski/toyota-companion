package pl.lk.toyotacompanion.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import android.content.Intent
import pl.lk.toyotacompanion.ui.Texts
import pl.lk.toyotacompanion.widget.WidgetSnapshot
import pl.lk.toyotacompanion.widget.WidgetSnapshotStore

/** One row of the car screen: a short title and the text under it (docs/SPEC.md §35). */
data class CarRow(val title: String, val text: String)

/**
 * The rows of the car screen from the snapshot the app saved for the widget (§32): average consumption, the trend and the default
 * price, the refuel forecast. Only lines that have text become rows, and without consumption there is one row asking to open the
 * app on the phone (a template needs at least one row). Pure, so the screen is testable without a car.
 */
fun carRows(snapshot: WidgetSnapshot): List<CarRow> {
    if (snapshot.isEmpty) return listOf(CarRow(Texts.CAR_EMPTY_TITLE, Texts.CAR_EMPTY_TEXT))
    return listOfNotNull(
        CarRow(Texts.CAR_CONSUMPTION, snapshot.main),
        snapshot.line2.takeIf { it.isNotEmpty() }?.let { CarRow(Texts.CAR_TREND, it) },
        snapshot.line3.takeIf { it.isNotEmpty() }?.let { CarRow(Texts.CAR_FORECAST, it) },
    )
}

/**
 * The Android Auto screen (PRD F34, docs/SPEC.md §35, ADR-014): a read-only pane with the numbers of the status screen. It reads
 * the same private snapshot as the widget, so it asks nobody for anything: no network, no archive, no position. Declared in the
 * `IOT` category; Android Auto runs it from the app launcher once "Unknown sources" is on in its developer settings.
 */
class ConsumptionCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // A debuggable build is sideloaded on purpose and also runs against the Desktop Head Unit.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // A release build answers only to the hosts the library lists as Google's own.
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = ConsumptionCarScreen(carContext)
    }
}

/** The pane: a title, the rows of [carRows] and nothing to tap, so a driver has nothing to operate. */
class ConsumptionCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        for (row in carRows(WidgetSnapshotStore(carContext).load())) {
            pane.addRow(Row.Builder().setTitle(row.title).addText(row.text).build())
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle(Texts.CAR_TITLE).setStartHeaderAction(Action.APP_ICON).build())
            .build()
    }
}
