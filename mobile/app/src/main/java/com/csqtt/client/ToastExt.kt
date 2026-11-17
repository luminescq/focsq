package com.csqtt.client

import android.content.Context
import android.view.Gravity
import android.widget.Toast

fun Context.showRaisedToast(message: String, length: Int = Toast.LENGTH_SHORT) {
    val toast = Toast.makeText(this.applicationContext, message, length)
    val yOffset = (180 * resources.displayMetrics.density).toInt()
    toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, yOffset)
    toast.show()
}
