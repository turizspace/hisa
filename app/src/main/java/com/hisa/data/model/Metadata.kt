package com.hisa.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class Metadata(
    val name: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val banner: String? = null,
    val website: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    val lud16: String? = null,
    val lud06: String? = null,
    @SerialName("is_deleted")
    val isDeleted: Boolean = false
)