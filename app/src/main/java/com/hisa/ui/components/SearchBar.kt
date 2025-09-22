package com.hisa.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onClearSearch: () -> Unit = {},
    placeholder: String = "Search services...",
    onSearch: (String) -> Unit = {},
    // Optional leading content to allow placing custom icons (e.g. menu) inside the search field
    leadingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth().padding(8.dp)
) {
    var isSearchActive by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            onValueChange(newValue)
            isSearchActive = newValue.isNotEmpty()
            onSearch(newValue)
        },
        placeholder = { Text(placeholder) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
        leadingIcon = {
            if (leadingContent != null) {
                leadingContent()
            } else {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { 
                        onValueChange("")
                        isSearchActive = false
                        onClearSearch()
                    }
                ) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.outline
        )
    )
}