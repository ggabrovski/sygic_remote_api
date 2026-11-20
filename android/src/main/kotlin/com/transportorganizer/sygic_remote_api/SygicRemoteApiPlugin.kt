package com.transportorganizer.sygic_remote_api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sygic.sdk.remoteapi.Api
import com.sygic.sdk.remoteapi.ApiCallback
import com.sygic.sdk.remoteapi.ApiItinerary
import com.sygic.sdk.remoteapi.ApiNavigation
import com.sygic.sdk.remoteapi.ApiOptions
import com.sygic.sdk.remoteapi.events.ApiEvents
import com.sygic.sdk.remoteapi.model.Position
import com.sygic.sdk.remoteapi.model.StopOffPoint
import com.sygic.sdk.remoteapi.model.WayPoint
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class SygicRemoteApiPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    private lateinit var appContext: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isConnected = AtomicBoolean(false)

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    @Volatile
    private var sygicApi: Api? = null

    @Volatile
    private var connectedPackage: String = DEFAULT_SYGIC_PACKAGE

    private val apiCallback = object : ApiCallback {
        override fun onServiceConnected() {
            isConnected.set(true)
            executor.execute {
                try {
                    sygicApi?.registerCallback()
                } catch (_: Exception) {
                }
            }
            emitEvent("connected", 0, connectedPackage)
        }

        override fun onServiceDisconnected() {
            isConnected.set(false)
            emitEvent("disconnected", 0, connectedPackage)
        }

        override fun onEvent(eventId: Int, data: String?) {
            val type = when (eventId) {
                ApiEvents.EVENT_WAIPOINT_VISITED,
                ApiEvents.EVENT_WAYPOINT_MARKED_AS_VISITED -> "waypoint_visited"
                ApiEvents.EVENT_ROUTE_FINISH -> "route_finish"
                ApiEvents.EVENT_ROUTE_COMPUTED -> "route_computed"
                ApiEvents.EVENT_ROUTE_USERCANCEL -> "route_cancelled"
                ApiEvents.EVENT_OFF_ROUTE,
                ApiEvents.EVENT_OFF_ROUTE_EXT,
                ApiEvents.EVENT_OFF_ROUTE_PRECOMPUTED -> "off_route"
                ApiEvents.EVENT_APP_EXIT -> "app_exit"
                else -> "raw"
            }
            emitEvent(type, eventId, data ?: "")
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        disconnectInternal()
        executor.shutdownNow()
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        if (isConnected.get()) {
            emitEvent("connected", 0, connectedPackage)
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isInstalled" -> {
                val pkg = call.argument<String>("packageName") ?: DEFAULT_SYGIC_PACKAGE
                result.success(isPackageInstalled(pkg))
            }
            "connect" -> {
                val pkg = call.argument<String>("packageName") ?: DEFAULT_SYGIC_PACKAGE
                handleConnect(pkg, result)
            }
            "disconnect" -> {
                executor.execute {
                    disconnectInternal()
                    replySuccess(result, true)
                }
            }
            "setTruckProfile" -> {
                val profile = call.argument<Map<String, Any?>>("profile") ?: emptyMap()
                handleSetTruckProfile(profile, result)
            }
            "startGuidedRoute" -> {
                val sifJson = call.argument<String>("sifJson") ?: ""
                @Suppress("UNCHECKED_CAST")
                val stops = call.argument<List<Map<String, Any?>>>("stops") ?: emptyList()
                val activeLat = call.argument<Double>("activeLat") ?: 0.0
                val activeLng = call.argument<Double>("activeLng") ?: 0.0
                val activeLabel = call.argument<String>("activeLabel") ?: ""
                val showSygic = call.argument<Boolean>("showSygic") ?: true
                handleStartGuidedRoute(sifJson, stops, activeLat, activeLng, activeLabel, showSygic, result)
            }
            "startSingleStop" -> {
                val lat = call.argument<Double>("lat") ?: 0.0
                val lng = call.argument<Double>("lng") ?: 0.0
                val label = call.argument<String>("label") ?: ""
                val showSygic = call.argument<Boolean>("showSygic") ?: true
                handleStartSingleStop(lat, lng, label, showSygic, result)
            }
            "getRouteStatus" -> handleGetRouteStatus(result)
            "showSygic" -> {
                executor.execute {
                    try {
                        sygicApi?.show(false)
                        replySuccess(result, true)
                    } catch (e: Exception) {
                        replyError(result, "SHOW_FAILED", e.message)
                    }
                }
            }
            "hideSygic" -> {
                executor.execute {
                    try {
                        sygicApi?.bringApplicationToBackground()
                        replySuccess(result, true)
                    } catch (e: Exception) {
                        replyError(result, "HIDE_FAILED", e.message)
                    }
                }
            }
            "stopNavigation" -> {
                executor.execute {
                    try {
                        ApiNavigation.stopNavigation(IPC_TIMEOUT_MS)
                        replySuccess(result, true)
                    } catch (e: Exception) {
                        replyError(result, "STOP_FAILED", e.message)
                    }
                }
            }
            "skipNextWaypoint" -> {
                executor.execute {
                    try {
                        ApiItinerary.skipNextWaypoint(IPC_TIMEOUT_MS)
                        replySuccess(result, true)
                    } catch (e: Exception) {
                        replyError(result, "SKIP_FAILED", e.message)
                    }
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun handleConnect(packageName: String, result: MethodChannel.Result) {
        if (!isPackageInstalled(packageName)) {
            isConnected.set(false)
            result.success(
                mapOf(
                    "connected" to false,
                    "installed" to false,
                    "connectionState" to "not_installed",
                    "packageName" to packageName
                )
            )
            return
        }

        executor.execute {
            try {
                if (sygicApi != null && isConnected.get() && connectedPackage == packageName) {
                    replySuccess(
                        result,
                        mapOf(
                            "connected" to true,
                            "installed" to true,
                            "connectionState" to "connected",
                            "packageName" to packageName
                        )
                    )
                    return@execute
                }

                disconnectInternal()
                connectedPackage = packageName
                val api = Api.init(appContext, packageName, Api.CLASS_FLEET, apiCallback)
                sygicApi = api
                api.connect()

                // Wait briefly for synchronous bind completion so callers know immediately when ready.
                var waitedMs = 0
                while (!isConnected.get() && waitedMs < 1500) {
                    Thread.sleep(100)
                    waitedMs += 100
                }

                val connectedNow = isConnected.get()
                replySuccess(
                    result,
                    mapOf(
                        "connected" to connectedNow,
                        "installed" to true,
                        "connectionState" to if (connectedNow) "connected" else "connecting",
                        "packageName" to packageName
                    )
                )
            } catch (e: Exception) {
                isConnected.set(false)
                replyError(result, "CONNECT_FAILED", e.message)
            }
        }
    }

    private fun handleSetTruckProfile(profile: Map<String, Any?>, result: MethodChannel.Result) {
        executor.execute {
            if (!isConnected.get()) {
                replyError(result, "NOT_CONNECTED", "Sygic IPC service is not connected.")
                return@execute
            }

            try {
                val truckSettings = JSONObject()
                val currentWeight = (profile["currentWeight"] as? Number)?.toInt() ?: 0
                val grossWeight = (profile["grossWeight"] as? Number)?.toInt() ?: 0
                val effectiveWeight = if (currentWeight > 0) currentWeight else grossWeight
                if (effectiveWeight > 0) {
                    truckSettings.put("TotalWeight", effectiveWeight)
                }

                val axleWeight = (profile["weightPerAxle"] as? Number)?.toInt() ?: 0
                if (axleWeight > 0) {
                    truckSettings.put("MaxAxleWeight", axleWeight)
                }

                // Sygic Remote API expects dimensions in millimeters (TruckProfile stores cm).
                val lengthCm = (profile["length"] as? Number)?.toInt() ?: 0
                if (lengthCm > 0) {
                    truckSettings.put("Length", lengthCm * 10)
                }
                val widthCm = (profile["width"] as? Number)?.toInt() ?: 0
                if (widthCm > 0) {
                    truckSettings.put("Width", widthCm * 10)
                }
                val heightCm = (profile["height"] as? Number)?.toInt() ?: 0
                if (heightCm > 0) {
                    truckSettings.put("Height", heightCm * 10)
                }

                val emission = (profile["emissionClass"] as? String)?.uppercase() ?: ""
                val emissionCode = when (emission) {
                    "EURO_3" -> 3
                    "EURO_4" -> 4
                    "EURO_5", "EEV" -> 5
                    "EURO_6", "ZERO" -> 6
                    else -> null
                }
                if (emissionCode != null) {
                    truckSettings.put("EmissionClass", emissionCode)
                }

                val tunnel = (profile["tunnelCategory"] as? String)?.uppercase() ?: ""
                truckSettings.put(
                    "TunnelCode",
                    if (tunnel in listOf("B", "C", "D", "E")) tunnel else "0"
                )
                val hazmat = (profile["hazardousGoods"] as? Boolean) ?: false
                truckSettings.put("Hazmat", hazmat)

                val root = JSONObject().put("TruckSettings", truckSettings)
                ApiOptions.setApplicationOptions(root.toString(), IPC_TIMEOUT_MS)
                replySuccess(result, true)
            } catch (e: Exception) {
                replyError(result, "TRUCK_PROFILE_FAILED", e.message)
            }
        }
    }

    private fun handleStartGuidedRoute(
        sifJson: String,
        stops: List<Map<String, Any?>>,
        activeLat: Double,
        activeLng: Double,
        activeLabel: String,
        showSygic: Boolean,
        result: MethodChannel.Result
    ) {
        executor.execute {
            if (!isConnected.get()) {
                replyError(result, "NOT_CONNECTED", "Sygic IPC service is not connected.")
                return@execute
            }

            // 1. Try precomputed SIF 1.1 guided route first.
            if (sifJson.isNotBlank()) {
                val loadedPath = tryLoadSifRoute(sifJson)
                if (loadedPath != null) {
                    if (showSygic) {
                        safeShowSygic()
                    }
                    replySuccess(
                        result,
                        mapOf(
                            "ok" to true,
                            "mode" to "guided_sif",
                            "message" to "Guided route loaded in Sygic Fleet"
                        )
                    )
                    return@execute
                }
            }

            // 2. Fallback to multi-stop itinerary when >= 2 valid non-passThrough stops exist.
            val validStops = stops.filter { stop ->
                val lat = (stop["lat"] as? Number)?.toDouble() ?: 0.0
                val lng = (stop["lng"] as? Number)?.toDouble() ?: 0.0
                val passThrough = (stop["passThrough"] as? Boolean) ?: false
                !passThrough && !(lat == 0.0 && lng == 0.0)
            }
            if (validStops.size >= 2) {
                val itineraryLoaded = tryStartItinerary(validStops)
                if (itineraryLoaded) {
                    if (showSygic) {
                        safeShowSygic()
                    }
                    replySuccess(
                        result,
                        mapOf(
                            "ok" to true,
                            "mode" to "itinerary",
                            "message" to "Multi-stop itinerary loaded in Sygic Fleet"
                        )
                    )
                    return@execute
                }
            }

            // 3. Final IPC fallback: single-stop navigation to the active task coordinates.
            if (!(activeLat == 0.0 && activeLng == 0.0)) {
                try {
                    val wp = WayPoint(activeLabel, toSygicCoord(activeLng), toSygicCoord(activeLat))
                    ApiNavigation.startNavigation(wp, 0, false, ROUTE_TIMEOUT_MS)
                    if (showSygic) {
                        safeShowSygic()
                    }
                    replySuccess(
                        result,
                        mapOf(
                            "ok" to true,
                            "mode" to "single_stop",
                            "message" to "Navigation started in Sygic Fleet"
                        )
                    )
                    return@execute
                } catch (e: Exception) {
                    replyError(result, "NAVIGATION_FAILED", e.message)
                    return@execute
                }
            }

            replyError(result, "NO_COORDINATES", "No valid route or destination coordinates available.")
        }
    }

    private fun handleStartSingleStop(
        lat: Double,
        lng: Double,
        label: String,
        showSygic: Boolean,
        result: MethodChannel.Result
    ) {
        executor.execute {
            if (!isConnected.get()) {
                replyError(result, "NOT_CONNECTED", "Sygic IPC service is not connected.")
                return@execute
            }
            if (lat == 0.0 && lng == 0.0) {
                replyError(result, "NO_COORDINATES", "Destination coordinates are empty.")
                return@execute
            }
            try {
                val wp = WayPoint(label, toSygicCoord(lng), toSygicCoord(lat))
                ApiNavigation.startNavigation(wp, 0, false, ROUTE_TIMEOUT_MS)
                if (showSygic) {
                    safeShowSygic()
                }
                replySuccess(
                    result,
                    mapOf(
                        "ok" to true,
                        "mode" to "single_stop",
                        "message" to "Navigation started in Sygic Fleet"
                    )
                )
            } catch (e: Exception) {
                replyError(result, "NAVIGATION_FAILED", e.message)
            }
        }
    }

    private fun handleGetRouteStatus(result: MethodChannel.Result) {
        executor.execute {
            if (!isConnected.get()) {
                replySuccess(
                    result,
                    mapOf(
                        "connected" to false,
                        "navigating" to false,
                        "etaMinutes" to 0,
                        "remainingMeters" to 0,
                        "currentLat" to 0.0,
                        "currentLng" to 0.0,
                        "nextWaypointIndex" to 0,
                        "rawStatusJson" to ""
                    )
                )
                return@execute
            }

            var remainingMeters = 0
            var remainingSeconds = 0
            var navigating = false
            var currentLat = 0.0
            var currentLng = 0.0
            var nextWaypointIndex = 0
            var rawStatusJson = ""

            try {
                val info = ApiNavigation.getRouteInfo(false, STATUS_TIMEOUT_MS)
                if (info != null) {
                    remainingMeters = info.remainingDistance.coerceAtLeast(0)
                    remainingSeconds = info.remainingTime.coerceAtLeast(0)
                    navigating = remainingMeters > 0 || remainingSeconds > 0
                }
            } catch (_: Exception) {
                // No active route in Sygic right now.
            }

            try {
                rawStatusJson = ApiNavigation.getRouteStatus(STATUS_TIMEOUT_MS) ?: ""
                if (rawStatusJson.isNotBlank()) {
                    val obj = JSONObject(rawStatusJson)
                    val waypoints = obj.optJSONArray("waypoints")
                    if (waypoints != null) {
                        for (i in 0 until waypoints.length()) {
                            val wp = waypoints.optJSONObject(i) ?: continue
                            if (!wp.optBoolean("visited", false)) {
                                nextWaypointIndex = i
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            try {
                val gps = ApiNavigation.getActualGpsPosition(false, STATUS_TIMEOUT_MS)
                if (gps != null && !(gps.latitude == 0 && gps.longitude == 0)) {
                    currentLat = gps.latitude / SYGIC_COORD_SCALE
                    currentLng = gps.longitude / SYGIC_COORD_SCALE
                }
            } catch (_: Exception) {
            }

            val etaMinutes = if (remainingSeconds > 0) ((remainingSeconds + 30) / 60) else 0
            replySuccess(
                result,
                mapOf(
                    "connected" to true,
                    "navigating" to navigating,
                    "etaMinutes" to etaMinutes,
                    "remainingMeters" to remainingMeters,
                    "currentLat" to currentLat,
                    "currentLng" to currentLng,
                    "nextWaypointIndex" to nextWaypointIndex,
                    "rawStatusJson" to rawStatusJson
                )
            )
        }
    }

    private fun tryLoadSifRoute(sifJson: String): String? {
        // Preferred: transfer compressed bytes directly via Sygic Remote API IPC (avoids Scoped Storage issues).
        try {
            val bytes = sifJson.toByteArray(StandardCharsets.UTF_8)
            val importedPath = Api.importFile(bytes, "active_route.json", true)
            if (!importedPath.isNullOrBlank()) {
                ApiNavigation.loadComputedRoute(importedPath, ROUTE_TIMEOUT_MS)
                return importedPath
            }
        } catch (_: Exception) {
        }

        // Secondary fallback: write to app cache and serve via ContentProvider URI.
        try {
            val dir = File(appContext.cacheDir, SygicRouteContentProvider.ROUTE_CACHE_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "active_route.json")
            file.writeText(sifJson, StandardCharsets.UTF_8)
            val authority = "${appContext.packageName}.sygic_route_provider"
            val contentUri = Uri.parse("content://$authority/active_route.json")
            appContext.grantUriPermission(
                connectedPackage,
                contentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ApiNavigation.loadComputedRoute(contentUri.toString(), ROUTE_TIMEOUT_MS)
            return contentUri.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun tryStartItinerary(stops: List<Map<String, Any?>>): Boolean {
        return try {
            val itineraryName = "active_order_itinerary"
            try {
                ApiItinerary.deleteItinerary(itineraryName, STATUS_TIMEOUT_MS)
            } catch (_: Exception) {
            }

            val points = ArrayList<StopOffPoint>()
            stops.forEachIndexed { index, stop ->
                val lat = (stop["lat"] as? Number)?.toDouble() ?: 0.0
                val lng = (stop["lng"] as? Number)?.toDouble() ?: 0.0
                val label = (stop["label"] as? String) ?: "Stop ${index + 1}"
                val pointType = when (index) {
                    0 -> StopOffPoint.PointType.START
                    stops.lastIndex -> StopOffPoint.PointType.FINISH
                    else -> StopOffPoint.PointType.VIAPOINT
                }
                val point = StopOffPoint().apply {
                    setLocation(Position(toSygicCoord(lng), toSygicCoord(lat)))
                    setPointType(pointType)
                    setCaption(label)
                    setAddress(label)
                    setSearchAddress(false)
                    setVisited(false)
                    setId(index + 1)
                }
                points.add(point)
            }

            ApiItinerary.addItinerary(points, itineraryName, ROUTE_TIMEOUT_MS)
            ApiItinerary.setRoute(itineraryName, 0, ROUTE_TIMEOUT_MS)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeShowSygic() {
        try {
            sygicApi?.show(false)
        } catch (_: Exception) {
        }
    }

    private fun disconnectInternal() {
        val api = sygicApi
        sygicApi = null
        isConnected.set(false)
        if (api != null) {
            try {
                api.unregisterCallback()
            } catch (_: Exception) {
            }
            try {
                api.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun toSygicCoord(degrees: Double): Int = (degrees * SYGIC_COORD_SCALE).roundToInt()

    private fun emitEvent(type: String, eventId: Int, data: String) {
        mainHandler.post {
            eventSink?.success(
                mapOf(
                    "type" to type,
                    "eventId" to eventId,
                    "data" to data
                )
            )
        }
    }

    private fun replySuccess(result: MethodChannel.Result, value: Any?) {
        mainHandler.post { result.success(value) }
    }

    private fun replyError(result: MethodChannel.Result, code: String, message: String?) {
        mainHandler.post { result.error(code, message ?: code, null) }
    }

    companion object {
        private const val METHOD_CHANNEL = "sygic_remote_api/methods"
        private const val EVENT_CHANNEL = "sygic_remote_api/events"
        private const val DEFAULT_SYGIC_PACKAGE = "com.sygic.fleet"
        private const val SYGIC_COORD_SCALE = 100000.0
        private const val STATUS_TIMEOUT_MS = 3000
        private const val IPC_TIMEOUT_MS = 5000
        private const val ROUTE_TIMEOUT_MS = 15000
    }
}
