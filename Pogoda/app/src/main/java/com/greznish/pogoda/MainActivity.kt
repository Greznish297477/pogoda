package com.greznish.pogoda

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    @Deprecated("handled by JS map sheet first")
    override fun onBackPressed() {
        web.evaluateJavascript("(typeof mapOpen==='function'&&mapOpen())?(closeMap(),1):0") { r -> if (r != "1") finish() }
    }

    /** При каждом входе в приложение просим страницу обновить погоду (и место). */
    override fun onResume() {
        super.onResume()
        try { Widgets.refreshAll(this) } catch (_: Exception) {}
        if (::web.isInitialized) web.evaluateJavascript("if(typeof onShow==='function')onShow()", null)
    }

    @SuppressLint("MissingPermission")
    private fun freshLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val p = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } } ?: return
        val done = { l: Location? -> if (l != null) web.evaluateJavascript("geoFix(${l.latitude},${l.longitude})", null) }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(p, null, mainExecutor) { l -> done(l) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(p, object : LocationListener {
                    override fun onLocationChanged(l: Location) { done(l) }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("old API")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        web.evaluateJavascript("geo(1)", null)
    }

    inner class Bridge {
        /** Предложить добавить выбранный виджет на главный экран (cw, cp, fc, ck). */
        @JavascriptInterface
        fun pinWidget(kind: String) {
            val m = AppWidgetManager.getInstance(this@MainActivity)
            val cls = Widgets.kinds[kind] ?: WeatherWidget::class.java
            if (m.isRequestPinAppWidgetSupported)
                m.requestPinAppWidget(ComponentName(this@MainActivity, cls), null, null)
        }

        @JavascriptInterface
        fun theme(seed: Int, theme: String) {
            getSharedPreferences("w", MODE_PRIVATE).edit().putInt("seed", seed).putString("th", theme).apply()
            try { Widgets.buildAll(this@MainActivity) } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun getLocation(): String {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return ""
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val l = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }
                .maxByOrNull { it.time } ?: return ""
            return "${l.latitude},${l.longitude}"
        }

        /** Запросить свежее местоположение; результат придёт в JS через geoFix(lat, lon). */
        @JavascriptInterface
        fun requestFresh() { runOnUiThread { freshLocation() } }

        /** Автоопределение места включено — фоновое обновление тоже следит за местоположением. */
        @JavascriptInterface
        fun setAuto(on: Boolean) {
            getSharedPreferences("w", MODE_PRIVATE).edit().putBoolean("auto", on).apply()
        }

        @JavascriptInterface
        fun sync(name: String, lat: Double, lon: Double) {
            getSharedPreferences("w", MODE_PRIVATE).edit()
                .putString("n", name).putString("la", lat.toString()).putString("lo", lon.toString()).apply()
            Widgets.refreshAll(this@MainActivity)
        }
    }
}
