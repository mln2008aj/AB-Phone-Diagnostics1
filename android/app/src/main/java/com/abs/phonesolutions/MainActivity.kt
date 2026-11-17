package com.abs.phonesolutions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.abs.phonesolutions.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot(vm) }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Android Settings after granting (or withdrawing) file access.
        vm.refreshPermissions()
    }
}
