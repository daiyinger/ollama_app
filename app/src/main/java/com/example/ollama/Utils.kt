package com.example.ollama

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun getCurrentTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    return sdf.format(Date())
}
