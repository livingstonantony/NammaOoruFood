package dev.livin.nammaoorufood.customer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.livin.nammaoorufood.network.OrderStatus

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restaurant

/** Status is conveyed by a text label and Google Material Icon. */
fun OrderStatus.label() = when (this) {
    OrderStatus.PLACED -> "Waiting for hotel"
    OrderStatus.ACCEPTED -> "Accepted"
    OrderStatus.PREPARING -> "Preparing"
    OrderStatus.READY_FOR_PICKUP -> "Ready for pickup"
    OrderStatus.COMPLETED -> "Completed"
    OrderStatus.REJECTED -> "Rejected"
    OrderStatus.CANCELLED -> "Cancelled"
}

fun OrderStatus.icon() = when (this) {
    OrderStatus.PLACED -> Icons.Default.HourglassEmpty
    OrderStatus.ACCEPTED -> Icons.Default.CheckCircle
    OrderStatus.PREPARING -> Icons.Default.Restaurant
    OrderStatus.READY_FOR_PICKUP -> Icons.Default.Check
    OrderStatus.COMPLETED -> Icons.Default.Done
    OrderStatus.REJECTED -> Icons.Default.Close
    OrderStatus.CANCELLED -> Icons.Default.Cancel
}

@Composable
fun StatusChip(status: OrderStatus) {
    val ready = status == OrderStatus.READY_FOR_PICKUP
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = status.icon(),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(status.label(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val shape = MaterialTheme.shapes.large
    if (onClick != null) {
        Card(onClick, modifier.fillMaxWidth(), shape = shape, colors = colors, border = border) { Column(Modifier.padding(16.dp), content = content) }
    } else {
        Card(modifier.fillMaxWidth(), shape = shape, colors = colors, border = border) { Column(Modifier.padding(16.dp), content = content) }
    }
}

@Composable
fun CenteredLoading() = Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(message: String) =
    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text(message, style = MaterialTheme.typography.bodyLarge) }

@Composable
fun NotificationIconButton(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge {
                        Text(
                            text = if (unreadCount > 9) "9+" else "$unreadCount",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        ) {
            Icon(Icons.Default.Notifications, contentDescription = "Notifications")
        }
    }
}

@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier.fillMaxWidth()) {
    Row(modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
