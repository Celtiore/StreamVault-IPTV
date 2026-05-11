package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface as TvClickableSurface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveSyncStatus
import java.text.DateFormat
import java.util.Date

/**
 * Drive backup section rendered under the local "Export / Import" cards of the
 * Backup tab in Settings. Mirrors their visual language (square 50/50 card layout,
 * arrow glyph + primary/secondary tint) so the screen stays cohesive.
 *
 * Reuses the existing `inspectBackup` preview dialog for pulls — no extra UI.
 */
@Composable
internal fun DriveBackupSection(
    authState: DriveAuthState,
    syncStatus: DriveSyncStatus,
    isBusy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_drive_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.settings_drive_section_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim,
        )

        when (val state = authState) {
            DriveAuthState.SignedOut, DriveAuthState.Pending -> {
                DriveActionCard(
                    label = stringResource(R.string.settings_drive_signin),
                    description = stringResource(R.string.settings_drive_signin_description),
                    enabled = !isBusy,
                    onClick = onSignIn,
                )
            }
            is DriveAuthState.SignedIn -> {
                DriveAccountRow(
                    email = state.account.email ?: state.account.displayName.orEmpty(),
                    lastPushAtMs = syncStatus.lastPushAtMs,
                    lastPullAtMs = syncStatus.lastPullAtMs,
                    onSignOut = onSignOut,
                    enabled = !isBusy,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DriveSquareCard(
                        glyph = "☁↑",
                        title = stringResource(R.string.settings_drive_push),
                        subtitle = stringResource(R.string.settings_drive_push_subtitle),
                        tint = Primary,
                        enabled = !isBusy,
                        onClick = onPush,
                        modifier = Modifier.weight(1f),
                    )
                    DriveSquareCard(
                        glyph = "☁↓",
                        title = stringResource(R.string.settings_drive_pull),
                        subtitle = stringResource(R.string.settings_drive_pull_subtitle),
                        tint = Secondary,
                        enabled = !isBusy,
                        onClick = onPull,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveAccountRow(
    email: String,
    lastPushAtMs: Long?,
    lastPullAtMs: Long?,
    enabled: Boolean,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatLastSync(lastPushAtMs, lastPullAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth(0f))
        TvClickableSurface(
            onClick = onSignOut,
            enabled = enabled,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f),
                focusedContainerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Text(
                text = stringResource(R.string.settings_drive_signout),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
            )
        }
    }
}

@Composable
private fun DriveActionCard(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Primary.copy(alpha = 0.12f),
            focusedContainerColor = Primary.copy(alpha = 0.28f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Primary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
            )
        }
    }
}

@Composable
private fun DriveSquareCard(
    glyph: String,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = tint.copy(alpha = 0.12f),
            focusedContainerColor = tint.copy(alpha = 0.28f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.titleLarge,
                color = tint,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = tint,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatLastSync(pushMs: Long?, pullMs: Long?): String {
    if (pushMs == null && pullMs == null) {
        return "—"
    }
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val parts = buildList {
        pushMs?.let { add("Push: ${df.format(Date(it))}") }
        pullMs?.let { add("Pull: ${df.format(Date(it))}") }
    }
    return parts.joinToString("   ·   ")
}
