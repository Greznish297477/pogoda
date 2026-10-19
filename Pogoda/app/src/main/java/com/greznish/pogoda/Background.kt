package com.greznish.pogoda

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Запускает фоновое обновление погоды при старте процесса (в т.ч. когда ожил только виджет). */
class PogodaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Background.schedule(this)
    }
}

object Background {
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<WeatherWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("weather", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class WeatherWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result = try {
        Widgets.fetchSync(applicationContext)
        Widgets.buildAll(applicationContext)
        Result.success()
    } catch (e: Exception) { Result.retry() }
}
