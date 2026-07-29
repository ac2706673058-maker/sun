package com.murmur.app

import android.app.Application

class MurmurApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MurmurApp
            private set
    }
}
