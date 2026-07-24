package com.hisa.data.repository

import com.hisa.base.BaseNostrRepository
import com.hisa.data.model.ServiceListing

class ServiceCacheRepository : BaseNostrRepository<ServiceListing, String>() {
    fun cache(service: ServiceListing) {
        updateCache(service.eventId, service)
    }

    fun getCached(eventId: String): ServiceListing? = items.value[eventId]

    fun getAllCached(): List<ServiceListing> = items.value.values.toList()

    fun clear() {
        clearCache()
    }
}
