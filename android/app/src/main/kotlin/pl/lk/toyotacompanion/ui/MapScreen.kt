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

/** A point of the in-app map: a short fixed label and a position already rounded to 0.001° (about 100 m). */
data class MapPoint(val label: String, val lat: Double, val lon: Double)

/** Hosts the map page may load from: its own assets and the OpenStreetMap tile server, nothing else. */
private const val TILE_HOST = "tile.openstreetmap.org"

/** URL of the bundled page with the points in the fragment; the labels are fixed texts, never user input. */
fun mapPageUrl(points: List<MapPoint>): String {
    val fragment = points.joinToString(";") {
        roundedCoordinate(it.lat) + "," + roundedCoordinate(it.lon) + "," + it.label
    }
    return "file:///android_asset/map/map.html#" + Uri.encode(fragment)
}

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
 * Leaflet page in a locked-down WebView. Only the rounded points go to the page and only tiles of OpenStreetMap
 * are fetched, so the area of the points (not the exact position) is what the tile server sees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(title: String, points: List<MapPoint>, route: RouteState, onBack: () -> Unit, heat: HeatState = HeatState.None) {
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
                        loadUrl(mapPageUrl(points))
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
                emoji = if (heat != HeatState.None) "🔥" else "🗺️",
                modifier = Modifier.padding(12.dp),
            ) {
                val routeNote = if (heat != HeatState.None) Texts.heatNote(heat) else Texts.mapRouteNote(route)
                Text(
                    (if (heat != HeatState.None) "🔥 " else "🛣️ ") + routeNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = readable(Palette.Blue),
                )
                Text(
                    "🔒 " + Texts.MAP_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                points.firstOrNull()?.let { first ->
                    ParkingFormatting.mapUri(first.lat, first.lon)?.let { uri ->
                        androidx.compose.material3.Button(
                            onClick = { openInMap(context, uri) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Text("🌐 " + Texts.MAP_OPEN_EXTERNAL)
                        }
                    }
                }
            }
        }
    }
}
