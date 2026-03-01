package com.example.ollama

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ollama.ui.theme.OllamaTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToEditPagePrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val logFiles by viewModel.logFiles.collectAsState()
    var showLogFilesDialog by remember { mutableStateOf(false) }

    var selectedProfile by remember(activeProfile) { mutableStateOf(activeProfile ?: SettingsManager.defaultProfile) }
    var name by remember(selectedProfile) { mutableStateOf(selectedProfile.name) }
    var apiHost by remember(selectedProfile) { mutableStateOf(selectedProfile.apiHost) }
    var apiPath by remember(selectedProfile) { mutableStateOf(selectedProfile.apiPath) }
    var psPath by remember(selectedProfile) { mutableStateOf(selectedProfile.psPath) }
    var tagsPath by remember(selectedProfile) { mutableStateOf(selectedProfile.tagsPath) }
    var model by remember(selectedProfile) { mutableStateOf(selectedProfile.model) }
    var apiKey by remember(selectedProfile) { mutableStateOf(selectedProfile.apiKey) }
    var apiMode by remember(selectedProfile) { mutableStateOf(selectedProfile.apiMode) }
    var visionFamilies by remember(selectedProfile) { mutableStateOf(selectedProfile.visionFamilies) }
    var checkImageProcessing by remember(selectedProfile) { mutableStateOf(selectedProfile.checkImageProcessing) }
    var imageQuality by remember(selectedProfile) { mutableStateOf(selectedProfile.imageQuality) }
    var pdfScale by remember(selectedProfile) { mutableStateOf(selectedProfile.pdfScale) }
    var contextLength by remember(selectedProfile) { mutableStateOf(selectedProfile.contextLength) }
    val enableSessionLogging by viewModel.enableSessionLogging.collectAsState()

    var passwordVisible by remember { mutableStateOf(false) }
    var isApiModeExpanded by remember { mutableStateOf(false) }
    var isProfileSelectorExpended by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    val settingsJson = viewModel.exportSettings()
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer ->
                            writer.write(settingsJson)
                        }
                    }
                    Toast.makeText(context, "Settings exported successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to export settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            val settingsJson = reader.readText()
                            showImportConfirmDialog = settingsJson
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to read settings file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    if (showImportConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = null },
            title = { Text("Confirm Import") },
            text = { Text("This will overwrite your current settings. Are you sure you want to continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog?.let { settingsJson ->
                            viewModel.importSettings(settingsJson)
                            Toast.makeText(context, "Settings imported successfully", Toast.LENGTH_SHORT).show()
                        }
                        showImportConfirmDialog = null
                    }
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportConfirmDialog = null }
                ) { Text("Cancel") }
            }
        )
    }

    val ollamaMode = stringResource(R.string.ollama)
    val openAiMode = stringResource(R.string.openai_compatible)
    val apiModes = listOf(ollamaMode, openAiMode)

    val hasModelSettingsChanges = name != selectedProfile.name ||
            apiHost != selectedProfile.apiHost ||
            apiPath != selectedProfile.apiPath ||
            model != selectedProfile.model ||
            apiKey != selectedProfile.apiKey ||
            apiMode != selectedProfile.apiMode ||
            visionFamilies != selectedProfile.visionFamilies ||
            checkImageProcessing != selectedProfile.checkImageProcessing ||
            imageQuality != selectedProfile.imageQuality ||
            pdfScale != selectedProfile.pdfScale ||
            contextLength != selectedProfile.contextLength

    val hasOverallChanges = hasModelSettingsChanges || psPath != selectedProfile.psPath || tagsPath != selectedProfile.tagsPath

    val saveModelSettings = {
        if (name.isBlank()) {
            Toast.makeText(context, "Profile name cannot be empty", Toast.LENGTH_SHORT).show()
        } else {
            val updatedProfile = selectedProfile.copy(
                name = name,
                apiHost = apiHost,
                apiPath = apiPath,
                model = model,
                apiKey = apiKey,
                apiMode = apiMode,
                visionFamilies = visionFamilies,
                checkImageProcessing = checkImageProcessing,
                imageQuality = imageQuality,
                pdfScale = pdfScale,
                contextLength = contextLength
            )
            if (profiles.any { it.name == name }) {
                viewModel.updateProfile(updatedProfile)
            } else {
                viewModel.addProfile(updatedProfile)
                viewModel.setActiveProfile(updatedProfile.name)
            }
        }
    }

    val saveAllChanges = {
        val updatedProfile = selectedProfile.copy(
            name = name,
            apiHost = apiHost,
            apiPath = apiPath,
            psPath = psPath,
            tagsPath = tagsPath,
            model = model,
            apiKey = apiKey,
            apiMode = apiMode,
            visionFamilies = visionFamilies,
            checkImageProcessing = checkImageProcessing,
            imageQuality = imageQuality,
            pdfScale = pdfScale,
            contextLength = contextLength
        )
        if (profiles.any { it.name == name }) {
            viewModel.updateProfile(updatedProfile)
        } else {
            viewModel.addProfile(updatedProfile)
            viewModel.setActiveProfile(updatedProfile.name)
        }
    }

    val attemptNavigateBack = {
        if (hasOverallChanges) {
            showConfirmDialog = true
        } else {
            onNavigateBack()
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("未保存的更改") },
            text = { Text("您要保存更改吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveAllChanges()
                        showConfirmDialog = false
                        onNavigateBack()
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onNavigateBack()
                    }
                ) { Text("放弃") }
            }
        )
    }

    if (showLogFilesDialog) {
        Dialog(
            onDismissRequest = { showLogFilesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)) {
                LazyColumn {
                    items(logFiles, key = { it.file.absolutePath }) { logFileInfo ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                if (it == SwipeToDismissBoxValue.EndToStart) {
                                    viewModel.deleteLogFile(logFileInfo.file)
                                    return@rememberSwipeToDismissBoxState true
                                }
                                false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                val color = when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.EndToStart -> Color.Red
                                    else -> Color.Transparent
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(color)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.readLogFile(logFileInfo.file)
                                        showLogFilesDialog = false
                                        onNavigateToLog()
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = logFileInfo.file.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = humanReadableByteCountSI(logFileInfo.size),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val showModelDetailsDialog by viewModel.showModelDetailsDialog.collectAsState()
    if (showModelDetailsDialog) {
        ModelDetailsDialog(viewModel = viewModel)
    }

    var showOllamaTagsDialog by remember { mutableStateOf(false) }
    if (showOllamaTagsDialog) {
        OllamaTagsDialog(viewModel = viewModel) {
            showOllamaTagsDialog = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = attemptNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = if (hasModelSettingsChanges) BorderStroke(2.dp, Color.Red) else null
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = "模型设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            ExposedDropdownMenuBox(modifier = Modifier.weight(1.0f), expanded = isProfileSelectorExpended, onExpandedChange = {isProfileSelectorExpended = it}) {
                                TextField(
                                    value = name,
                                    onValueChange = {},
                                    label = { Text(stringResource(R.string.profile)) },
                                    readOnly = true,
                                    trailingIcon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = isProfileSelectorExpended, onDismissRequest = { isProfileSelectorExpended = false }) {
                                    profiles.forEach {
                                            profile ->
                                        DropdownMenuItem(
                                            text = { Text(profile.name) },
                                            onClick = {
                                                selectedProfile = profile
                                                viewModel.setActiveProfile(profile.name)
                                                isProfileSelectorExpended = false
                                            }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { name = "" }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_profile))
                            }
                            IconButton(onClick = {
                                viewModel.deleteProfile(selectedProfile.name)
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_profile))
                            }
                            IconButton(onClick = { saveModelSettings() }) {
                                Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.save_profile))
                            }
                        }
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.profile_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ExposedDropdownMenuBox(expanded = isApiModeExpanded, onExpandedChange = {isApiModeExpanded = it}) {
                            TextField(
                                value = apiMode,
                                onValueChange = {},
                                label = { Text(stringResource(R.string.api_mode)) },
                                readOnly = true,
                                trailingIcon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = isApiModeExpanded, onDismissRequest = { isApiModeExpanded = false }) {
                                apiModes.forEach {
                                    mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode) },
                                        onClick = {
                                            apiMode = mode
                                            isApiModeExpanded = false
                                            // Auto-update API path for convenience
                                            if (mode == ollamaMode) {
                                                apiPath = "/api/generate"
                                            } else if (mode == openAiMode) {
                                                apiPath = "/v1/chat/completions"
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        TextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.api_key)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                val description = if (passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(imageVector = image, contentDescription = description)
                                }
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = apiHost,
                                onValueChange = { apiHost = it },
                                label = { Text(stringResource(R.string.api_host)) },
                                modifier = Modifier.weight(2f)
                            )
                            TextField(
                                value = apiPath,
                                onValueChange = { apiPath = it },
                                label = { Text(stringResource(R.string.api_path)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = apiHost + apiPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        TextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text(stringResource(R.string.model)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = contextLength.toString(),
                            onValueChange = { contextLength = it.toIntOrNull() ?: 0 },
                            label = { Text("Context Length") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TextField(
                            value = visionFamilies,
                            onValueChange = { visionFamilies = it },
                            label = { Text("Vision Families") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "检查图片处理能力")
                            Switch(
                                checked = checkImageProcessing,
                                onCheckedChange = { checkImageProcessing = it }
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("图像质量")
                                Text("${imageQuality}%")
                            }
                            Slider(
                                value = imageQuality.toFloat(),
                                onValueChange = { imageQuality = it.roundToInt() },
                                valueRange = 0f..100f,
                                steps = 100
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("PDF 缩放")
                                Text("%.1fx".format(pdfScale))
                            }
                            Slider(
                                value = pdfScale,
                                onValueChange = { pdfScale = it },
                                valueRange = 1.0f..4.0f,
                                steps = 30
                            )
                        }
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigateToEditPagePrompt() },
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = "编辑页面提示", style = MaterialTheme.typography.titleMedium)
                        TextField(
                            value = activeProfile?.pagePrompt ?: "",
                            onValueChange = {},
                            label = { Text("Page Prompt") },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            maxLines = 3
                        )
                    }
                }
                TextField(
                    value = psPath,
                    onValueChange = { psPath = it },
                    label = { Text("PS Path") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = tagsPath,
                    onValueChange = { tagsPath = it },
                    label = { Text("Tags Path") },
                    modifier = Modifier.fillMaxWidth().clickable { showOllamaTagsDialog = true },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "启用会话日志")
                    Switch(
                        checked = enableSessionLogging,
                        onCheckedChange = { viewModel.setEnableSessionLogging(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.padding(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { openDocumentLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text="Import Settings",
                        style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp),
                        maxLines = 1)
                }
                Button(
                    onClick = { createDocumentLauncher.launch("ollama_settings.json") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text="Export Settings",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 12.sp),
                        maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.padding(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.listLogFiles()
                        showLogFilesDialog = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("View Log")
                }
                Button(
                    onClick = attemptNavigateBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}

@Composable
fun ModelDetailsDialog(viewModel: MainViewModel) {
    val modelDetails by viewModel.selectedModelDetails.collectAsState()

    Dialog(
        onDismissRequest = { viewModel.dismissOllamaModelDetailsDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                modelDetails?.let {
                    Text(text = "Model Details", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.padding(8.dp))
                    Text(text = "Family: ${it.details.family}")
                    Text(text = "Families: ${it.details.families?.joinToString(", ")}")
                    Text(text = "Parameter Size: ${it.details.parameterSize}")
                    Text(text = "Quantization Level: ${it.details.quantizationLevel}")
                }
            }
        }
    }
}

@Composable
fun OllamaTagsDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val ollamaModels by viewModel.ollamaModels.collectAsState()
    val ollamaModelsError by viewModel.ollamaModelsError.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.listOllamaModels()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column {
                if (ollamaModelsError != null) {
                    Text(text = ollamaModelsError!!)
                } else {
                    LazyColumn {
                        items(ollamaModels) { model ->
                            Text(
                                text = model.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.showOllamaModel(model.name)
                                        onDismiss()
                                    }
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    OllamaTheme {
        SettingsScreen(
            viewModel = MainViewModel(LocalContext.current.applicationContext as Application),
            onNavigateBack = {},
            onNavigateToLog = {},
            onNavigateToEditPagePrompt = {}
        )
    }
}
