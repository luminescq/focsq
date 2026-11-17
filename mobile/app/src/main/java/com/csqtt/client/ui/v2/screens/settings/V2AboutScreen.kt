package com.csqtt.client.ui.v2.screens.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csqtt.client.CsqttConstants
import com.csqtt.client.R
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2HeroCard
import com.csqtt.client.ui.v2.components.V2TopBar
import com.csqtt.client.ui.v2.design.V2AccentFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private data class V2TeamMember(
    val name: String,
    val description: String,
    val githubUrl: String,
    val username: String?,
)

private val teamMembers = listOf(
    V2TeamMember(
        name = "amurcanov",
        description = "Создатель и разработчик оригинального проекта csqtt.",
        githubUrl = "https://github.com/amurcanov",
        username = "amurcanov",
    ),
    V2TeamMember(
        name = "luminescq",
        description = "Участник сообщества. Рисовал дизайн для этого приложения (csqttr).",
        githubUrl = "https://github.com/luminescq",
        username = "luminescq",
    ),
    V2TeamMember(
        name = "Ядро CSQTTR",
        description = "Обновления ядра выше 2.5.0 — репозиторий Afteroid/csqttr: форк ядра с мобильным клиентом. " +
            "FOCSQ берёт обновления ядра оттуда и указывает авторство.",
        githubUrl = CsqttConstants.Links.REPOSITORY,
        username = null,
    ),
)

private val avatarCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun V2AboutScreen(
    version: String,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = V2Theme.colors

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            android.widget.Toast.makeText(context, "Не удалось открыть ссылку", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = V2Sizes.ScreenPadding),
    ) {
        V2TopBar(
            onBack = onBack,
            title = "О проекте",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = V2Spacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            V2HeroCard(
                title = "FOCSQ",
                version = version,
                showGithub = true,
                onBadgeClick = onCheckUpdates,
                onGithubClick = { openUrl(CsqttConstants.Links.REPOSITORY) },
            )

            V2Card(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(V2Spacing.Lg),
            ) {
                Text(
                    text = "Над проектом работали",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(V2Spacing.Md))
                teamMembers.forEach { member ->
                    V2TeamMemberRow(
                        member = member,
                        onClick = { openUrl(member.githubUrl) },
                        modifier = Modifier.padding(bottom = V2Spacing.Sm),
                    )
                }
            }

            Text(
                text = "design by luminescq",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2AccentFamily),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = V2Spacing.Xl),
            )
        }
    }
}

@Composable
private fun V2TeamMemberRow(
    member: V2TeamMember,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    V2Card(
        modifier = modifier,
        shape = V2Shapes.Row,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(V2Spacing.Md),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            V2AsyncAvatar(
                username = member.username,
                fallbackText = member.name.take(1).uppercase(),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = member.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_v2_github),
                contentDescription = "GitHub",
                tint = colors.textPrimary,
                modifier = Modifier.size(V2Sizes.IconLarge),
            )
        }
    }
}

@Composable
private fun V2AsyncAvatar(
    username: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = V2Theme.colors
    var bitmap by remember(username) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(username) {
        if (username != null) {
            bitmap = avatarCache[username] ?: loadAvatar(context, username)
        }
    }

    Box(
        modifier = modifier
            .size(V2Sizes.Avatar)
            .clip(V2Shapes.Avatar)
            .background(colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallbackText,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSecondary,
            )
        }
    }
}

fun preloadAvatars(scope: CoroutineScope, context: Context) {
    scope.launch(Dispatchers.IO) {
        teamMembers.mapNotNull { member ->
            member.username?.let { username -> async { loadAvatar(context.applicationContext, username) } }
        }.awaitAll()
    }
}

suspend fun loadAvatar(context: Context, username: String): ImageBitmap? = withContext(Dispatchers.IO) {
    avatarCache[username]?.let { return@withContext it }
    val cacheDir = File(context.cacheDir, "avatars").apply { if (!exists()) mkdirs() }
    val cacheFile = File(cacheDir, "$username.png")
    if (cacheFile.exists() && cacheFile.length() > 0L) {
        runCatching {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.asImageBitmap()?.also {
                avatarCache[username] = it
                return@withContext it
            }
        }
    }
    runCatching {
        var url = "https://github.com/$username.png"
        for (i in 0..3) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "csqttr-client")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                url = conn.getHeaderField("Location") ?: break
                continue
            }
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                cacheFile.writeBytes(bytes)
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also {
                    avatarCache[username] = it
                }
            }
            break
        }
        null
    }.getOrNull()
}
