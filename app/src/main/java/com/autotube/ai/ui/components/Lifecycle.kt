package com.autotube.ai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Calls [onEvent] every time the current lifecycle owner reaches [event] (e.g. ON_RESUME). */
@Composable
fun OnLifecycleEvent(event: Lifecycle.Event, onEvent: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onEvent)
    DisposableEffect(owner, event) {
        val observer = LifecycleEventObserver { _, e -> if (e == event) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
