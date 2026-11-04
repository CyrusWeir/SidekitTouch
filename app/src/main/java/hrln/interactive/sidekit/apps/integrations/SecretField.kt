package hrln.interactive.sidekit.apps.integrations

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme

@Composable
fun SecretField(value: String, change: (String) -> Unit, label: String) {
    SidekitCard(Modifier.fillMaxWidth()) {
      Column {
        SidekitText(label, style = SidekitTheme.typography.caption)
        BasicTextField(value, change, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = SidekitTheme.typography.bodySmall.copy(color = SidekitTheme.colors.surfaceA6),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation())
      }
    }
}
