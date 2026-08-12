package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.BrandLogo
import com.naarni.service.ui.theme.BrandGradient
import kotlinx.coroutines.delay

private const val OTP_LENGTH = 4
private const val RESEND_SECONDS = 30

/**
 * Phone-first, passwordless login (never email — [[feedback-phone-login]]) using
 * Naarni OTP SSO. Two steps: enter mobile → enter the 6-digit code. The code step
 * has a segmented input, auto-submits on the 6th digit, and a resend countdown —
 * so a non-tech-savvy field user just types and is in.
 */
@Composable
fun LoginScreen(vm: AppViewModel) {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    val state = vm.ui
    val phoneValid = phone.length == 10

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
                .height(280.dp)
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
                    PhoneStep(
                        phone = phone,
                        onPhoneChange = { phone = it },
                        canSubmit = phoneValid && !state.loading,
                        loading = state.loading,
                        error = state.error,
                        onSend = { vm.requestOtp(phone) },
                    )
                } else {
                    OtpStep(
                        otpPhone = state.otpPhone,
                        otp = otp,
                        onOtpChange = {
                            otp = it
                            if (it.length == OTP_LENGTH && !state.loading) vm.verifyOtp(it)
                        },
                        loading = state.loading,
                        error = state.error,
                        onVerify = { vm.verifyOtp(otp) },
                        onResend = { otp = ""; vm.requestOtp(state.otpPhone) },
                        onChangeNumber = { otp = ""; vm.resetOtp() },
                    )
                }

                // Debug builds only — OTP is issued by the Naarni backend, so a
                // build pointed at a local bench has no other way in.
                if (com.naarni.service.BuildConfig.DEBUG) DevSignIn(vm)
            }
        }

        Text(
            "Use your registered mobile number",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhoneStep(
    phone: String,
    onPhoneChange: (String) -> Unit,
    canSubmit: Boolean,
    loading: Boolean,
    error: String?,
    onSend: () -> Unit,
) {
    Text("Sign in", style = MaterialTheme.typography.titleLarge)
    Text(
        "We'll text you a one-time code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = phone,
        onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) onPhoneChange(it) },
        label = { Text("Mobile number") },
        prefix = { Text("+91 ") },
        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorBox(error)
    Button(
        onClick = onSend,
        enabled = canSubmit,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.height(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text("Send code", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OtpStep(
    otpPhone: String,
    otp: String,
    onOtpChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onChangeNumber, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Change number", Modifier.size(20.dp))
            Spacer(Modifier.size(4.dp))
            Text("Change")
        }
    }
    Text("Enter code", style = MaterialTheme.typography.titleLarge)
    Text(
        "Sent to +91 $otpPhone",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OtpInput(value = otp, onValueChange = onOtpChange, enabled = !loading)

    ErrorBox(error)

    Button(
        onClick = onVerify,
        enabled = otp.length == OTP_LENGTH && !loading,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.height(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text("Verify & sign in", style = MaterialTheme.typography.labelLarge)
        }
    }

    ResendRow(onResend = onResend)
}

/** Segmented OTP field: a hidden text field drives 6 visible cells. */
@Composable
private fun OtpInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    length: Int = OTP_LENGTH,
) {
    val focusRequester = remember { FocusRequester() }
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= length && it.all(Char::isDigit)) onValueChange(it) },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = TextStyle(color = Color.Transparent),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        decorationBox = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(length) { i ->
                    val char = value.getOrNull(i)?.toString() ?: ""
                    val active = i == value.length
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.medium,
                            )
                            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(char, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** "Resend code" gated by a countdown so a slow SMS doesn't trigger a re-send storm. */
@Composable
private fun ResendRow(onResend: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf(RESEND_SECONDS) }
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (secondsLeft > 0) {
            Text(
                "Resend code in 0:%02d".format(secondsLeft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(onClick = { secondsLeft = RESEND_SECONDS; onResend() }) {
                Text("Resend code")
            }
        }
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

/**
 * Debug-only password sign-in.
 *
 * Collapsed behind a text button so it never competes with the real OTP flow.
 * Gated on BuildConfig.DEBUG at the call site, so it is absent from release.
 */
@Composable
private fun DevSignIn(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Dev sign-in", style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.devLogin(phone, password) },
            enabled = phone.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
    }
}
