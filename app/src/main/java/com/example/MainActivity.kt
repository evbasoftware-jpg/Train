package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.TrainTrackerScreen
import com.example.ui.theme.SmartTrainTrackerTheme
import com.example.viewmodel.TrainTrackerViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: TrainTrackerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      SmartTrainTrackerTheme {
        TrainTrackerScreen(viewModel = viewModel)
      }
    }
  }
}
