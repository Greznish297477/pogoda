package com.greznish.pogoda

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

abstract class BaseWidget(private val kind: String) : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { try { Widgets.build(c, m, it, kind) } catch (_: Exception) {} }
        val pending = goAsync()
        Thread {
            try { Widgets.fetchSync(c); ids.forEach { try { Widgets.build(c, m, it, kind) } catch (_: Exception) {} } }
            catch (_: Exception) {} finally { pending.finish() }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle?) { try { Widgets.build(c, m, id, kind) } catch (_: Exception) {} }

    override fun onDeleted(c: Context, ids: IntArray) {
        val e = c.getSharedPreferences("wcfg", Context.MODE_PRIVATE).edit()
        ids.forEach { e.remove("c$it") }; e.apply()
    }
}

/** Четыре виджета в списке «Виджеты» телефона. */
class WeatherWidget : BaseWidget("cw")   // Часы слева + погода справа (+ прогноз)
class CompactWidget : BaseWidget("cp")   // Компактная погода
class ForecastWidget : BaseWidget("fc")  // Прогноз на 5 дней
class ClockWidget : BaseWidget("ck")     // Только часы

data class WCfg(
    var bg: Int, var fg: Int, var alpha: Int, var rad: Int, var fs: Int,
    var sec: Boolean, var date: Boolean, var dv: Boolean, var fc: Boolean,
    var city: Boolean, var desc: Boolean, var h24: Boolean
)

object Widgets {
    val kinds = mapOf(
        "cw" to WeatherWidget::class.java, "cp" to CompactWidget::class.java,
        "fc" to ForecastWidget::class.java, "ck" to ClockWidget::class.java
    )

    fun kindOf(c: Context, id: Int): String {
        val cn = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: return "cw"
        return kinds.entries.firstOrNull { it.value.name == cn }?.key ?: "cw"
    }

    private fun layoutFor(cls: Int) = when (cls) {
        0 -> R.layout.widget_ck; 1 -> R.layout.widget_w1; 2 -> R.layout.widget_w2; else -> R.layout.widget_w3
    }
    private fun defFs(kind: String) = when (kind) { "cp" -> 40; "fc" -> 34; "ck" -> 52; else -> 44 }

    fun savedJson(c: Context, id: Int): String? = c.getSharedPreferences("wcfg", Context.MODE_PRIVATE).getString("c$id", null)

    private fun hsl(h: Float, sat: Float, l: Float): Int {
        val a = sat * minOf(l, 1 - l)
        fun f(n: Int): Int { val k = (n + h / 30f) % 12; return ((l - a * maxOf(-1f, minOf(minOf(k - 3, 9 - k), 1f))) * 255).toInt().coerceIn(0, 255) }
        return Color.rgb(f(0), f(8), f(4))
    }

    /** Цвета виджетов берутся из цветовой темы приложения (выбор цвета в настройках виджета убран). */
    fun themeColors(c: Context): Pair<Int, Int> {
        val sp = c.getSharedPreferences("w", Context.MODE_PRIVATE)
        val h = sp.getInt("seed", 220).toFloat(); val th = sp.getString("th", "auto")
        val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = th == "dark" || th == "amoled" || (th == "auto" && night)
        return if (dark) hsl(h, .70f, .80f) to hsl(h, .60f, .18f) else hsl(h, .58f, .40f) to Color.WHITE
    }

    fun cfg(c: Context, id: Int, kind: String): WCfg {
        val fs = when (kind) { "cp" -> 40; "fc" -> 34; "ck" -> 52; else -> 44 }
        val j = try { JSONObject(savedJson(c, id) ?: "{}") } catch (_: Exception) { JSONObject() }
        val (tb, tf) = themeColors(c)
        return WCfg(
            tb, tf, j.optInt("alpha", 90), j.optInt("rad", 32), j.optInt("fs", fs),
            j.optBoolean("sec", false), j.optBoolean("date", true), j.optBoolean("dv", true), j.optBoolean("fc", true),
            j.optBoolean("city", true), j.optBoolean("desc", true), j.optBoolean("h24", true)
        )
    }

    fun buildAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        kinds.forEach { (k, cls) -> m.getAppWidgetIds(ComponentName(c, cls)).forEach { build(c, m, it, k) } }
    }

    fun refreshAll(c: Context) { buildAll(c); Thread { fetchSync(c); buildAll(c) }.start() }

    fun refresh(c: Context, id: Int) {
        val m = AppWidgetManager.getInstance(c); val k = kindOf(c, id)
        build(c, m, id, k)
        Thread { fetchSync(c); build(c, m, id, k) }.start()
    }

    /**
     * Виджет подстраивается под размер, как в Яндекс Погоде:
     *  1 — одна строка (иконка, температура, город/описание, часы),
     *  2 — квадрат (город, иконка + температура, описание, мин/макс),
     *  3 — широкий и высокий: то же + полоса прогноза по часам (или по дням для «Прогноза»).
     */
    fun build(c: Context, m: AppWidgetManager, id: Int, kind: String) {
        val cfg = cfg(c, id, kind)
        val sp = c.getSharedPreferences("w", Context.MODE_PRIVATE)
        val o = m.getAppWidgetOptions(id)
        val d = c.resources.displayMetrics.density
        // Реальный размер в dp: в портрете ширина = MIN_WIDTH, высота = MAX_HEIGHT; в ландшафте наоборот.
        val port = c.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val wdp = (if (port) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)).let { if (it <= 0) 250 else it }
        val hdp = (if (port) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).let { if (it <= 0) 110 else it }
        val cls = when { kind == "ck" -> 0; hdp < 100 -> 1; wdp < 190 -> 2; else -> 3 }
        val (bwD, bhD) = when (cls) { 0 -> 200 to 100; 1 -> 250 to 70; 2 -> 120 to 120; else -> 250 to 130 }
        val um = (cfg.fs / defFs(kind).toFloat()).coerceIn(0.6f, 1.6f)
        val k = minOf(wdp / bwD.toFloat(), hdp / bhD.toFloat()).coerceIn(0.7f, 1.9f) * um

        // Лёгкая подложка (до ~400 px по длинной стороне): EMUI/HarmonyOS режет тяжёлые картинки в виджетах.
        val sc = 400f / maxOf(wdp, hdp)
        val bw = (wdp * sc).toInt().coerceAtLeast(40); val bh = (hdp * sc).toInt().coerceAtLeast(30)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cfg.bg; alpha = cfg.alpha * 255 / 100 }
        val rr = (cfg.rad * sc).coerceAtMost(minOf(bw, bh) / 2f)
        Canvas(bmp).drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), rr, rr, p)

        val v = RemoteViews(c.packageName, layoutFor(cls))
        v.setImageViewBitmap(R.id.bg, bmp)
        val pd = ((8 + 6 * minOf(k, 1.2f)) * d).toInt()
        v.setViewPadding(R.id.pad, pd, pd, pd, pd)
        val f = (if (cfg.h24) "HH:mm" else "h:mm") + (if (cfg.sec) ":ss" else "")
        v.setCharSequence(R.id.clock, "setFormat24Hour", f)
        v.setCharSequence(R.id.clock, "setFormat12Hour", f)
        fun sz(i: Int, base: Float) = v.setTextViewTextSize(i, TypedValue.COMPLEX_UNIT_SP, maxOf(9f, base * k))
        fun vis(i: Int, b: Boolean) = v.setViewVisibility(i, if (b) View.VISIBLE else View.GONE)
        val gids = intArrayOf(R.id.g0, R.id.g1, R.id.g2, R.id.g3, R.id.g4, R.id.g5)
        val all = intArrayOf(R.id.clock, R.id.date, R.id.ico, R.id.temp, R.id.city, R.id.desc, R.id.hl) + gids
        for (i in all) v.setTextColor(i, cfg.fg)

        if (cls == 0) {
            sz(R.id.clock, 52f); sz(R.id.date, 14f); vis(R.id.date, cfg.date && hdp >= 60)
        } else {
            val clockOn = kind == "cw"
            when (cls) {
                1 -> { sz(R.id.ico, 28f); sz(R.id.temp, 30f); sz(R.id.city, 12f); sz(R.id.desc, 13f); sz(R.id.hl, 12f); sz(R.id.clock, 22f)
                       vis(R.id.clock, clockOn && wdp >= 220); vis(R.id.hl, wdp >= 300) }
                2 -> { sz(R.id.ico, 34f); sz(R.id.temp, 38f); sz(R.id.city, 12f); sz(R.id.desc, 13f); sz(R.id.hl, 12f); sz(R.id.clock, 22f); sz(R.id.date, 12f)
                       vis(R.id.clock, clockOn); vis(R.id.date, false) }
                else -> { sz(R.id.ico, 40f); sz(R.id.temp, 44f); sz(R.id.city, 13f); sz(R.id.desc, 14f); sz(R.id.hl, 12f); sz(R.id.clock, 30f); sz(R.id.date, 12f)
                       vis(R.id.clock, clockOn); vis(R.id.date, clockOn && cfg.date) }
            }
            vis(R.id.city, cfg.city); vis(R.id.desc, cfg.desc)
            v.setTextViewText(R.id.ico, sp.getString("e", "⛅"))
            v.setTextViewText(R.id.temp, sp.getString("t", "--°"))
            v.setTextViewText(R.id.desc, sp.getString("ds", "Загрузка…"))
            v.setTextViewText(R.id.city, "📍 " + sp.getString("n", "Москва"))
            val hi = sp.getString("hi", null); val lo = sp.getString("lo", null)
            v.setTextViewText(R.id.hl, if (hi != null && lo != null) "↑$hi ↓$lo" else "")
            if (cls == 3) {
                val showStrip = hdp >= 125
                vis(R.id.strip, showStrip)
                if (showStrip) {
                    val keys = if (kind == "fc") listOf("f1", "f2", "f3", "f4", "f5") else listOf("h1", "h2", "h3", "h4", "h5", "h6")
                    val n = (wdp / 58).coerceIn(3, keys.size)
                    for (i in 0..5) {
                        val parts = keys.getOrNull(i)?.let { sp.getString(it, null) }?.split("|")
                        if (parts != null && parts.size >= 3 && i < n) {
                            sz(gids[i], 11f); vis(gids[i], true)
                            v.setTextViewText(gids[i], "${parts[0]}\n${parts[1]}\n${parts[2]}")
                        } else vis(gids[i], false)
                    }
                }
            }
        }
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        try { m.updateAppWidget(id, v) } catch (e: Exception) {
            // запасной вариант без картинки, если лаунчер отказался принять RemoteViews
            v.setImageViewResource(R.id.bg, R.drawable.widget_bg)
            try { m.updateAppWidget(id, v) } catch (_: Exception) {}
        }
    }

    private fun get(u: String): String {
        val con = URL(u).openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "PogodaApp/1.0 github.com/Greznish297477")
        con.connectTimeout = 8000; con.readTimeout = 8000
        return con.inputStream.bufferedReader().use { it.readText() }
    }

    private val MODELS = "icon_seamless,gfs_seamless,ecmwf_ifs025"

    private fun fetchAll(urls: List<String>): List<String?> {
        val ex = Executors.newFixedThreadPool(urls.size)
        try {
            return ex.invokeAll(urls.map { u -> Callable<String?> { try { get(u) } catch (_: Exception) { null } } }, 15, TimeUnit.SECONDS)
                .map { f -> try { f.get() } catch (_: Exception) { null } }
        } finally { ex.shutdownNow() }
    }

    /** Температура: отбрасываем выбросы (дальше 4° от медианы) и усредняем. */
    private fun robust(a: List<Double>): Double {
        val s = a.sorted()
        val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        val k = a.filter { abs(it - med) <= 4 }
        return (if (k.isEmpty()) a else k).average()
    }

    /** Обновляет координаты по последнему известному местоположению (если включено автоопределение). */
    @SuppressLint("MissingPermission")
    fun updateLocation(c: Context) {
        val sp = c.getSharedPreferences("w", Context.MODE_PRIVATE)
        if (!sp.getBoolean("auto", true)) return
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val l = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }
                .maxByOrNull { it.time } ?: return
            if (System.currentTimeMillis() - l.time > 6 * 3600_000L) return
            val oldLa = sp.getString("la", null)?.toDoubleOrNull(); val oldLo = sp.getString("lo", null)?.toDoubleOrNull()
            if (oldLa != null && oldLo != null) {
                val r = FloatArray(1); Location.distanceBetween(oldLa, oldLo, l.latitude, l.longitude, r)
                if (r[0] < 2000) return
            }
            var name = "Моё место"
            try {
                @Suppress("DEPRECATION")
                val g = Geocoder(c, Locale("ru")).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                name = g?.locality ?: g?.subAdminArea ?: name
            } catch (_: Exception) {}
            sp.edit().putString("n", name).putString("la", l.latitude.toString()).putString("lo", l.longitude.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Погода из 6 источников (Open-Meteo, MET Norway, wttr.in, DWD ICON, NOAA GFS, ECMWF): усредняем и вычисляем состояние. */
    fun fetchSync(c: Context) {
        updateLocation(c)
        val sp = c.getSharedPreferences("w", Context.MODE_PRIVATE)
        val la = sp.getString("la", "55.75")!!; val lo = sp.getString("lo", "37.62")!!
        try {
            val r = fetchAll(listOf(
                "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,weather_code,cloud_cover,precipitation,snowfall&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=6",
                "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,cloud_cover,precipitation&models=$MODELS&forecast_days=1",
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$la&lon=$lo",
                "https://wttr.in/$la,$lo?format=j1"
            ))
            val root = JSONObject(r[0] ?: throw IllegalStateException("no data"))
            val om = root.getJSONObject("current")
            val t = mutableListOf(om.getDouble("temperature_2m"))
            val cl = mutableListOf(om.getDouble("cloud_cover"))
            val pr = mutableListOf(om.getDouble("precipitation"))
            try {
                val cur = JSONObject(r[1]!!).getJSONObject("current")
                for (m in MODELS.split(",")) {
                    val tt = cur.optDouble("temperature_2m_$m", Double.NaN)
                    if (tt.isNaN()) continue
                    t += tt
                    cur.optDouble("cloud_cover_$m", Double.NaN).takeIf { !it.isNaN() }?.let { cl += it }
                    cur.optDouble("precipitation_$m", Double.NaN).takeIf { !it.isNaN() }?.let { pr += it }
                }
            } catch (_: Exception) {}
            try {
                val d = JSONObject(r[2]!!).getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
                val i = d.getJSONObject("instant").getJSONObject("details")
                t += i.getDouble("air_temperature"); cl += i.getDouble("cloud_area_fraction")
                d.optJSONObject("next_1_hours")?.let { pr += it.getJSONObject("details").getDouble("precipitation_amount") }
            } catch (_: Exception) {}
            try {
                val x = JSONObject(r[3]!!).getJSONArray("current_condition").getJSONObject(0)
                t += x.getDouble("temp_C"); cl += x.getDouble("cloudcover"); pr += x.getDouble("precipMM")
            } catch (_: Exception) {}
            val tm = robust(t)
            val w = describe(classify(tm, cl.average(), pr.average(), om.optDouble("snowfall", 0.0), om.getInt("weather_code")))
            val e = sp.edit().putString("t", Math.round(tm).toString() + "°").putString("e", w.first).putString("ds", w.second)
            val dl = root.getJSONObject("daily")
            val days = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
            for (i in 1..5) {
                val dn = days[LocalDate.parse(dl.getJSONArray("time").getString(i)).dayOfWeek.value - 1]
                e.putString("f$i", "$dn|${describe(dl.getJSONArray("weather_code").getInt(i)).first}|${Math.round(dl.getJSONArray("temperature_2m_max").getDouble(i))}°")
            }
            e.putString("hi", Math.round(dl.getJSONArray("temperature_2m_max").getDouble(0)).toString() + "°")
            e.putString("lo", Math.round(dl.getJSONArray("temperature_2m_min").getDouble(0)).toString() + "°")
            try {
                val hr = root.getJSONObject("hourly"); val ht = hr.getJSONArray("time"); val ct = om.getString("time").substring(0, 13)
                var idx = 0
                for (i in 0 until ht.length()) if (ht.getString(i).startsWith(ct)) { idx = i; break }
                for (n in 1..6) {
                    val i = idx + n
                    if (i < ht.length()) e.putString("h$n", "${ht.getString(i).substring(11, 16)}|${describe(hr.getJSONArray("weather_code").getInt(i)).first}|${Math.round(hr.getJSONArray("temperature_2m").getDouble(i))}°")
                }
            } catch (_: Exception) {}
            e.putLong("ts", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
            if (!sp.contains("t")) sp.edit().putString("ds", "Нет сети").apply()
        }
    }

    private fun classify(t: Double, cloud: Double, precip: Double, snow: Double, base: Int): Int {
        if (base >= 95 || base == 45 || base == 48) return base
        if (precip >= 0.1) {
            val k = if (precip < 0.5) 0 else if (precip < 2.5) 1 else if (precip < 7.6) 2 else 3
            return if (snow > 0 || t <= 0.5) intArrayOf(71, 71, 73, 75)[k] else intArrayOf(51, 61, 63, 65)[k]
        }
        return if (cloud < 15) 0 else if (cloud < 40) 1 else if (cloud < 75) 2 else 3
    }

    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Ясно"
        1 -> "🌤️" to "Малооблачно"
        2 -> "⛅" to "Облачно"
        3 -> "☁️" to "Пасмурно"
        45, 48 -> "🌫️" to "Туман"
        in 51..57 -> "🌦️" to "Морось"
        in 61..67, in 80..82 -> "🌧️" to "Дождь"
        in 71..77, 85, 86 -> "❄️" to "Снег"
        in 95..99 -> "⛈️" to "Гроза"
        else -> "⛅" to "Погода"
    }
}
