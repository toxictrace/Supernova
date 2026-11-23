package com.supernova.launcher.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.supernova.googlecompanion.bridge.IDiscoverBridge
import com.supernova.googlecompanion.bridge.IDiscoverBridgeCallback
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import kotlin.math.abs

/**
 * Connects through the companion Binder bridge and forwards a rightward swipe.
 *
 * Google App's launcher-overlay protocol is private. The companion uses its own
 * package identity when binding to Google App, then forwards the original Binder.
 */
class GoogleDiscoverController(private val activity: Activity) {

    enum class TouchResult { PASS, TAKE_OVER, CONSUMED }

    private var enabled = false
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var directBound = false
    private var bridgeBound = false
    private var companionInstalled = companionPackageInstalled()
    private var attached = false
    private var windowAttached = false
    private var bridge: IDiscoverBridge? = null
    private var overlay: ILauncherOverlay? = null
    private var apiVersion = 1
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable {
        if (enabled && started && !destroyed && !directBound && !bridgeBound) {
            Log.i(TAG, "Retrying Discover bridge connection")
            connect()
        }
    }

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

    private val directConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            activity.runOnUiThread {
                if (destroyed || !enabled) {
                    releaseDirectConnection()
                    return@runOnUiThread
                }
                overlay = ILauncherOverlay.Stub.asInterface(service)
                windowAttached = false
                attachWindow()
                updateActivityState()
                Log.i(TAG, "Connected directly to Google overlay service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            activity.runOnUiThread {
                Log.w(TAG, "Direct Google overlay connection disconnected")
                fallbackToBridge("direct service disconnected", name)
            }
        }

        override fun onBindingDied(name: ComponentName) {
            activity.runOnUiThread {
                fallbackToBridge("direct binding died", name)
            }
        }

        override fun onNullBinding(name: ComponentName) {
            activity.runOnUiThread {
                fallbackToBridge("direct service returned null binding", name)
            }
        }
    }

    private val bridgeCallback = object : IDiscoverBridgeCallback.Stub() {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            activity.runOnUiThread {
                if (destroyed || !enabled) return@runOnUiThread
                overlay = ILauncherOverlay.Stub.asInterface(service)
                windowAttached = false
                attachWindow()
                updateActivityState()
                Log.i(TAG, "Bridge delivered Google overlay Binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            activity.runOnUiThread {
                finishScroll()
                overlay = null
                windowAttached = false
                Log.w(TAG, "Google overlay disconnected through bridge")
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            activity.runOnUiThread {
                reconnectHandler.removeCallbacks(reconnectRunnable)
                reconnectAttempt = 0
                bridge = IDiscoverBridge.Stub.asInterface(service)
                try {
                    bridge?.bindService(
                        bridgeCallback,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                    )
                    Log.i(TAG, "Connected to Discover bridge service")
                } catch (error: RemoteException) {
                    Log.w(TAG, "Unable to request Google overlay through bridge", error)
                    bindingFailed("bridge request failed", name)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            activity.runOnUiThread {
                finishScroll()
                bridge = null
                overlay = null
                windowAttached = false
                // Android retains this binding and reconnects when the service returns.
                Log.w(TAG, "Discover bridge service disconnected; awaiting system reconnection")
            }
        }

        override fun onBindingDied(name: ComponentName) {
            activity.runOnUiThread { bindingFailed("bridge binding died", name) }
        }

        override fun onNullBinding(name: ComponentName) {
            activity.runOnUiThread { bindingFailed("bridge returned null binding", name) }
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value || destroyed) return
        enabled = value
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectAttempt = 0
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

        val installedNow = companionPackageInstalled()
        if (installedNow != companionInstalled) {
            companionInstalled = installedNow
            if (!installedNow) {
                disconnect()
                Log.i(TAG, "Google Companion was removed")
            } else {
                Log.i(TAG, "Google Companion was installed; retrying bridge connection")
            }
        }

        if (enabled && started && !directBound && !bridgeBound) {
            reconnectHandler.removeCallbacks(reconnectRunnable)
            connect()
        }
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

    private fun companionPackageInstalled(): Boolean {
        return runCatching {
            activity.packageManager.getPackageInfo(BRIDGE_PACKAGE, 0)
        }.isSuccess
    }

    private fun connect() {
        if (!enabled || !started || destroyed || directBound || bridgeBound) return

        val googleIntent = googleOverlayIntent()
        val resolvedGoogle = runCatching {
            activity.packageManager.resolveService(googleIntent, PackageManager.GET_META_DATA)
        }.onFailure {
            Log.w(TAG, "Unable to resolve Google overlay service", it)
        }.getOrNull()
        val googleInfo = resolvedGoogle?.serviceInfo
        apiVersion = googleInfo?.metaData?.getInt("service.api.version", 1) ?: 1

        val googleAppVersion = runCatching {
            val info = activity.packageManager.getPackageInfo(GOOGLE_PACKAGE, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }

        Log.i(TAG, "Google app=$googleAppVersion; overlay API=$apiVersion")
        directBound = try {
            activity.bindService(
                googleIntent,
                directConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Google App rejected direct overlay connection", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to bind Google overlay directly", error)
            false
        }

        Log.i(TAG, "Direct Google overlay bindService returned $directBound")
        if (directBound) return

        Log.w(TAG, "Direct Google overlay binding unavailable; trying Companion bridge")
        connectBridge()
    }

    private fun connectBridge() {
        if (!enabled || !started || destroyed || directBound || bridgeBound) return
        if (!companionPackageInstalled()) {
            Log.w(TAG, "Google Companion is not installed; direct overlay binding failed")
            return
        }

        val bridgeIntent = bridgeServiceIntent()
        val resolvedBridge = runCatching {
            activity.packageManager.resolveService(bridgeIntent, PackageManager.MATCH_ALL)
        }.getOrNull()

        Log.i(
            TAG,
            "Bridge resolve: component=${resolvedBridge?.serviceInfo?.packageName}/" +
                "${resolvedBridge?.serviceInfo?.name}, exported=${resolvedBridge?.serviceInfo?.exported}"
        )

        bridgeBound = try {
            activity.bindService(
                bridgeIntent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Discover bridge rejected the connection", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to bind Discover bridge service", error)
            false
        }
        Log.i(TAG, "Discover bridge bindService returned $bridgeBound")
        if (!bridgeBound) {
            Log.w(TAG, "Discover bridge is unavailable")
            scheduleReconnect("bridge bindService returned false")
        }
    }

    private fun fallbackToBridge(reason: String, name: ComponentName) {
        Log.w(TAG, "Direct Google overlay failed: $reason; component=${name.flattenToShortString()}")
        finishScroll()
        overlay = null
        windowAttached = false
        releaseDirectConnection()
        connectBridge()
    }

    private fun releaseDirectConnection() {
        if (directBound) {
            runCatching { activity.unbindService(directConnection) }
                .onFailure { Log.w(TAG, "Unable to unbind direct Google overlay", it) }
        }
        directBound = false
    }

    private fun bridgeServiceIntent(): Intent {
        return Intent(OVERLAY_ACTION)
            .setPackage(BRIDGE_PACKAGE)
            .setData(overlayUri())
    }

    private fun googleOverlayIntent(): Intent {
        return Intent(OVERLAY_ACTION)
            .setPackage(GOOGLE_PACKAGE)
            .setData(overlayUri())
    }

    private fun overlayUri(): Uri {
        return Uri.parse("app://${activity.packageName}:${Process.myUid()}")
            .buildUpon()
            .appendQueryParameter("v", "7")
            .appendQueryParameter("cv", "9")
            .build()
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
        reconnectHandler.removeCallbacks(reconnectRunnable)
        finishScroll()
        detachWindow()
        releaseDirectConnection()
        if (bridgeBound) {
            runCatching { activity.unbindService(connection) }
                .onFailure { Log.w(TAG, "Unable to unbind Discover bridge service", it) }
        }
        bridgeBound = false
        bridge = null
        overlay = null
        windowAttached = false
    }

    private fun bindingFailed(reason: String, name: ComponentName) {
        Log.w(TAG, "Discover bridge binding failed: $reason; component=${name.flattenToShortString()}")
        if (bridgeBound) {
            runCatching { activity.unbindService(connection) }
                .onFailure { Log.w(TAG, "Unable to unbind failed Discover bridge", it) }
        }
        bridgeBound = false
        bridge = null
        overlay = null
        windowAttached = false
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (!enabled || !started || destroyed) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPTS)
        val delayMs = (1_000L shl (reconnectAttempt - 1)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectHandler.removeCallbacks(reconnectRunnable)
        Log.w(TAG, "Scheduling Discover bridge reconnect in ${delayMs}ms: $reason")
        reconnectHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private companion object {
        const val TAG = "GoogleDiscover"
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        const val BRIDGE_PACKAGE = "com.supernova.googlecompanion"
        const val OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY"
        const val CLIENT_OPTIONS = 15
        const val DIRECTION_BIAS = 1.2f
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
