package com.example.fosterconnect

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.fosterconnect.data.KittenRepository

class FosterConnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        KittenRepository.init(this)
    }
}
