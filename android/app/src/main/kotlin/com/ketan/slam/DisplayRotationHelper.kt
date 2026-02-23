package com.ketan.slam

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Session

class DisplayRotationHelper(private val activity: Activity) : DisplayManager.DisplayListener {

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val displayManager: DisplayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    // KEY FIX: Use the non-deprecated API on API 30+ to get the correct display.
    // windowManager.defaultDisplay is deprecated and returns wrong rotation
    // on multi-display / foldable devices running Android 11+.
    private val display: Display
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        } else {
            @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        }

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    /**
     * Must be called from the GL thread once per frame, before session.update().
     * Passes the current display rotation to ARCore so it can correctly orient
     * the camera image and produce proper texture coordinates.
     */
    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            session.setDisplayGeometry(display.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    fun getRotationDegrees(): Int = when (display.rotation) {
        Surface.ROTATION_0   -> 0
        Surface.ROTATION_90  -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else                 -> 0
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        // Mark as changed so updateSessionIfNeeded() re-sends geometry to ARCore
        viewportChanged = true
    }
}