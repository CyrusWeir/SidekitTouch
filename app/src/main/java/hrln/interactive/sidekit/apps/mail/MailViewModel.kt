package hrln.interactive.sidekit.apps.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MailViewModel(
    private val repository: MailRepository,
    private val account: MailAccount,
) : ViewModel() {
    val messages: StateFlow<List<MailMessage>?> = repository.messages
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _undo = MutableStateFlow<Triple<MailMessage, Int, Long>?>(null)
    val undo: StateFlow<Triple<MailMessage, Int, Long>?> = _undo.asStateFlow()
    private var noticeJob: Job? = null
    private var undoJob: Job? = null

    fun saveDraft(id: Long?, to: String, subject: String, body: String) {
        repository.save(id, to, subject, body, MailFolder.Draft)
        say("Draft saved on this phone")
    }

    fun send(id: Long?, to: String, subject: String, body: String) {
        if (to.isBlank()) { say("Add a recipient first"); return }
        val message = repository.save(id, to, subject, body, MailFolder.Outbox)
        viewModelScope.launch {
            when (account.send(message)) {
                MailSendResult.HandedOff -> {
                    repository.folder(message.id, MailFolder.HandedOff)
                    say("Mail app opened; sending isn't confirmed")
                }
                MailSendResult.Unavailable -> say("No mail app. Message kept in Outbox")
            }
        }
    }

    fun delete(id: Long) {
        val (message, index) = repository.delete(id) ?: return
        undoJob?.cancel()
        val token = System.nanoTime()
        _undo.value = Triple(message, index, token)
        undoJob = viewModelScope.launch {
            delay(5_000)
            if (_undo.value?.third == token) _undo.value = null
        }
    }

    fun undo() {
        val value = _undo.value ?: return
        undoJob?.cancel()
        repository.restore(value.first, value.second)
        _undo.value = null
    }

    fun say(text: String) {
        noticeJob?.cancel()
        _notice.value = text
        noticeJob = viewModelScope.launch { delay(3_000); _notice.value = null }
    }

    fun dismissNotice() { noticeJob?.cancel(); _notice.value = null }
}
