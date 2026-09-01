package io.github.lectorsys.symus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.ui.SearchViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProofOfConcept()
            }
        }
    }
}

/**
 * Search, tap, hear it. The whole point of this screen is to prove on-device
 * extraction works end to end; the real UI arrives with the shelves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProofOfConcept(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("SYmuS") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search YouTube") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(onClick = viewModel::search, enabled = !state.searching) {
                Text(if (state.searching) "Searching…" else "Search")
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            state.nowPlaying?.let { track ->
                Text("Playing: ${track.title}", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = viewModel::togglePause) { Text("Play / pause") }
            }

            if (state.searching) CircularProgressIndicator()

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.results, key = { it.videoId }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title, maxLines = 2) },
                        supportingContent = {
                            Text(listOfNotNull(track.artist, track.genre).joinToString(" · "))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.play(track) }
                    )
                }
            }
        }
    }
}
