package com.bfyy.dentalime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bfyy.dentalime.ui.theme.DentalIMETheme

class MainActivity : ComponentActivity() {
    private var keyboardState by mutableStateOf(KeyboardState.DISABLED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DentalIMETheme {
                SetupScreen(
                    keyboardState = keyboardState,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onSelect = {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showInputMethodPicker()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKeyboardState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshKeyboardState()
    }

    private fun refreshKeyboardState() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val thisIme = manager.enabledInputMethodList.firstOrNull {
            it.packageName == packageName && it.serviceName == DentalImeService::class.java.name
        }
        keyboardState = when {
            thisIme == null -> KeyboardState.DISABLED
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == thisIme.id ->
                KeyboardState.SELECTED
            else -> KeyboardState.ENABLED
        }
    }
}

private enum class KeyboardState { DISABLED, ENABLED, SELECTED }

@Composable
private fun SetupScreen(
    keyboardState: KeyboardState,
    onEnable: () -> Unit,
    onSelect: () -> Unit
) {
    var testText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            when (keyboardState) {
                KeyboardState.DISABLED -> stringResource(R.string.setup_status_disabled)
                KeyboardState.ENABLED -> stringResource(R.string.setup_status_enabled)
                KeyboardState.SELECTED -> stringResource(R.string.setup_status_selected)
            }
        )
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.enable_keyboard))
        }
        Button(
            onClick = onSelect,
            enabled = keyboardState != KeyboardState.DISABLED,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.select_keyboard))
        }
        Text(stringResource(R.string.test_input), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text(stringResource(R.string.test_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
