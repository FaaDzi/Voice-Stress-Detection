package com.example.myapplication.core

import android.os.SystemClock
import android.view.View

private const val DEFAULT_DEBOUNCE_MS = 700L

fun View.setDebouncedClickListener(
    debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    onClick: (View) -> Unit
) {
    var lastClickAt = 0L
    setOnClickListener { view ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < debounceMs) return@setOnClickListener
        lastClickAt = now
        onClick(view)
    }
}
