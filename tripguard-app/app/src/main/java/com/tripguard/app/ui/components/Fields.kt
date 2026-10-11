package com.tripguard.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tripguard.app.ui.theme.BorderColor
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.TextSecondary

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Navy,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            enabled = enabled,
            isError = error != null,
            placeholder = placeholder?.let {
                { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium) }
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = BorderColor,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        val helper = error ?: supportingText
        if (!helper.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else TextSecondary,
            )
        }
    }
}

/** Read-only field used for fixed scope values (nationality, passport type, purpose). */
@Composable
fun FixedField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Navy,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = {},
            enabled = false,
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                disabledBorderColor = BorderColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledTextColor = Navy,
            ),
        )
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
