package com.studioasmr.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val teks = TextView(this)
        teks.text = "Studio ASMR siap"
        teks.textSize = 24f
        teks.setPadding(48, 96, 48, 48)
        setContentView(teks)
    }
}
