package com.naarni.service

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.core.feedback.rememberFeedback
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.navigation.MainShell
import com.naarni.service.ui.screens.LoginScreen
import com.naarni.service.ui.theme.NaarniTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NaarniTheme {
                CompositionLocalProvider(LocalFeedback provides rememberFeedback()) {
                    val vm: AppViewModel = viewModel()
                    if (vm.ui.loggedIn) MainShell(vm) else LoginScreen(vm)
                }
            }
        }
    }
}
