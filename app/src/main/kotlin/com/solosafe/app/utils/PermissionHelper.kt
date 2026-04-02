package com.solosafe.app.utils

import android.Manifest
import android.os.Build

object PermissionHelper {

    val requiredPermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    // Background location must be requested separately after foreground is granted
    val backgroundLocationPermission: String
        get() = Manifest.permission.ACCESS_BACKGROUND_LOCATION
}
