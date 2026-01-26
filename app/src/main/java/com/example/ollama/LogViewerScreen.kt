package com.example.ollama

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ollama.ui.theme.OllamaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
) {
    val logContent by viewModel.logContent.collectAsState()
    val runningModels by viewModel.runningModels.collectAsState()
    val runningModelsError by viewModel.runningModelsError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Viewer") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearLogFile() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Log")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (logContent.isNotEmpty()) {
                Text(text = logContent, modifier = Modifier.padding(16.dp))
            } else {
                runningModelsError?.let {
                    if (it == "Loading...") {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    } else {
                        Text(text = "Error: $it", modifier = Modifier.padding(16.dp))
                    }
                } ?: run {
                    if (runningModels.isEmpty()) {
                        Text(text = "No running models found.", modifier = Modifier.padding(16.dp))
                    } else {
                        runningModels.forEach { model ->
                            Text(text = "${model.name} (Expires in: ${model.expirationTime})", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LogViewerScreenPreview() {
    OllamaTheme {
        LogViewerScreen(
            viewModel = MainViewModel(LocalContext.current.applicationContext as Application),
            onNavigateBack = {}
        )
    }
}
