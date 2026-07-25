package com.yishaik.homeapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ActivationScreen(
    loading: Boolean,
    error: String?,
    approvalRequestId: String?,
    onActivate: (code: String, recoveryCode: String?) -> Unit,
    onCheckApproval: (code: String, requestId: String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var showRecovery by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                androidx.compose.material3.Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(24.dp).size(52.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("היומן שלנו", style = MaterialTheme.typography.headlineLarge)
            Text("לוח שנה, פתקים ורשימות משותפים", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Card {
                Column(Modifier.padding(20.dp)) {
                    Text("קוד הפעלה אישי", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("הקוד מזהה את החשבון ומחבר מכשיר אחד מאושר.")
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(12) },
                        label = { Text("קוד הפעלה") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showRecovery) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = recoveryCode,
                            onValueChange = { recoveryCode = it.take(64) },
                            label = { Text("קוד שחזור למכשיר חדש") },
                            singleLine = true,
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = { showRecovery = !showRecovery }, enabled = !loading) {
                        Text(if (showRecovery) "הסתרת קוד שחזור" else "הפעלה במכשיר חדש עם קוד שחזור")
                    }
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (approvalRequestId != null) {
                        Text(
                            "נשלחה בקשת אישור למשתמש השני. לאחר האישור לחצו על בדיקה מחדש.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onCheckApproval(code, approvalRequestId) },
                            enabled = !loading && code.length >= 4,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("בדיקה מחדש") }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { onActivate(code, recoveryCode.takeIf(String::isNotBlank)) },
                        enabled = !loading && code.length >= 4,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("המשך")
                    }
                }
            }
        }
    }
}

@Composable
fun PinSetupScreen(onSet: (String) -> Unit) = PinScreen(
    title = "הגדרת PIN",
    subtitle = "בחרו PIN בן 4–8 ספרות",
    button = "שמירה",
    onSubmit = onSet,
)

@Composable
fun PinUnlockScreen(onUnlock: (String) -> Unit) = PinScreen(
    title = "פתיחת האפליקציה",
    subtitle = "הזינו את ה־PIN האישי",
    button = "פתיחה",
    onSubmit = onUnlock,
)

@Composable
private fun PinScreen(title: String, subtitle: String, button: String, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(8) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(pin) }, enabled = pin.length >= 4) { Text(button) }
    }
}
