package com.supernova.launcher.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import kotlin.math.abs

/**
 * Connects to the Google App launcher overlay and forwards a rightward swipe.
 *
 * The overlay protocol is private to Google App. Its AIDL transaction order and
 * attachment bundle are matched to the launcher client protocol used by Lawnchair.
 */
class GoogleDiscoverController(private val activity: Activity) {

    enum class TouchResult { PASS, TAKE_OVER, CONSUMED }

    private var enabled = false
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var bound = false
    private var attached = false
    private var windowAttached = false
    private var overlay: ILauncherOverlay? = null
    private var apiVersion = 1

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var tracking = false
    private var scrolling = false
    private var downX = 0f
    private var downY = 0f

    private val callback = object : ILauncherOverlayCallback.Stub() {
        override fun overlayScrollChanged(progress: Float) = Unit

        override fun overlayStatusChanged(status: Int) {
            Log.d(TAG, "Google overlay status=$status")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            overlay = ILauncherOverlay.Stub.asInterface(service)
            windowAttached = false
            attachWindow()
            updateActivityState()
            Log.d(TAG, "Connected to Google overlay service")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            finishScroll()
            overlay = null
            windowAttached = false
            Log.w(TAG, "Google overlay service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            bindingFailed("binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            bindingFailed("null binding")
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value || destroyed) return
        enabled = value
        if (value) {
            if (started) connect()
        } else {
            disconnect()
        }
    }

    fun onStart() {
        if (destroyed) return
        started = true
        attached = attached || activity.window.decorView.isAttachedToWindow
        if (enabled) connect()
        updateActivityState()
    }

    fun onResume() {
        if (destroyed) return
        resumed = true
        updateActivityState()
    }

    fun onPause() {
        resumed = false
        finishScroll()
        updateActivityState()
    }

    fun onStop() {
        started = false
        resumed = false
        updateActivityState()
        disconnect()
    }

    fun onDestroy() {
        destroyed = true
        enabled = false
        disconnect()
    }

    fun onAttachedToWindow() {
        if (destroyed) return
        attached = true
        attachWindow()
        updateActivityState()
    }

    fun onDetachedFromWindow() {
        attached = false
        detachWindow()
    }

    fun onTouch(event: MotionEvent, eligible: Boolean): TouchResult {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = enabled && eligible && overlay != null && windowAttached
                scrolling = false
                if (tracking) {
                    downX = event.x
                    downY = event.y
                }
                return TouchResult.PASS
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (scrolling) {
                    finishScroll()
                    return TouchResult.CONSUMED
                }
                tracking = false
                return TouchResult.PASS
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    sendScroll(event)
                    return TouchResult.CONSUMED
                }
                if (!tracking) return TouchResult.PASS

                val dx = event.x - downX
                val dy = event.y - downY
                if (dx > touchSlop && dx > abs(dy) * DIRECTION_BIAS) {
                    val current = overlay ?: run {
                        tracking = false
                        return TouchResult.PASS
                    }
                    return try {
                        current.startScroll()
                        scrolling = true
                        sendScroll(event)
                        TouchResult.TAKE_OVER
                    } catch (error: RemoteException) {
                        tracking = false
                        scrolling = false
                        Log.w(TAG, "Unable to start Google Discover swipe", error)
                        TouchResult.PASS
                    }
                }

                if ((abs(dy) > touchSlop && abs(dy) >= dx) || dx < -touchSlop) {
                    tracking = false
                }
                return TouchResult.PASS
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrolling) {
                    finishScroll()
                    return TouchResult.CONSUMED
                }
                tracking = false
                return TouchResult.PASS
            }
        }
        return if (scrolling) TouchResult.CONSUMED else TouchResult.PASS
    }

    private fun sendScroll(event: MotionEvent) {
        val current = overlay ?: return
        val width = activity.window.decorView.width
            .takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val progress = ((event.x - downX) / width.toFloat()).coerceIn(0f, 1f)
        try {
            current.onScroll(progress)
        } catch (error: RemoteException) {
            Log.w(TAG, "Unable to update Google Discover swipe", error)
        }
    }

    private fun finishScroll() {
        tracking = false
        if (!scrolling) return
        scrolling = false
        runCatching { overlay?.endScroll() }
            .onFailure { Log.w(TAG, "Unable to finish Google Discover swipe", it) }
    }

    private fun connect() {
        if (!enabled || !started || destroyed || bound) return
        val intent = serviceIntent()
        apiVersion = runCatching {
            activity.packageManager.resolveService(intent, PackageManager.GET_META_DATA)
                ?.serviceInfo?.metaData?.getInt("service.api.version", 1) ?: 1
        }.getOrDefault(1)

        bound = try {
            activity.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Google overlay service rejected the connection", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to bind Google overlay service", error)
            false
        }

        if (!bound) Log.w(TAG, "Google overlay service is unavailable")
    }

    private fun serviceIntent(): Intent {
        val data = Uri.parse("app://${activity.packageName}:${Process.myUid()}")
            .buildUpon()
            .appendQueryParameter("v", "7")
            .appendQueryParameter("cv", "9")
            .build()
        return Intent(OVERLAY_ACTION)
            .setPackage(GOOGLE_PACKAGE)
            .setData(data)
    }

    private fun attachWindow() {
        val current = overlay ?: return
        if (!attached || windowAttached) return
        try {
            val layoutParams = activity.window.attributes
            if (apiVersion < 3) {
                current.windowAttached(layoutParams, callback, CLIENT_OPTIONS)
            } else {
                val bundle = Bundle().apply {
                    putParcelable("layout_params", layoutParams)
                    putParcelable("configuration", activity.resources.configuration)
                    putInt("client_options", CLIENT_OPTIONS)
                }
                current.windowAttached2(bundle, callback)
            }
            windowAttached = true
        } catch (error: RemoteException) {
            Log.w(TAG, "Unable to attach launcher window to Google overlay", error)
        }
    }

    private fun detachWindow() {
        if (!windowAttached) return
        try {
            overlay?.windowDetached(activity.isChangingConfigurations)
        } catch (error: RemoteException) {
            Log.w(TAG, "Unable to detach launcher window from Google overlay", error)
        } finally {
            windowAttached = false
        }
    }

    private fun updateActivityState() {
        val current = overlay ?: return
        if (!windowAttached) return
        try {
            if (apiVersion >= 4) {
                val state = (if (started) 1 else 0) or (if (resumed) 2 else 0)
                current.setActivityState(state)
            } else if (resumed) {
                current.onResume()
            } else {
                current.onPause()
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Unable to update Google overlay activity state", error)
        }
    }

    private fun disconnect() {
        finishScroll()
        detachWindow()
        if (bound) {
            runCatching { activity.unbindService(connection) }
                .onFailure { Log.w(TAG, "Unable to unbind Google overlay service", it) }
        }
        bound = false
        overlay = null
        windowAttached = false
    }

    private fun bindingFailed(reason: String) {
        Log.w(TAG, "Google overlay binding failed: $reason")
        runCatching { activity.unbindService(connection) }
        bound = false
        overlay = null
        windowAttached = false
        if (enabled && started && !destroyed) connect()
    }

    private companion object {
        const val TAG = "GoogleDiscover"
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        const val OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY"
        const val CLIENT_OPTIONS = 15
        const val DIRECTION_BIAS = 1.2f
    }
}
