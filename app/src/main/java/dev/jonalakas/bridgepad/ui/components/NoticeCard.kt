package dev.jonalakas.bridgepad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jonalakas.bridgepad.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class NoticeTone {
    SUCCESS,
    WARNING,
    ERROR,
}

@Composable
fun NoticeCard(
    message: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val containerColor: Color
    val contentColor: Color
    val title: String

    when (tone) {
        NoticeTone.SUCCESS -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            title = stringResource(R.string.status_label)
        }
        NoticeTone.WARNING -> {
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            title = stringResource(R.string.notice_attention)
        }
        NoticeTone.ERROR -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            title = stringResource(R.string.state_error)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (actionLabel != null && onAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
