package com.hisa.base

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface BaseRepository<T, ID> {
    suspend fun get(id: ID): T?
    suspend fun getAll(): List<T>
    suspend fun create(item: T): T
    suspend fun update(item: T): T
    suspend fun delete(id: ID)
    
    // For real-time updates
    fun observe(id: ID): Flow<T?>
    fun observeAll(): Flow<List<T>>
}

abstract class BaseNostrRepository<T, ID> : BaseRepository<T, ID> {
    protected val items = MutableStateFlow<Map<ID, T>>(emptyMap())

    override fun observe(id: ID): Flow<T?> = MutableStateFlow(items.value[id])
    
    override fun observeAll(): Flow<List<T>> = MutableStateFlow(items.value.values.toList())
    
    protected suspend fun updateCache(id: ID, item: T) {
        items.value = items.value + (id to item)
    }
    
    protected suspend fun removeFromCache(id: ID) {
        items.value = items.value - id
    }
    
    protected suspend fun clearCache() {
        items.value = emptyMap()
    }
}
