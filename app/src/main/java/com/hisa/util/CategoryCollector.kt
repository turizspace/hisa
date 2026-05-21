package com.hisa.util

import timber.log.Timber

object CategoryCollector {
    private val categories = mutableSetOf<String>()

    @Synchronized
    fun collect(categoriesToAdd: Iterable<String>) {
        categories.addAll(categoriesToAdd.filter { it.isNotBlank() })
    }

    @Synchronized
    fun allCategories(): List<String> = categories.sorted()

    @Synchronized
    fun logAll(context: String) {
        Timber.i("%s unique categories=%s", context, allCategories())
    }
}
