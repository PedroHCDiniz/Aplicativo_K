package com.pedro.screenshare.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pedro.screenshare.signaling.RoutePoint
import com.pedro.screenshare.signaling.SignalingClient
import com.pedro.screenshare.signaling.SignalingEvent
import com.pedro.screenshare.signaling.SignalingMessage
import com.pedro.screenshare.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object RouteTrackingScheduler {
    private const val PREFS_NAME = "route_tracking_prefs"
    private const val KEY_ROUTE_ENABLED = "route_enabled"
    private const val UNIQUE_PERIODIC_WORK = "route_tracking_periodic_work"
    private const val UNIQUE_IMMEDIATE_WORK = "route_tracking_immediate_work"
    private const val ROUTE_INTERVAL_MINUTES = 30L

    fun startTracking(context: Context) {
        setTrackingEnabled(context, true)
        scheduleRouteWork(context, sendFirstPointNow = true)
    }

    private fun scheduleRouteWork(context: Context, sendFirstPointNow: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val periodicWork =
            PeriodicWorkRequestBuilder<RouteSnapshotWorker>(ROUTE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()

        if (sendFirstPointNow) {
            val immediateWork = OneTimeWorkRequestBuilder<RouteSnapshotWorker>().build()
            workManager.enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                immediateWork
            )
        }

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )
    }

    fun stopTracking(context: Context) {
        setTrackingEnabled(context, false)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
    }

    fun startIfEnabled(context: Context) {
        if (isTrackingEnabled(context)) {
            scheduleRouteWork(context, sendFirstPointNow = false)
        }
    }

    fun isTrackingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ROUTE_ENABLED, false)
    }

    private fun setTrackingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ROUTE_ENABLED, enabled)
            .apply()
    }
}

class RouteSnapshotWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!RouteTrackingScheduler.isTrackingEnabled(applicationContext)) {
            return@withContext Result.success()
        }

        if (!hasLocationPermission()) {
            return@withContext Result.success()
        }

        val location = readBestLocation() ?: return@withContext Result.retry()
        val point = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
            timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )

        if (SignalingClient.isConnected()) {
            SignalingClient.send(point.toRouteMessage())
            Result.success()
        } else if (SignalingClient.isConnecting()) {
            Result.retry()
        } else if (sendWithTemporaryConnection(point)) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) && background
    }

    private suspend fun readBestLocation(): Location? {
        val locationManager = applicationContext.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers) {
                readCurrentLocation(locationManager, provider)?.let { return it }
            }
        }

        return providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private suspend fun readCurrentLocation(locationManager: LocationManager, provider: String): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val executor = Executors.newSingleThreadExecutor()

            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
                executor.shutdown()
            }

            runCatching {
                locationManager.getCurrentLocation(provider, cancellationSignal, executor) { location ->
                    executor.shutdown()
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }.onFailure {
                executor.shutdown()
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun sendWithTemporaryConnection(point: RoutePoint): Boolean {
        val latch = CountDownLatch(1)
        var sent = false
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val requestBuilder = Request.Builder().url(Constants.SIGNALING_SERVER_URL)
        if (Constants.SIGNALING_AUTH_TOKEN.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${Constants.SIGNALING_AUTH_TOKEN}")
        }

        val socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    SignalingMessage(
                        type = SignalingEvent.REGISTER_TRANSMITTER,
                        roomId = Constants.ROOM_ID
                    ).toJson()
                )
                webSocket.send(point.toRouteMessage().toJson())
                sent = true
                webSocket.close(1000, "Ponto de rota enviado")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        latch.await(20, TimeUnit.SECONDS)
        socket.cancel()
        client.dispatcher.executorService.shutdown()
        return sent
    }

    private fun RoutePoint.toRouteMessage(): SignalingMessage {
        return SignalingMessage(
            type = SignalingEvent.ROUTE_POINT,
            roomId = Constants.ROOM_ID,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            timestamp = timestamp
        )
    }
}
