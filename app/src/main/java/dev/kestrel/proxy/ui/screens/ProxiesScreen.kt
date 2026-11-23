package dev.kestrel.proxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kestrel.proxy.data.model.ProxyProfile
import dev.kestrel.proxy.data.model.ProxyProtocol
import dev.kestrel.proxy.ui.KestrelUiState
import kotlinx.coroutines.launch

@Composable
fun ProxiesScreen(
    uiState: KestrelUiState,
    onSelectProxy: (String) -> Unit,
    onSaveProxy: (ProxyProfile) -> Unit,
    onDeleteProxy: (String) -> Unit,
    onImportUri: (String) -> Result<ProxyProfile>,
    onPingProxy: (ProxyProfile) -> Unit,
    onPingAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingProfile = null
                    showDialog = true
                },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Добавить прокси")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Прокси-серверы",
                style = MaterialTheme.typography.headlineSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        val clipText = clipboard.getText()?.text.orEmpty()
                        onImportUri(clipText)
                            .onSuccess { added ->
                                scope.launch {
                                    snackbarHostState.showSnackbar("Добавлен: ${added.name}")
                                }
                            }
                            .onFailure { err ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        err.message ?: "Скопируйте ссылку вида socks5://host:port",
                                    )
                                }
                            }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Из буфера", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onPingAll,
                    enabled = uiState.profiles.isNotEmpty() && !uiState.isPinging,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkPing,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Пинг всех", maxLines = 1)
                }
            }

            if (uiState.isPinging) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (uiState.profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "Пока нет серверов",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Добавьте SOCKS5, SOCKS4/4a или HTTP CONNECT сервер вручную или вставьте ссылку из буфера обмена.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 84.dp),
                ) {
                    items(uiState.profiles, key = { it.id }) { profile ->
                        val isActive = profile.id == uiState.settings.activeProfileId
                        ProxyProfileCard(
                            profile = profile,
                            isActive = isActive,
                            onSelect = { onSelectProxy(profile.id) },
                            onPing = { onPingProxy(profile) },
                            onEdit = {
                                editingProfile = profile
                                showDialog = true
                            },
                            onDelete = { onDeleteProxy(profile.id) },
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        ProxyEditorDialog(
            initial = editingProfile,
            onDismiss = { showDialog = false },
            onConfirm = { saved ->
                onSaveProxy(saved)
                showDialog = false
            },
        )
    }
}

@Composable
private fun ProxyProfileCard(
    profile: ProxyProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        border = if (isActive) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = profile.protocol.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (isActive) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Активный",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!profile.username.isNullOrEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Авторизация: ${profile.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PingChip(pingMs = profile.lastPingMs, onClick = onPing)
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Изменить",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxyEditorDialog(
    initial: ProxyProfile?,
    onDismiss: () -> Unit,
    onConfirm: (ProxyProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var protocol by remember { mutableStateOf(initial?.protocol ?: ProxyProtocol.SOCKS5) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var portText by remember { mutableStateOf(initial?.port?.toString() ?: "1080") }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }

    val parsedPort = portText.toIntOrNull()
    val isValid = host.isNotBlank() && parsedPort != null && parsedPort in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "Новый прокси" else "Редактировать прокси",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Протокол",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProxyProtocol.entries.forEach { item ->
                        FilterChip(
                            selected = protocol == item,
                            onClick = {
                                protocol = item
                                if (initial == null && (portText == "1080" || portText == "8080")) {
                                    portText = if (item == ProxyProtocol.HTTP) "8080" else "1080"
                                }
                            },
                            label = { Text(item.displayName) },
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (необязательно)") },
                    placeholder = { Text("Например: Мой сервер") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Адрес (IP / домен)") },
                        placeholder = { Text("198.51.100.1") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Порт") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Логин (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == ProxyProtocol.SOCKS5 || protocol == ProxyProtocol.HTTP) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Пароль (необязательно)") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    val trimmedHost = host.trim()
                    val finalPort = parsedPort ?: 1080
                    val finalName = name.trim().ifEmpty {
                        "${protocol.displayName} $trimmedHost:$finalPort"
                    }
                    onConfirm(
                        ProxyProfile(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = finalName,
                            protocol = protocol,
                            host = trimmedHost,
                            port = finalPort,
                            username = username.trim().takeIf { it.isNotEmpty() },
                            password = password.takeIf { it.isNotEmpty() },
                            lastPingMs = initial?.lastPingMs,
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
