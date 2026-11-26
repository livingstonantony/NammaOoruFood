package dev.livin.nammaoorufood.customer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun RemoteImage(url: String?, contentDescription: String, modifier: Modifier = Modifier) {
    val imageUrl = url?.trim()?.takeIf { it.isNotEmpty() }
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE5E5E5)), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFBDBDBD)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onLoading = { loaded = false },
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}
