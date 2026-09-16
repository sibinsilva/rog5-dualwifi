package me.phh.rog5.dualwifi

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Automatically adopt user's system wallpaper and theme palette (Material You / Dynamic Color)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
