package com.mariocart.app.ui.updates

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mariocart.app.BuildConfig
import com.mariocart.app.ui.AutoUpdater
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.util.responsiveDims
import com.mariocart.app.ui.util.rememberInitialFocusRequester
import kotlinx.coroutines.launch
import java.io.File

private enum class DownloadState { Idle, Downloading, Done, Failed }

@Composable
fun UpdatesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checkState by remember { mutableStateOf<AutoUpdater.CheckResult?>(null) }
    var isChecking by remember { mutableStateOf(true) }

    var downloadState by remember { mutableStateOf(DownloadState.Idle) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Fetch the latest release once when the screen opens.
    LaunchedEffect(Unit) {
        checkState = AutoUpdater.checkForUpdate()
        isChecking = false
    }

    val dims = responsiveDims()

    // On a no-pointer TV box, land D-pad focus on the first action button so
    // the user has a known starting point when they open Updates.
    val actionFocusRequester = rememberInitialFocusRequester()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = dims.topContentPadding)
    ) {
        Text(
            "Updates",
            color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            "Current version: ${BuildConfig.VERSION_NAME} " +
                "(build ${BuildConfig.VERSION_CODE})",
            color = TextMuted, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        when {
            isChecking -> Box(
                Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Red) }

            checkState is AutoUpdater.CheckResult.Error -> {
                val msg = (checkState as AutoUpdater.CheckResult.Error).message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Bg3)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Couldn't check for updates",
                            color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(msg, color = TextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                isChecking = true
                                statusMessage = null
                                scope.launch {
                                    checkState = AutoUpdater.checkForUpdate()
                                    isChecking = false
                                }
                            },
                            modifier = Modifier.focusRequester(actionFocusRequester),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("Retry", color = TextPrimary) }
                    }
                }
            }

            checkState is AutoUpdater.CheckResult.UpToDate -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Bg3)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "You're up to date",
                            color = Color(0xFF4CAF50),
                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) " +
                                "is the latest available.",
                            color = TextMuted, fontSize = 13.sp
                        )
                    }
                }
            }

            checkState is AutoUpdater.CheckResult.UpdateAvailable -> {
                val r = checkState as AutoUpdater.CheckResult.UpdateAvailable
                val sizeMb = if (r.apkSize > 0) "%.1f".format(r.apkSize / 1_048_576.0) else "?"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Bg3)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                r.releaseName.ifEmpty { r.latestTag },
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "NEW",
                                color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Red, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Published ${r.publishedAt} • APK $sizeMb MB",
                            color = TextMuted, fontSize = 12.sp
                        )

                        if (r.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                r.releaseNotes,
                                color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        when (downloadState) {
                            DownloadState.Idle -> Button(
                                onClick = {
                                    statusMessage = null
                                    downloadState = DownloadState.Downloading
                                    downloadProgress = 0
                                    scope.launch {
                                        val file = AutoUpdater.downloadApk(
                                            apkUrl = r.apkUrl,
                                            context = context
                                        ) { pct -> downloadProgress = pct }
                                        if (file != null) {
                                            downloadedFile = file
                                            downloadState = DownloadState.Done
                                            AutoUpdater.installApk(context, file)
                                        } else {
                                            downloadState = DownloadState.Failed
                                            statusMessage = "Download failed. Check your connection."
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(actionFocusRequester),
                                colors = ButtonDefaults.buttonColors(containerColor = Red),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Download & Install", color = Color.White, fontWeight = FontWeight.Bold) }

                            DownloadState.Downloading -> {
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    color = Red,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Downloading… $downloadProgress%",
                                    color = TextMuted, fontSize = 12.sp
                                )
                            }

                            DownloadState.Done -> Column {
                                Text(
                                    "Download complete — launching installer…",
                                    color = Color(0xFF4CAF50), fontSize = 13.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            downloadedFile?.let { AutoUpdater.installApk(context, it) }
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) { Text("Install again", color = TextPrimary) }
                                    OutlinedButton(
                                        onClick = {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(r.releaseUrl))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) { Text("View on GitHub", color = TextPrimary) }
                                }
                            }

                            DownloadState.Failed -> Column {
                                Text(
                                    statusMessage ?: "Download failed.",
                                    color = Red, fontSize = 13.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        downloadState = DownloadState.Idle
                                        statusMessage = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text("Try Again", color = Color.White) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "If the installer doesn't appear, enable " +
                        "\"Install unknown apps\" for Netflix in your Android settings.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
        }
    }
}
