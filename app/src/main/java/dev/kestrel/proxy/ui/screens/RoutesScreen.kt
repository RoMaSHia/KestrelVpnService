package dev.kestrel.proxy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kestrel.proxy.data.model.AppRoutingMode
import dev.kestrel.proxy.data.model.InstalledAppInfo
import dev.kestrel.proxy.data.model.RoutingRule
import dev.kestrel.proxy.data.model.RuleAction
import dev.kestrel.proxy.data.model.RuleMatchType
import dev.kestrel.proxy.data.util.RuleSerializer
import dev.kestrel.proxy.ui.KestrelUiState
import dev.kestrel.proxy.ui.components.ActionBadge
import dev.kestrel.proxy.ui.components.RoutingHelpBottomSheet
import dev.kestrel.proxy.ui.theme.KestrelColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(
    uiState: KestrelUiState,
    onSetAppMode: (AppRoutingMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onClearSelectedPackages: () -> Unit,
    onApplyAppChanges: () -> Unit,
    onSaveRule: (RoutingRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String) -> Unit,
    onMoveRule: (String, Boolean) -> Unit,
    onSetDefaultAction: (RuleAction) -> Unit,
    onImportRules: (String, Boolean) -> Result<Int>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    var showHelpSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<RoutingRule?>(null) }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
            }.onSuccess { text ->
                onImportRules(text, false)
                    .onSuccess { count ->
                        scope.launch { snackbarHostState.showSnackbar("Импортировано правил: $count") }
                    }
                    .onFailure { err ->
                        scope.launch {
                            snackbarHostState.showSnackbar(err.message ?: "Ошибка импорта")
                        }
                    }
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(RuleSerializer.exportJson(uiState.rules))
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("Правила экспортированы в JSON") }
            }
        }
    }

    val exportTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(RuleSerializer.exportText(uiState.rules))
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("Список доменов экспортирован") }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedSubTab == 1) {
                FloatingActionButton(
                    onClick = {
                        editingRule = null
                        showRuleDialog = true
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Добавить правило")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Маршрутизация",
                    style = MaterialTheme.typography.headlineSmall,
                )

                Row {
                    IconButton(onClick = { showHelpSheet = true }) {
                        Icon(
                            imageVector = Icons.Rounded.HelpOutline,
                            contentDescription = "Как работают правила",
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Импорт и экспорт",
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Импорт из буфера обмена") },
                                onClick = {
                                    showMenu = false
                                    val clip = clipboard.getText()?.text.orEmpty()
                                    onImportRules(clip, false)
                                        .onSuccess { count ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Импортировано правил: $count")
                                            }
                                        }
                                        .onFailure { err ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    err.message ?: "Скопируйте JSON или список доменов",
                                                )
                                            }
                                        }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Импорт из файла (JSON / TXT)") },
                                onClick = {
                                    showMenu = false
                                    importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Копировать правила (JSON)") },
                                onClick = {
                                    showMenu = false
                                    clipboard.setText(AnnotatedString(RuleSerializer.exportJson(uiState.rules)))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("JSON правил скопирован")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Экспорт JSON в файл") },
                                onClick = {
                                    showMenu = false
                                    exportJsonLauncher.launch("kestrel-rules.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Экспорт списком (TXT)") },
                                onClick = {
                                    showMenu = false
                                    exportTextLauncher.launch("kestrel-rules.txt")
                                },
                            )
                        }
                    }
                }
            }

            // Sub-tabs: Apps | Rules
            PrimaryTabRow(
                selectedTabIndex = selectedSubTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    text = { Text("Приложения (${uiState.settings.selectedPackages.size})") },
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    text = { Text("Правила (${uiState.rules.size})") },
                )
            }

            if (selectedSubTab == 0) {
                AppsRoutingTab(
                    uiState = uiState,
                    onSetAppMode = onSetAppMode,
                    onTogglePackage = onTogglePackage,
                    onClearSelected = onClearSelectedPackages,
                    onApplyChanges = onApplyAppChanges,
                )
            } else {
                RulesRoutingTab(
                    uiState = uiState,
                    onSetDefaultAction = onSetDefaultAction,
                    onToggleRule = onToggleRule,
                    onMoveRule = onMoveRule,
                    onEditRule = { rule ->
                        editingRule = rule
                        showRuleDialog = true
                    },
                    onDeleteRule = onDeleteRule,
                )
            }
        }
    }

    if (showHelpSheet) {
        RoutingHelpBottomSheet(onDismiss = { showHelpSheet = false })
    }

    if (showRuleDialog) {
        RuleEditorDialog(
            initial = editingRule,
            onDismiss = { showRuleDialog = false },
            onConfirm = { saved ->
                onSaveRule(saved)
                showRuleDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsRoutingTab(
    uiState: KestrelUiState,
    onSetAppMode: (AppRoutingMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onClearSelected: () -> Unit,
    onApplyChanges: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnTop by rememberSaveable { mutableStateOf(true) }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }

    val selectedPackages = uiState.settings.selectedPackages
    val mode = uiState.settings.appRoutingMode

    val filteredApps = remember(
        uiState.installedApps,
        query,
        selectedOnTop,
        showSystemApps,
        selectedPackages,
    ) {
        val q = query.trim().lowercase()
        uiState.installedApps
            .asSequence()
            .filter { showSystemApps || !it.isSystem || selectedPackages.contains(it.packageName) }
            .filter {
                q.isEmpty() ||
                    it.label.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q)
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> {
                    selectedOnTop && selectedPackages.contains(it.packageName)
                }.thenBy { it.label.lowercase() },
            )
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 3-way SegmentedButtonRow
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf(
                AppRoutingMode.ALL to "Все",
                AppRoutingMode.ONLY_SELECTED to "Только выбранные",
                AppRoutingMode.BYPASS_SELECTED to "Обходить выбранные",
            )
            modes.forEachIndexed { index, (itemMode, label) ->
                SegmentedButton(
                    selected = mode == itemMode,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSetAppMode(itemMode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    icon = {},
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Dynamic colored mode hint banner
        AnimatedContent(
            targetState = mode,
            label = "appModeHintBanner",
        ) { currentMode ->
            val dark = isSystemInDarkTheme()
            val (bg, fg, hintText) = when (currentMode) {
                AppRoutingMode.ALL -> Triple(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Весь трафик всех приложений на устройстве проходит через VPN и правила Rust-ядра.",
                )
                AppRoutingMode.ONLY_SELECTED -> Triple(
                    if (dark) KestrelColors.DirectGreenContainerDark else KestrelColors.DirectGreenContainerLight,
                    if (dark) KestrelColors.DirectGreenOnDark else KestrelColors.DirectGreen,
                    "Через прокси работают ТОЛЬКО отмеченные ниже приложения — все остальные выходят напрямую.",
                )
                AppRoutingMode.BYPASS_SELECTED -> Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Весь трафик идёт через прокси, кроме отмеченных приложений — удобно для банков и госуслуг.",
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = bg,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = fg,
                    )
                }
            }
        }

        // Apply Changes banner when VPN is running and app settings changed
        AnimatedVisibility(visible = uiState.hasPendingAppChanges) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Список приложений изменён",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "Перезапустите туннель, чтобы новые правила ОС вступили в силу.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onApplyChanges()
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Применить")
                    }
                }
            }
        }

        // Search input
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Поиск приложения или packageName…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        // Filter chips + counter + clear
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = selectedOnTop,
                onClick = { selectedOnTop = !selectedOnTop },
                label = { Text("Выбранные сверху") },
                leadingIcon = if (selectedOnTop) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
            FilterChip(
                selected = showSystemApps,
                onClick = { showSystemApps = !showSystemApps },
                label = { Text("Системные") },
                leadingIcon = if (showSystemApps) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
            if (selectedPackages.isNotEmpty()) {
                TextButton(onClick = onClearSelected) {
                    Text("Сбросить (${selectedPackages.size})")
                }
            }
        }

        // Apps list
        if (uiState.isLoadingApps) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val selectionEnabled = mode != AppRoutingMode.ALL
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = selectedPackages.contains(app.packageName)
                    AppRowItem(
                        app = app,
                        checked = isChecked,
                        enabled = selectionEnabled,
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePackage(app.packageName)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRowItem(
    app: InstalledAppInfo,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.58f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

@Composable
private fun RulesRoutingTab(
    uiState: KestrelUiState,
    onSetDefaultAction: (RuleAction) -> Unit,
    onToggleRule: (String) -> Unit,
    onMoveRule: (String, Boolean) -> Unit,
    onEditRule: (RoutingRule) -> Unit,
    onDeleteRule: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Правило по умолчанию (Fallback)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Куда отправлять трафик, не попавший ни под одно правило выше и не входящий в LAN:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val choices = listOf(
                            RuleAction.PROXY to "Через прокси (PROXY)",
                            RuleAction.DIRECT to "Напрямую (DIRECT)",
                        )
                        choices.forEachIndexed { idx, (act, label) ->
                            SegmentedButton(
                                selected = uiState.settings.defaultAction == act,
                                onClick = { onSetDefaultAction(act) },
                                shape = SegmentedButtonDefaults.itemShape(idx, choices.size),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (uiState.rules.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Rule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "Пользовательских правил пока нет",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Нажмите «+» внизу справа, чтобы направить нужные сайты, IP-подсети или порты напрямую, через прокси или заблокировать их.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(uiState.rules, key = { _, item -> item.id }) { index, rule ->
                RuleCard(
                    rule = rule,
                    canMoveUp = index > 0,
                    canMoveDown = index < uiState.rules.lastIndex,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleRule(rule.id)
                    },
                    onMoveUp = { onMoveRule(rule.id, true) },
                    onMoveDown = { onMoveRule(rule.id, false) },
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule.id) },
                )
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: RoutingRule,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (rule.enabled) 1f else 0.6f),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionBadge(action = rule.action)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = rule.matchType.prefix.trimEnd(':'),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = rule.name.ifBlank { rule.value },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle() },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rule.value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Выше", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Ниже", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Изменить", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorDialog(
    initial: RoutingRule?,
    onDismiss: () -> Unit,
    onConfirm: (RoutingRule) -> Unit,
) {
    var action by remember { mutableStateOf(initial?.action ?: RuleAction.DIRECT) }
    var matchType by remember { mutableStateOf(initial?.matchType ?: RuleMatchType.DOMAIN_SUFFIX) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }

    val isValid = value.trim().isNotEmpty() && when (matchType) {
        RuleMatchType.PORT -> value.trim().toIntOrNull() in 1..65535
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "Новое правило" else "Редактировать правило",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Действие",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleAction.entries.forEach { item ->
                        FilterChip(
                            selected = action == item,
                            onClick = { action = item },
                            label = { Text(item.label) },
                        )
                    }
                }

                Text(
                    text = "Тип условия",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleMatchType.entries.forEach { item ->
                        FilterChip(
                            selected = matchType == item,
                            onClick = { matchType = item },
                            label = { Text(item.titleRu) },
                        )
                    }
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Значение (${matchType.prefix})") },
                    placeholder = { Text("Например: ${matchType.hintRu}") },
                    supportingText = {
                        Text(
                            text = when (matchType) {
                                RuleMatchType.DOMAIN_SUFFIX -> "Совпадёт с example.com и любым *.example.com"
                                RuleMatchType.DOMAIN_FULL -> "Только точный домен без поддоменов (api.example.com)"
                                RuleMatchType.DOMAIN_KEYWORD -> "Любой домен, содержащий это слово (tracker)"
                                RuleMatchType.IP_CIDR -> "IPv4/IPv6 адрес или подсеть (192.168.1.0/24)"
                                RuleMatchType.PORT -> "Целевой порт от 1 до 65535 (например, 443 или 53)"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Заметка / название (необязательно)") },
                    placeholder = { Text("Например: Локальные сервисы") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    val trimmedVal = value.trim()
                    val finalName = name.trim().ifEmpty { trimmedVal }
                    onConfirm(
                        RoutingRule(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = finalName,
                            matchType = matchType,
                            value = trimmedVal,
                            action = action,
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}
