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

/** Status is conveyed by a text label and glyph, never by color alone. */
fun OrderStatus.label() = when (this) {
    OrderStatus.PLACED -> "⏳ Waiting for hotel"
    OrderStatus.ACCEPTED -> "✔ Accepted"
    OrderStatus.PREPARING -> "🍳 Preparing"
    OrderStatus.READY_FOR_PICKUP -> "✅ Ready for pickup"
    OrderStatus.COMPLETED -> "✔ Completed"
    OrderStatus.REJECTED -> "✖ Rejected"
    OrderStatus.CANCELLED -> "✖ Cancelled"
}

@Composable
fun StatusChip(status: OrderStatus) {
    val ready = status == OrderStatus.READY_FOR_PICKUP
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(status.label(), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
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
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("← Back") } else Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
