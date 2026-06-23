package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.BrandLogo
import com.naarni.service.ui.theme.BrandGradient

/**
 * Phone-first, passwordless login (never email — [[feedback-phone-login]]) using
 * Naarni OTP SSO. Two steps in one screen: enter mobile → enter the 6-digit code.
 * No password to remember — ideal for non-tech-savvy field users.
 */
@Composable
fun LoginScreen(vm: AppViewModel) {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    val state = vm.ui
    val phoneValid = phone.length == 10
    val otpValid = otp.length in 4..6

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Gradient hero
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(BrandGradient),
                    RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandLogo(icon = Icons.Filled.DirectionsBus, size = 84)
                Spacer(Modifier.height(18.dp))
                Text("Naarni Service", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text(
                    "Field service, simplified",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        // Form card, overlapping the hero
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-28).dp)
                .padding(horizontal = 20.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!state.otpSent) {
                    // ── Step 1: phone ──
                    Text("Sign in", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) phone = it },
                        label = { Text("Mobile number") },
                        prefix = { Text("+91 ") },
                        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ErrorBox(state.error)
                    Button(
                        onClick = { vm.requestOtp(phone) },
                        enabled = phoneValid && !state.loading,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.height(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Send code", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    // ── Step 2: OTP ──
                    Text("Enter code", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Sent to +91 ${state.otpPhone}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) otp = it },
                        label = { Text("6-digit code") },
                        leadingIcon = { Icon(Icons.Filled.Pin, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ErrorBox(state.error)
                    Button(
                        onClick = { vm.verifyOtp(otp) },
                        enabled = otpValid && !state.loading,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.height(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Verify & sign in", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    TextButton(
                        onClick = { otp = ""; vm.resetOtp() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Change number")
                    }
                }
            }
        }

        Text(
            "Use your registered mobile number",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBox(error: String?) {
    error ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Text(
            error,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}
