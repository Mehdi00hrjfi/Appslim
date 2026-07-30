package com.appslim.analyzer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** 把 Drawable 转成可传递给 Compose Image 的 Bitmap */
fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp
}
