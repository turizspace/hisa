package com.hisa.base

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface BaseRepository<T, ID> {
    suspend fun get(id: ID): T?
    suspend fun getAll(): List<T>
    suspend fun create(item: T): T
    suspend fun update(item: T): T
    suspend fun delete(id: ID)

    fun observe(id: ID): Flow<T?>
    fun observeAll(): Flow<List<T>>
}

abstract class BaseNostrRepository<T, ID> : BaseRepository<T, ID> {
    protected val items = MutableStateFlow<Map<ID, T>>(emptyMap())

    override suspend fun get(id: ID): T? = items.value[id]
    override suspend fun getAll(): List<T> = items.value.values.toList()
    override suspend fun create(item: T): T = item
    override suspend fun update(item: T): T = item
    override suspend fun delete(id: ID) {
        items.value = items.value - id
    }

    override fun observe(id: ID): Flow<T?> = MutableStateFlow(items.value[id])
    override fun observeAll(): Flow<List<T>> = MutableStateFlow(items.value.values.toList())

    protected fun updateCache(id: ID, item: T) {
        items.value = items.value + (id to item)
    }

    protected fun removeFromCache(id: ID) {
        items.value = items.value - id
    }

    protected fun clearCache() {
        items.value = emptyMap()
    }
}
