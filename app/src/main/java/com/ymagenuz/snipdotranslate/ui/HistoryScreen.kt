package com.ymagenuz.snipdotranslate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ymagenuz.snipdotranslate.R

@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.title_history), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TextButton(onClick = viewModel::clearHistory) { Text(stringResource(R.string.history_clear)) }
            }
        }

        if (history.isEmpty()) {
            Text(stringResource(R.string.history_empty), modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn {
                items(history) { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.openHistoryEntry(entry)
                                onBack()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(entry.time, style = MaterialTheme.typography.labelSmall)
                        Text(entry.source, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            entry.result,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
