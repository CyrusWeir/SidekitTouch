package hrln.interactive.sidekit.apps.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

private class MemoryTranslationStore(var saved: List<Translation> = emptyList()) : TranslationStore {
    override fun read() = saved
    override fun write(history: List<Translation>) { saved = history }
}

private class FakeTranslator(private val failure: Boolean = false) : Translator {
    override suspend fun translate(text: String, source: TranslateLanguage, target: TranslateLanguage): String {
        if (failure) throw IOException("offline")
        return "$text [${source.code}->${target.code}]"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var store: MemoryTranslationStore
    private lateinit var repository: TranslateRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = MemoryTranslationStore()
        repository = TranslateRepository(store, scope.backgroundScope, dispatcher) { 42L }
        scope.testScheduler.runCurrent()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `successful translation updates result and persisted history`() {
        val vm = TranslateViewModel(repository, FakeTranslator())
        vm.setText("Good morning")
        vm.translate()
        scope.testScheduler.runCurrent()
        assertEquals("Good morning [en->zh-CN]", vm.state.value.target.text)
        assertEquals(1, repository.history.value!!.size)
        assertEquals("Good morning", repository.history.value!!.first().sourceText)
        scope.testScheduler.runCurrent()
        assertEquals(repository.history.value, store.saved)
    }

    @Test fun `offline failure keeps source and explains error`() {
        val vm = TranslateViewModel(repository, FakeTranslator(failure = true))
        vm.setText("Hello")
        vm.translate()
        scope.testScheduler.runCurrent()
        assertEquals("Hello", vm.state.value.source.text)
        assertEquals("", vm.state.value.target.text)
        assertEquals("Translation failed. Check the connection and try again.", vm.state.value.error)
        assertEquals(false, vm.state.value.translating)
    }

    @Test fun `speech in target language translates back without sample output`() {
        val vm = TranslateViewModel(repository, FakeTranslator())
        vm.onHeard(TranslateSide.Target, "你好")
        scope.testScheduler.runCurrent()
        assertEquals("你好", vm.state.value.target.text)
        assertEquals("你好 [zh-CN->en]", vm.state.value.source.text)
        assertEquals(false, vm.state.value.translating)
    }

    @Test fun `swap and history restore preserve language pair`() {
        val vm = TranslateViewModel(repository, FakeTranslator())
        vm.setText("One")
        vm.translate()
        scope.testScheduler.runCurrent()
        vm.swapLanguages()
        assertEquals("Chinese", vm.state.value.source.language)
        assertEquals("English", vm.state.value.target.language)
        vm.open(repository.history.value!!.first())
        assertEquals("English", vm.state.value.source.language)
        assertEquals("One", vm.state.value.source.text)
    }
}
