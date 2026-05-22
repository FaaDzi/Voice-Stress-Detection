package com.example.myapplication.core

import android.content.Context
import com.example.myapplication.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object AppDialogs {
    fun builder(context: Context): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(context)
    }
}
