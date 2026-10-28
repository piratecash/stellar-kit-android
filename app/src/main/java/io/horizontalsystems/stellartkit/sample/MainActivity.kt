package io.horizontalsystems.stellartkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.horizontalsystems.stellartkit.sample.ui.theme.StellartKitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StellartKitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val initError = App.initError
                    if (initError != null) {
                        Text("Stellar kit failed to start: ${initError.message}")
                    } else {
                        NavigationStack()
                    }
                }
            }
        }
    }
}
