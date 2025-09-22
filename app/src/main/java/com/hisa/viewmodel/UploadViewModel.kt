package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.blossom.BlossomClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val blossomClient: BlossomClient
) : ViewModel() {

    sealed class UploadState {
        object Idle : UploadState()
        data class Uploading(val bytesSent: Long, val totalBytes: Long) : UploadState()
        data class Success(val url: String) : UploadState()
        data class Error(val message: String?) : UploadState()
    }

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state

    fun uploadFile(
        file: File,
        contentType: String,
        pubkeyHex: String,
        privKey: ByteArray,
        endpoint: String = "upload",
    insertUrlCallback: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _state.value = UploadState.Uploading(0, file.length())
            val result = blossomClient.uploadFile(file, contentType, pubkeyHex, privKey, endpoint) { sent, total ->
                _state.value = UploadState.Uploading(sent, total)
            }
            if (result.ok) {
                // Attempt to parse URL from structured response (prefer NIP-94) via BlossomClient helper.
                val body = result.body
                val parsed = blossomClient.parseUploadUrl(body)
                val url = parsed ?: if (!body.isNullOrBlank() && body.contains("http")) body else "${blossomClient.baseUrl}/$endpoint/${file.name}"
                _state.value = UploadState.Success(url)
                insertUrlCallback?.invoke(url)
            } else {
                _state.value = UploadState.Error("Upload failed: ${result.statusCode} ${result.body}")
            }
        }
    }

    fun reset() {
        _state.value = UploadState.Idle
    }
}
