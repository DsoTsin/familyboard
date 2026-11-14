package com.familyboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun phoneNumber(input: String): String? {
    val number = input.trim().replace(Regex("[ .()\\-]"), "")
    return number.takeIf { it.matches(Regex("\\+?[0-9]{3,15}")) }
}

internal fun hasLocationPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java)
    val fine = hasLocationPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = hasLocationPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!fine && !coarse) return null
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { (it != LocationManager.GPS_PROVIDER || fine) && manager.isProviderEnabled(it) }
        ?: return null
    return withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, cancellation, ContextCompat.getMainExecutor(context)
                ) { fix ->
                    if (continuation.isActive) continuation.resume(fix)
                }
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
