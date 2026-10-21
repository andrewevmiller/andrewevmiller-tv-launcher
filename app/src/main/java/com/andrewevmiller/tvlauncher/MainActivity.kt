package com.andrewevmiller.tvlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.placeholderRows
import com.andrewevmiller.tvlauncher.theme.FieldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher is the bottom of the stack; Back has nowhere to go.
        onBackPressedDispatcher.addCallback(this) { }
        setContent {
            FieldTheme {
                HomeScreen(rows = placeholderRows(onLaunch = {}))
            }
        }
    }
}
