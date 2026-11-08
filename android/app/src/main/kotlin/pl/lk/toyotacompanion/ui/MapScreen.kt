package pl.lk.toyotacompanion.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * A point of the in-app map: a short fixed label and a position. Trips and parking places hand over positions already rounded to
 * 0.001° (about 100 m); only the "Lokalizacja" screen hands over the exact position, and then the map is opened with `exact = true`.
 */
data class MapPoint(val label: String, val lat: Double, val lon: Double)

/** Hosts the map page may load from: its own assets and the OpenStreetMap tile server, nothing else. */
private const val TILE_HOST = "tile.openstreetmap.org"

/** URL of the bundled page with the points in the fragment; the labels are fixed texts, never user input. */
fun mapPageUrl(points: List<MapPoint>, exact: Boolean = false): String {
    val fragment = points.joinToString(";") {
        if (exact) {
            exactCoordinate(it.lat) + "," + exactCoordinate(it.lon) + "," + it.label
        } else {
            roundedCoordinate(it.lat) + "," + roundedCoordinate(it.lon) + "," + it.label
        }
    }
    return "file:///android_asset/map/map.html#" + Uri.encode(fragment)
}

/** Five decimals (about 1 m) from the decimal text of the value, with a dot: the exact position of the car (docs/SPEC.md §43, ADR-022). */
fun exactCoordinate(value: Double): String =
    java.math.BigDecimal.valueOf(value).setScale(5, java.math.RoundingMode.HALF_UP).toPlainString()

/** `[[lat,lon,e],...]` with `e` 1, 0 or -1 (unknown): numbers only, five decimals, the argument of the page's `showRoute`. */
fun routeJson(points: List<pl.lk.toyotacompanion.core.routes.TripRoutePoint>): String = points.joinToString(",", "[", "]") {
    "[%.5f,%.5f,%d]".format(java.util.Locale.ROOT, it.lat, it.lon, when (it.isEv) { true -> 1; false -> 0; null -> -1 })
}

/** Whether the map page may fetch [uri]: its own assets or an HTTPS tile of OpenStreetMap (docs/SPEC.md §26). */
fun mapRequestAllowed(uri: Uri): Boolean =
    (uri.scheme == "file" && uri.path.orEmpty().startsWith("/android_asset/map/")) ||
        (uri.scheme == "https" && uri.host == TILE_HOST)

/**
 * The map of a trip's two ends or of a parking place, shown at once inside the app (docs/SPEC.md §26): a bundled
 * Leaflet page in a locked-down WebView. Only the points go to the page (rounded, or exact for the car's own position) and only tiles
 * of OpenStreetMap are fetched, so what the tile server sees is the area being viewed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(
    title: String,
    points: List<MapPoint>,
    route: RouteState,
    onBack: () -> Unit,
    heat: HeatState = HeatState.None,
    /** The points are the exact position of the car (the "Lokalizacja" screen); everything else stays at about 100 m. */
    exact: Boolean = false,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var page by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(heat, loaded, page) {
        val view = page
        if (loaded && view != null && heat is HeatState.Ready) view.evaluateJavascript("showHeat(" + heatJson(heat.lines) + ")", null)
    }
    // The route goes to the page once it is ready and the page is loaded; numbers only, so nothing but digits is injected.
    LaunchedEffect(route, loaded, page) {
        val view = page
        if (loaded && view != null && route is RouteState.Ready) view.evaluateJavascript("showRoute(" + routeJson(route.points) + ")", null)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.userAgentString = "toyota-companion (Android WebView)"
                        page = this
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                loaded = true
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (mapRequestAllowed(request.url)) {
                                    null
                                } else {
                                    WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                }

                        }
                        loadUrl(mapPageUrl(points, exact))
                    }
                },
                onRelease = {
                    loaded = false
                    page = null
                    it.destroy()
                },
            )
            DashboardCard(
                title = title,
                icon = if (heat != HeatState.None) AppIcons.LocalFireDepartment else AppIcons.Map,
                modifier = Modifier.padding(12.dp),
            ) {
                val routeNote = if (heat != HeatState.None) Texts.heatNote(heat) else Texts.mapRouteNote(route)
                IconText(
                    if (heat != HeatState.None) AppIcons.LocalFireDepartment else AppIcons.Route,
                    routeNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = readable(Palette.Blue),
                )
                IconText(
                    AppIcons.Lock,
                    Texts.MAP_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                points.firstOrNull()?.let { first ->
                    ParkingFormatting.mapUri(first.lat, first.lon, exact)?.let { uri ->
                        androidx.compose.material3.Button(
                            onClick = { openInMap(context, uri) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            IconLabel(AppIcons.OpenInNew, Texts.MAP_OPEN_EXTERNAL)
                        }
                    }
                }
            }
        }
    }
}
