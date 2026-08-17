package com.hisa.ui.navigation

import androidx.navigation.NavController
import com.hisa.data.model.Stall
import org.json.JSONArray
import org.json.JSONObject

const val NAV_RESULT_EDIT_STALL_PAYLOAD = "edit_stall_payload"

fun NavController.navigateToStallEditor(stall: Stall) {
    val payload = JSONObject().apply {
        put("id", stall.id)
        put("name", stall.name)
        put("description", stall.description)
        put("currency", stall.currency)
        put("categories", JSONArray(stall.categories))
        put("shipping", JSONArray().apply {
            stall.shippingZones.forEach { zone ->
                put(JSONObject().apply {
                    put("id", zone.id)
                    put("name", zone.name)
                    put("cost", zone.cost)
                    put("regions", JSONArray(zone.regions))
                })
            }
        })
    }

    currentBackStackEntry?.savedStateHandle?.set(NAV_RESULT_EDIT_STALL_PAYLOAD, payload.toString())
    navigate(Routes.CREATE_SERVICE)
}
