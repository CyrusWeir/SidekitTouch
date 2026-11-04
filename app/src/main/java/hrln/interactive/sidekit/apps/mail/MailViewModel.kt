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
    val account: MailAccount,
) : ViewModel() {
    val storageError = repository.error
    private var sending = false
    val messages: StateFlow<List<MailMessage>?> = repository.messages
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _undo = MutableStateFlow<Triple<MailMessage, Int, Long>?>(null)
    val undo: StateFlow<Triple<MailMessage, Int, Long>?> = _undo.asStateFlow()
    private var noticeJob: Job? = null
    private var undoJob: Job? = null

    fun saveDraft(id: Long?, to: String, subject: String, body: String) {
        if (messages.value == null) { say("Mail is not ready. Your draft has not been saved"); return }
        repository.save(id, to, subject, body, MailFolder.Draft)
        say("Draft saved on this phone")
    }

    fun send(id: Long?, to: String, subject: String, body: String) {
        if (sending || messages.value == null) { say("Mail is busy. Try again shortly"); return }
        if (to.isBlank()) { say("Add a recipient first"); return }
        sending = true
        val message = repository.save(id, to, subject, body, MailFolder.Outbox)
        viewModelScope.launch {
          try {
            when (account.send(message)) {
                MailSendResult.Sent -> { repository.folder(message.id, MailFolder.Sent); say("Accepted by your SMTP server") }
                MailSendResult.HandedOff -> {
                    repository.folder(message.id, MailFolder.HandedOff)
                    say("Mail app opened; sending isn't confirmed")
                }
                MailSendResult.Unavailable -> say("No mail app. Message kept in Outbox")
            }
          } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { say("Send failed or confirmation lost. Check Sent before retrying; message kept in Outbox") }
          finally { sending = false }
        }
    }

    val busy = MutableStateFlow(false)
    fun refresh() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { repository.replaceInbox(account.inbox()); say("Inbox updated · latest 100 messages") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { say("Inbox could not refresh. Check account settings and connection") }
            finally { busy.value = false }
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
