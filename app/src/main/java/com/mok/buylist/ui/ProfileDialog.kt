package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette

@Composable
fun ProfileDialog(initialName: String, required: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    val valid = name.isNotBlank()
    fun submit() { if (valid) onSave(name.trim()) }
    AppSheet(onDismissRequest = onDismiss, required = required, containerColor = p.sheet,
        title = { SheetHeader(if (required) stringResource(R.string.profile_title) else "Ваше имя") },
        minBodyHeight = 240.dp,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SheetTextField(name, { name = it.take(30).replace('\n', ' ') }, "Имя",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }), placeholder = stringResource(R.string.profile_name_hint))
            }
        },
        confirmButton = { SheetActionButton(onClick = { submit() }, enabled = valid) { Text(stringResource(if (required) R.string.profile_continue else R.string.profile_save)) } },
        showSecondaryAction = false,
        dismissButton = if (required) null else ({ SheetActionButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }),
    )
}
