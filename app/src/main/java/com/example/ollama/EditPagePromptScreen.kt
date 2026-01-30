package com.example.ollama

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private const val DEFAULT_PAGE_PROMPT = "The following image is a page from a document. Please identify the text on this page and return the recognized result."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPagePromptScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val activeProfile by viewModel.activeProfile.collectAsState()
    var pagePrompt by remember(activeProfile) { mutableStateOf(activeProfile?.pagePrompt ?: DEFAULT_PAGE_PROMPT) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Page Prompt") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextField(
                value = pagePrompt,
                onValueChange = { pagePrompt = it },
                label = { Text("Page Prompt") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 10
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        pagePrompt = DEFAULT_PAGE_PROMPT
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset")
                }
                Button(
                    onClick = {
                        viewModel.setPagePrompt(pagePrompt)
                        onNavigateBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}
