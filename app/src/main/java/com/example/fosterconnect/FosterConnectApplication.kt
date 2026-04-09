package com.example.fosterconnect

import android.app.Application
import com.example.fosterconnect.data.KittenRepository

class FosterConnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KittenRepository.init(this)
    }
}
