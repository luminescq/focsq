package com.csqtt.client.ui

import java.util.Locale

internal fun <T> selectedApplicationsFirst(
    apps: List<T>,
    selected: Set<String>,
    packageName: (T) -> String,
    label: (T) -> String,
): List<T> = apps.sortedWith(
    compareByDescending<T> { packageName(it) in selected }
        .thenBy { label(it).lowercase(Locale.ROOT) }
        .thenBy { packageName(it) },
)
