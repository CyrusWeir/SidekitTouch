package hrln.interactive.sidekit.apps.sidemessage

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Message(val text: String, val fromMe: Boolean)

data class Conversation(val contact: String, val messages: List<Message>) {
    val preview: String get() = messages.lastOrNull()?.text.orEmpty()
}

/**
 * @property draft message being typed in the open conversation ("text - typing", 35:268), or
 * null when not typing.
 */
data class SidemessageUiState(
    val pinned: List<String> = SidemessageSampleData.pinned,
    val conversations: List<Conversation> = SidemessageSampleData.conversations,
    val draft: String? = null,
) {
    fun conversation(contact: String): Conversation =
        conversations.firstOrNull { it.contact == contact } ?: Conversation(contact, emptyList())

    /** Inbox rows: everything that isn't pinned to the top row. */
    val threads: List<Conversation> get() = conversations.filterNot { it.contact in pinned }
}

class SidemessageViewModel : ViewModel() {
    private val _state = MutableStateFlow(SidemessageUiState())
    val state: StateFlow<SidemessageUiState> = _state.asStateFlow()

    fun startTyping() = _state.update { it.copy(draft = it.draft ?: "") }

    fun updateDraft(text: String) = _state.update { if (it.draft == null) it else it.copy(draft = text) }

    fun stopTyping() = _state.update { it.copy(draft = null) }

    /** Appends the draft to [contact]'s conversation (creating it if needed) unless blank. */
    fun send(contact: String) = _state.update { state ->
        val text = state.draft?.trim().orEmpty()
        if (text.isEmpty()) return@update state.copy(draft = null)
        val existing = state.conversation(contact)
        val updated = existing.copy(messages = existing.messages + Message(text, fromMe = true))
        val others = state.conversations.filterNot { it.contact == contact }
        state.copy(conversations = listOf(updated) + others, draft = null)
    }
}

object SidemessageSampleData {
    // From "sidemessage home" (35:98) and "text" (35:145).
    val pinned = listOf("Amelie", "Mum")
    val conversations = listOf(
        Conversation(
            "Amelie",
            listOf(
                Message("Im free tn", fromMe = false),
                Message("do u wanna do something later?", fromMe = false),
                Message("Yeah i’ll come over soon", fromMe = true),
            ),
        ),
        Conversation("Mum", emptyList()),
        Conversation("Josie", listOf(Message("Yeah should be all good", fromMe = false))),
        Conversation("Ari", listOf(Message("Dont forget to go over to the", fromMe = false))),
        Conversation("Fio", listOf(Message("Its on sunday so come whenever", fromMe = false))),
    )
}
