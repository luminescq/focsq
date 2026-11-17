package com.csqtt.client.ui.v2.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

// Пароли туннеля и SSH-ключи не должны предлагаться менеджерам паролей.
// Вызывать внутри контента диалога/шита: LocalView относится к окну вызывающей композиции.
@Composable
fun SuppressAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val targets = listOf(view, view.rootView).distinct()
        val previous = targets.map { it to it.importantForAutofill }
        targets.forEach { it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        onDispose { previous.forEach { (target, flag) -> target.importantForAutofill = flag } }
    }
}
