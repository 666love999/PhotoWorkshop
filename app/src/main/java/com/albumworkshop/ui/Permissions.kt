package com.albumworkshop.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}

internal fun hasMediaPermission(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
            isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)

    else -> isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
