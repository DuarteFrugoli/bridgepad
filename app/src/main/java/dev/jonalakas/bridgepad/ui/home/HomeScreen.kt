package dev.jonalakas.bridgepad.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.ui.theme.BridgePadTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.baseline_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.baseline_description),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                InfoCard(
                    title = stringResource(R.string.app_information),
                    rows = listOf(
                        stringResource(R.string.version_label) to appVersion,
                        stringResource(R.string.status_label) to stringResource(R.string.status_early_development),
                    ),
                )
            }
            item {
                InfoCard(
                    title = stringResource(R.string.device_information),
                    rows = listOf(
                        stringResource(R.string.device_label) to deviceInfo.displayModel,
                        stringResource(R.string.android_label) to deviceInfo.androidVersion,
                        stringResource(R.string.api_level_label) to deviceInfo.sdkLevel.toString(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    BridgePadTheme {
        HomeScreen(
            appVersion = "0.1.0",
            deviceInfo = DeviceInfo(
                manufacturer = "Samsung",
                model = "Galaxy A55",
                androidVersion = "16",
                sdkLevel = 36,
            ),
        )
    }
}
