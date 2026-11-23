package dev.kestrel.proxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kestrel.proxy.data.model.RuleAction
import dev.kestrel.proxy.ui.theme.KestrelColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActionBadge(
    action: RuleAction,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val (bg, fg) = when (action) {
        RuleAction.PROXY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        RuleAction.DIRECT -> if (dark) {
            KestrelColors.DirectGreenContainerDark to KestrelColors.DirectGreenOnDark
        } else {
            KestrelColors.DirectGreenContainerLight to KestrelColors.DirectGreen
        }
        RuleAction.BLOCK -> if (dark) {
            KestrelColors.BlockRedContainerDark to KestrelColors.BlockRedOnDark
        } else {
            KestrelColors.BlockRedContainerLight to KestrelColors.BlockRed
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.2f ГБ", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}

fun formatSpeed(bps: Long): String = when {
    bps >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f МБ/с", bps / 1_048_576.0)
    bps >= 1024 -> String.format(Locale.getDefault(), "%.1f КБ/с", bps / 1024.0)
    else -> "$bps Б/с"
}

fun formatUptime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

fun formatLogTime(timestampMs: Long): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(timestampMs))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingHelpBottomSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Как работает маршрутизация Kestrel",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = "Каждый сетевой запрос проходит 3 последовательных шага — от фильтра приложений в Android до правил внутри Rust-ядра.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Visual 3-step pipeline
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PipelineStepRow(
                        step = "1",
                        icon = Icons.Rounded.Apps,
                        title = "Фильтр приложений (Android VPN)",
                        description = "Режим «Все», «Только выбранные» или «Обходить выбранные» решает, попадает ли приложение в туннель вообще.",
                    )
                    PipelineArrow()
                    PipelineStepRow(
                        step = "2",
                        icon = Icons.Rounded.Rule,
                        title = "Пользовательские правила (Rust-ядро)",
                        description = "Проверяются сверху вниз: домен (через SNI/HTTP/DNS), IP-подсеть (CIDR) или порт. Срабатывает первое совпадение.",
                    )
                    PipelineArrow()
                    PipelineStepRow(
                        step = "3",
                        icon = Icons.Rounded.Public,
                        title = "Обход локальной сети и правило по умолчанию",
                        description = "Если включён «Обход LAN», адреса роутера и домашней сети идут напрямую. Всё остальное — по правилу по умолчанию.",
                    )
                }
            }

            Text(
                text = "Что означают действия",
                style = MaterialTheme.typography.titleMedium,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionExplanationRow(
                    action = RuleAction.PROXY,
                    text = "Отправить соединение через активный прокси-сервер.",
                )
                ActionExplanationRow(
                    action = RuleAction.DIRECT,
                    text = "Подключиться напрямую с телефона, минуя прокси (максимальная скорость для банков и локальных сайтов).",
                )
                ActionExplanationRow(
                    action = RuleAction.BLOCK,
                    text = "Мгновенно сбросить соединение (полезно для трекеров и рекламы).",
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Зачем нужен SNI-анализ?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Когда приложение открывает HTTPS-сайт, оно сначала обращается по IP-адресу. Kestrel считывает открытое имя домена из первого пакета TLS (ClientHello SNI), не расшифровывая ваш трафик — это позволяет точно применять доменные правила, даже если DNS закэширован системой.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PipelineStepRow(
    step: String,
    icon: ImageVector,
    title: String,
    description: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "$step. $title",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PipelineArrow() {
    Box(
        modifier = Modifier.padding(start = 7.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ArrowDownward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ActionExplanationRow(
    action: RuleAction,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionBadge(action = action, modifier = Modifier.width(64.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
