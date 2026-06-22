package edu.usf.steadydrive.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.usf.steadydrive.BuildConfig
import java.util.Locale

@Composable
fun SteadyDriveScreen(
    uiState: SteadyDriveUiState,
    onStartDrive: () -> Unit,
    onRetry: () -> Unit,
) {
    val backgroundColor by animateColorAsState(
        when (uiState.screenMode) {
            SteadyDriveScreenMode.CONFIGURING -> Color(0xFFF6F0E3)
            SteadyDriveScreenMode.READY -> Color(0xFFF6F0E3)
            SteadyDriveScreenMode.RUNNING -> Color(0xFF102218)
            SteadyDriveScreenMode.COMPLETE -> Color(0xFF97D7A8)
            SteadyDriveScreenMode.ERROR -> Color(0xFFF9E2D7)
        },
        label = "backgroundColor",
    )

    val contentColor =
        if (uiState.screenMode == SteadyDriveScreenMode.RUNNING) {
            Color(0xFFF9FFF7)
        } else {
            Color(0xFF1F2B23)
        }

    val colorScheme =
        if (uiState.screenMode == SteadyDriveScreenMode.RUNNING) {
            darkColorScheme(primary = Color(0xFF94D2A4))
        } else {
            lightColorScheme(primary = Color(0xFF0E6B4D))
        }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = backgroundColor,
            contentColor = contentColor,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                when (uiState.screenMode) {
                    SteadyDriveScreenMode.CONFIGURING -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(28.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Please wait while SteadyDrive is configured.",
                                textAlign = TextAlign.Center,
                                fontSize = 24.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "The app is checking in with the researcher portal.",
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    SteadyDriveScreenMode.READY -> {
                        Button(
                            onClick = onStartDrive,
                            modifier = Modifier.size(228.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ) {
                            Text(
                                text = "Start",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    SteadyDriveScreenMode.RUNNING -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.padding(28.dp),
                        ) {
                            Text(
                                text = "Drive Safely!",
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = formatRemaining(uiState.remainingSeconds),
                                fontSize = 64.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text =
                                    if (uiState.remainingSeconds > 15 * 60) {
                                        "Warm-up period in progress."
                                    } else {
                                        "Active data collection in progress."
                                    },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    SteadyDriveScreenMode.COMPLETE -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF0E6B4D),
                                modifier = Modifier.size(88.dp),
                            )
                            Text(
                                text = "Data Collection Complete!",
                                textAlign = TextAlign.Center,
                                fontSize = 34.sp,
                                lineHeight = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF124A2D),
                            )
                            uiState.lastCsvFileName?.let { fileName ->
                                Text(
                                    text = "Saved as $fileName",
                                    textAlign = TextAlign.Center,
                                    color = Color(0xFF124A2D),
                                )
                            }
                        }
                    }

                    SteadyDriveScreenMode.ERROR -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(28.dp),
                        ) {
                            Text(
                                text = "We hit a setup issue.",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = uiState.errorMessage ?: "Please try syncing again.",
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = onRetry) {
                                Text("Try Again")
                            }
                        }
                    }
                }

                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.5f),
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                )
            }
        }
    }
}

private fun formatRemaining(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
