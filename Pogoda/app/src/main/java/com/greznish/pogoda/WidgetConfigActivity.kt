package com.greznish.pogoda

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** Настройка виджета: та же страница-дизайн, что и в приложении (Material You). */
class WidgetConfigActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setResult(RESULT_CANCELED)
        id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        try { Widgets.build(this, AppWidgetManager.getInstance(this), id, Widgets.kindOf(this, id)) } catch (_: Exception) {}
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Cfg")
        setContentView(web)
        web.loadUrl("file:///android_asset/config.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun init(): String {
            val sp = getSharedPreferences("w", MODE_PRIVATE)
            val o = JSONObject()
                .put("kind", Widgets.kindOf(this@WidgetConfigActivity, id))
                .put("seed", sp.getInt("seed", 220)).put("theme", sp.getString("th", "auto"))
                .put("t", sp.getString("t", "12°")).put("e", sp.getString("e", "⛅"))
                .put("ds", sp.getString("ds", "Облачно")).put("n", sp.getString("n", "Москва"))
            val f = JSONArray()
            for (i in 1..5) sp.getString("f$i", null)?.let { f.put(it) }
            o.put("f", f)
            Widgets.savedJson(this@WidgetConfigActivity, id)?.let { o.put("cfg", JSONObject(it)) }
            return o.toString()
        }

        @JavascriptInterface
        fun save(json: String) {
            getSharedPreferences("wcfg", MODE_PRIVATE).edit().putString("c$id", json).apply()
            runOnUiThread {
                Widgets.refresh(this@WidgetConfigActivity, id)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
        }

        @JavascriptInterface
        fun cancel() { runOnUiThread { setResult(RESULT_CANCELED); finish() } }
    }
}
