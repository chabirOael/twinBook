package io.github.chabiroael.twinbook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

/** A page dialog waiting for the user. The screen shows it and calls one of the answers. */
sealed class PendingPrompt(val message: String) {
    class Alert(message: String, val ok: () -> Unit) : PendingPrompt(message)

    class Confirm(message: String, val answer: (Boolean) -> Unit) : PendingPrompt(message)

    class Text(message: String, val default: String, val answer: (String?) -> Unit) : PendingPrompt(message)

    class Choice(message: String, val labels: List<String>, val answer: (Int?) -> Unit) : PendingPrompt(message)
}

/**
 * JavaScript dialogs in the plainest way: alert, confirm, prompt and single-choice selects become
 * one pending dialog at a time. Everything about saving or filling logins, cards or addresses,
 * file pickers, colour and date pickers, and HTTP auth is dismissed.
 */
class Prompts(
    /** Popups the popup blocker would block (no user gesture). The capture browser allows them; the shell does not. */
    private val allowPopupsWithoutGesture: Boolean = true,
) : PromptDelegate {
    private val pendingFlow = MutableStateFlow<PendingPrompt?>(null)
    val pending: StateFlow<PendingPrompt?> = pendingFlow.asStateFlow()

    private fun <P : PromptDelegate.BasePrompt> show(prompt: P, build: (complete: (() -> PromptResponse) -> Unit) -> PendingPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        var shown: PendingPrompt? = null
        var answered = false
        // prompt.dismiss() and prompt.confirm() may be called only once per prompt, so the
        // response is built only for the first answer.
        val complete: (() -> PromptResponse) -> Unit = { response ->
            if (pendingFlow.value === shown) pendingFlow.value = null
            if (!answered && !prompt.isComplete) {
                answered = true
                result.complete(response())
            }
        }
        shown = build(complete)
        prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(p: PromptDelegate.BasePrompt) {
                if (pendingFlow.value === shown) pendingFlow.value = null
            }
        })
        pendingFlow.value = shown
        return result
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> =
        show(prompt) { complete -> PendingPrompt.Alert(prompt.message.orEmpty()) { complete { prompt.dismiss() } } }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> =
        show(prompt) { complete ->
            PendingPrompt.Confirm(prompt.message.orEmpty()) { ok ->
                complete { prompt.confirm(if (ok) PromptDelegate.ButtonPrompt.Type.POSITIVE else PromptDelegate.ButtonPrompt.Type.NEGATIVE) }
            }
        }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> =
        show(prompt) { complete ->
            PendingPrompt.Text(prompt.message.orEmpty(), prompt.defaultValue.orEmpty()) { text -> complete { if (text == null) prompt.dismiss() else prompt.confirm(text) } }
        }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val choices = prompt.choices.filter { !it.separator && !it.disabled && it.items == null }
        if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE || choices.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())
        return show(prompt) { complete ->
            PendingPrompt.Choice(prompt.message.orEmpty(), choices.map { it.label }) { i -> complete { if (i == null) prompt.dismiss() else prompt.confirm(choices[i]) } }
        }
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> =
        show(prompt) { complete -> PendingPrompt.Confirm("Send the form data again?") { ok -> complete { prompt.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY) } } }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

    // window.open goes on to EngineSession's onNewSession, which loads it in the same session.
    // Gecko asks here only for popups without a user gesture.
    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(if (allowPopupsWithoutGesture) AllowOrDeny.ALLOW else AllowOrDeny.DENY))

    override fun onLoginSave(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(request.dismiss())

    override fun onLoginSelect(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(request.dismiss())

    override fun onCreditCardSave(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(request.dismiss())

    override fun onCreditCardSelect(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(request.dismiss())

    override fun onAddressSave(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(request.dismiss())

    override fun onAddressSelect(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(request.dismiss())

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.dismiss())

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.dismiss())

    override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.dismiss())

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.dismiss())

    override fun onSharePrompt(session: GeckoSession, prompt: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.dismiss())
}
