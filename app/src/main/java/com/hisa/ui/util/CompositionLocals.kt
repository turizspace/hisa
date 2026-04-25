package com.hisa.ui.util

import androidx.compose.runtime.compositionLocalOf
import com.hisa.data.repository.ProfileRepository
import com.hisa.ui.util.ProfileMetaUtil

val LocalProfileMetaUtil = compositionLocalOf<ProfileMetaUtil> {
    error("No ProfileMetaUtil provided")
}

val LocalProfileRepository = compositionLocalOf<ProfileRepository> {
    error("No ProfileRepository provided")
}
