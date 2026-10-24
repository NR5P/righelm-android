package com.righelm.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class RigHelmApp : Application() {
    lateinit var rig: RigController
        private set

    override fun onCreate() {
        super.onCreate()
        rig = RigController(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = rig.enteredForeground()
            override fun onStop(owner: LifecycleOwner) = rig.enteredBackground()
        })
    }
}
