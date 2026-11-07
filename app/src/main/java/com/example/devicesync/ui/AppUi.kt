package com.example.devicesync.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.devicesync.KeepAliveGuide
import com.example.devicesync.PeerSnapshot
import com.example.devicesync.PeerStateRepo

@Composable
fun AppUi() {
    val self by PeerStateRepo.self.collectAsState()
    val peers by PeerStateRepo.peers.collectAsState()
    val notifications by PeerStateRepo.notifications.collectAsState()

    val ctx = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp

    var bgUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            bgUri = it
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (bgUri != null) {
            AsyncImage(
                model = bgUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        } else {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF0D0D12), Color(0xFF1A1A2E)))
            ))
        }

        if (width >= 600) {
            Row(Modifier.fillMaxSize().padding(16.dp)) {
                DeviceList(
                    self = self, peers = peers,
                    modifier = Modifier.weight(0.35f).fillMaxHeight()
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(0.65f).fillMaxHeight()) {
                    Header()
                    Spacer(Modifier.height(12.dp))
                    StatusCard(self, "本机")
                    Spacer(Modifier.height(12.dp))
                    peers.values.forEach {
                        StatusCard(it, "对端 ${it.deviceType}")
                        Spacer(Modifier.height(12.dp))
                    }
                    NotificationPanel(notifications)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Header() }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { KeepAliveGuide.launchAll(ctx) }) {
                            Text("保活设置")
                        }
                        Button(onClick = { picker.launch("image/*") }) {
                            Text("背景")
                        }
                    }
                }
                item { StatusCard(self, "本机") }
                items(peers.values.toList()) { p ->
                    StatusCard(p, "对端 ${p.deviceType}")
                }
                item { NotificationPanel(notifications) }
            }
        }
    }
}

@Composable
private fun Header() {
    Text(
        "设备同步",
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )
}

@Composable
private fun DeviceList(
    self: PeerSnapshot?,
    peers: Map<String, PeerSnapshot>,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { GlassCard { Text("本机 ${self?.deviceType ?: "--"}", color = Color.White) } }
        items(peers.values.toList()) { p ->
            GlassCard {
                Text("对端 ${p.deviceType}", color = Color.White)
                Text(p.deviceId.take(8), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusCard(p: PeerSnapshot?, label: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "电量 ${if ((p?.battery ?: -1) < 0) "--" else "${p!!.battery}%"}",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "网络 ${p?.network ?: "--"}   存储 ${p?.storageFreeMb ?: "--"} MB",
            color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp
        )
    }
}

@Composable
private fun NotificationPanel(list: List<String>) {
    if (list.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth()) {
        Text("对端通知", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        list.takeLast(5).forEach {
            Text(it, color = Color.White, fontSize = 13.sp)
        }
    }
}
