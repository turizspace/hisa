package com.hisa.ui.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController

const val NAV_RESULT_UPLOADED_MEDIA_URL = "uploaded_media_url"
const val NAV_RESULT_UPLOAD_TARGET = "upload_target"
const val NAV_STATE_COMPOSE_DRAFT = "compose_draft"
const val NAV_ARG_STALL_ID = "stallId"
const val NAV_ARG_STALL_NAME = "stallName"
const val NAV_ARG_STALL_PICTURE = "stallPicture"

data class StallArgs(
    val stallId: String,
    val stallName: String,
    val stallPicture: String
)

data class UploadResultPayload(
    val target: String,
    val urls: List<String>
)

fun NavController?.prepareUploadResult(target: String) {
    val entries = listOf(this?.currentBackStackEntry, this?.previousBackStackEntry)
    entries.forEach { entry ->
        entry?.savedStateHandle?.clearUploadResult()
        entry?.savedStateHandle?.set(NAV_RESULT_UPLOAD_TARGET, target)
    }
}

fun SavedStateHandle.clearUploadResult() {
    remove<String>(NAV_RESULT_UPLOADED_MEDIA_URL)
    remove<String>(NAV_RESULT_UPLOAD_TARGET)
}

fun SavedStateHandle.setUploadedMediaResult(rawUrls: String) {
    set(NAV_RESULT_UPLOADED_MEDIA_URL, rawUrls)
}

fun NavController?.deliverUploadResult(rawUrls: String) {
    listOf(this?.previousBackStackEntry, this?.currentBackStackEntry).forEach { entry ->
        entry?.savedStateHandle?.setUploadedMediaResult(rawUrls)
    }
}

fun SavedStateHandle.consumeUploadedMediaUrls(expectedTarget: String): List<String> {
    return consumeUploadedMediaResult()
        ?.takeIf { it.target == expectedTarget }
        ?.urls
        .orEmpty()
}

fun SavedStateHandle.consumeUploadedMediaResult(): UploadResultPayload? {
    val target = get<String>(NAV_RESULT_UPLOAD_TARGET)?.takeIf { it.isNotBlank() } ?: return null
    val raw = get<String>(NAV_RESULT_UPLOADED_MEDIA_URL)
    clearUploadResult()
    val urls = raw
        ?.split('\n')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return if (urls.isEmpty()) null else UploadResultPayload(target = target, urls = urls)
}

fun SavedStateHandle.setStallArgs(stallId: String, stallName: String, stallPicture: String?) {
    set(NAV_ARG_STALL_ID, stallId)
    set(NAV_ARG_STALL_NAME, stallName)
    set(NAV_ARG_STALL_PICTURE, stallPicture.orEmpty())
}

fun SavedStateHandle.getStallArgs(): StallArgs? {
    val stallId = get<String>(NAV_ARG_STALL_ID)?.takeIf { it.isNotBlank() } ?: return null
    val stallName = get<String>(NAV_ARG_STALL_NAME)?.takeIf { it.isNotBlank() } ?: return null
    return StallArgs(
        stallId = stallId,
        stallName = stallName,
        stallPicture = get<String>(NAV_ARG_STALL_PICTURE).orEmpty()
    )
}
