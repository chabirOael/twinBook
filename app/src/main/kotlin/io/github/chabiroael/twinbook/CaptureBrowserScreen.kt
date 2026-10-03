package io.github.chabiroael.twinbook

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.chabiroael.twinbook.engine.capture.CaptureRecorder
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView

/**
 * Development screen for the owner's capture session: a GeckoView filling most of the screen,
 * a mobile/desktop site switch, back, reload, the current URL, and capture controls.
 */
@Composable
fun CaptureBrowserScreen(browser: CaptureBrowser, onExit: () -> Unit) {
    val site by browser.site.collectAsState()
    val session = browser.sessions.getValue(site)
    val page by session.page.collectAsState()
    val ready by browser.ready.collectAsState()
    val capture by browser.recorder.state.collectAsState()
    val prompt by browser.prompts.pending.collectAsState()
    val scope = browser.engine.scope

    BackHandler {
        if (page.canGoBack) browser.back() else onExit()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    CaptureBrowser.Site.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = s == site,
                            onClick = { browser.select(s) },
                            shape = SegmentedButtonDefaults.itemShape(i, CaptureBrowser.Site.entries.size),
                            modifier = Modifier.testTag("site-${s.name.lowercase()}"),
                        ) { Text(if (s == CaptureBrowser.Site.MOBILE) "Mobile site" else "Desktop site") }
                    }
                }
                TextButton(onClick = { browser.back() }, enabled = page.canGoBack, modifier = Modifier.testTag("back")) { Text("Back") }
                TextButton(onClick = { browser.reload() }, modifier = Modifier.testTag("reload")) { Text("Reload") }
            }
            Text(
                text = if (!ready) "waiting for the engine…" else page.url ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("url"),
            )
            val lastOnDisk = remember(capture) { browser.recorder.store.list().lastOrNull()?.let { "${it.id}: ${if (it.finalized) "finalized" else "NOT finalized"}" } }
            CaptureControls(capture, lastOnDisk) { action ->
                scope.launch {
                    runCatching {
                        when (action) {
                            CaptureAction.START -> browser.startCapture()
                            CaptureAction.STOP -> browser.recorder.stop()
                            CaptureAction.DISCARD -> browser.recorder.discard()
                        }
                    }.onFailure { android.util.Log.e(AppEngine.TAG, "capture $action failed", it) }
                }
            }
            // The page dialog lies over the top of the page, so the page never moves under it.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { context ->
                        GeckoView(context).also {
                            it.setAutofillEnabled(false)
                            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        }
                    },
                    update = { view ->
                        if (view.session !== session.geckoSession) {
                            browser.sessions.values.filter { it !== session }.forEach { if (it.view === view) it.detach() }
                            session.attach(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize().testTag("gecko"),
                )
                prompt?.let { PromptPanel(it) }
            }
            DisposableEffect(session) {
                onDispose { session.detach() }
            }
        }
    }
}

enum class CaptureAction { START, STOP, DISCARD }

@Composable
private fun CaptureControls(state: CaptureRecorder.State, lastOnDisk: String?, onAction: (CaptureAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is CaptureRecorder.State.Idle -> Button(onClick = { onAction(CaptureAction.START) }, modifier = Modifier.testTag("capture-start")) { Text("Start capture") }
                is CaptureRecorder.State.Recording -> {
                    Button(onClick = { onAction(CaptureAction.STOP) }, modifier = Modifier.testTag("capture-stop")) { Text("Stop and finalize") }
                    OutlinedButton(onClick = { onAction(CaptureAction.DISCARD) }, modifier = Modifier.testTag("capture-discard")) { Text("Discard") }
                }
                is CaptureRecorder.State.Finalizing -> Text("Finalizing ${state.done}/${state.total}…", modifier = Modifier.testTag("capture-finalizing"))
            }
        }
        Text(CaptureText.status(state, lastOnDisk), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("capture-status"))
    }
}

/** Status lines of the capture controls, kept apart so tests can build the expected strings. */
object CaptureText {
    fun status(state: CaptureRecorder.State, lastOnDisk: String?): String = when (state) {
        is CaptureRecorder.State.Recording -> {
            val c = state.counters
            "Recording ${state.id}: ${c.records} records, ${mb(c.bytes)}, ${c.redactions} redactions, ${c.errors} errors"
        }
        is CaptureRecorder.State.Finalizing -> "Finalizing ${state.id}"
        is CaptureRecorder.State.Idle -> state.last?.let { last(it) } ?: ("Last session on disk: " + (lastOnDisk ?: "none"))
    }

    fun last(l: CaptureRecorder.LastSession): String =
        if (l.finalized) "Last session ${l.id}: FINALIZED, ${l.files} files, ${mb(l.bytes)}, ${l.replacements} taint replacements"
        else "Last session ${l.id}: NOT finalized (${l.message})"

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)
}

/**
 * A page dialog as a plain panel inside the screen (not a separate dialog window), so it is
 * answered only by its own buttons.
 */
@Composable
internal fun PromptPanel(p: PendingPrompt) {
    var text by remember(p) { mutableStateOf((p as? PendingPrompt.Text)?.default.orEmpty()) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("prompt")) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The page says", style = MaterialTheme.typography.titleSmall)
            Text(p.message, modifier = Modifier.testTag("prompt-message"))
            when (p) {
                is PendingPrompt.Text -> OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("prompt-text"))
                is PendingPrompt.Choice -> p.labels.forEachIndexed { i, label ->
                    Text(label, modifier = Modifier.fillMaxWidth().clickable { p.answer(i) }.padding(vertical = 10.dp).testTag("prompt-choice-$i"))
                }
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (p) {
                    is PendingPrompt.Alert -> Button(onClick = p.ok, modifier = Modifier.testTag("prompt-ok")) { Text("OK") }
                    is PendingPrompt.Confirm -> {
                        Button(onClick = { p.answer(true) }, modifier = Modifier.testTag("prompt-ok")) { Text("OK") }
                        OutlinedButton(onClick = { p.answer(false) }, modifier = Modifier.testTag("prompt-cancel")) { Text("Cancel") }
                    }
                    is PendingPrompt.Text -> {
                        Button(onClick = { p.answer(text) }, modifier = Modifier.testTag("prompt-ok")) { Text("OK") }
                        OutlinedButton(onClick = { p.answer(null) }, modifier = Modifier.testTag("prompt-cancel")) { Text("Cancel") }
                    }
                    is PendingPrompt.Choice -> OutlinedButton(onClick = { p.answer(null) }, modifier = Modifier.testTag("prompt-cancel")) { Text("Cancel") }
                }
            }
        }
    }
}
