package hrln.interactive.sidekit.apps.sidenotes

import androidx.activity.compose.BackHandler
import hrln.interactive.sidekit.design.components.SidekitTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitSwipeRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.components.rememberSwipeRevealGroup
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview
import kotlinx.coroutines.flow.filter

object SidenotesTags {
    const val LIST = "sidenotes_list"
    const val EDITOR = "sidenotes_editor"
}

/**
 * "My Notes multiple notes" (1:278). Rows swipe left to delete.
 *
 * Action bar: "New Sidenote →". For a few seconds after a delete it splits into
 * "Undo | New →", then merges back.
 */
@Composable
fun SidenotesListScreen(
    state: SidenotesUiState,
    onOpenNote: (Note) -> Unit,
    onNewNote: () -> Unit,
    onDelete: (Note) -> Unit,
    onUndoDelete: () -> Unit,
) {
    val undoable = state.recentlyDeleted != null
    val actions = if (undoable) {
        listOf(
            SideAction("undo", "Undo", SidekitIcon.Undo, tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Undo delete", onClick = onUndoDelete),
            SideAction("new", "New", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "New Sidenote", onClick = onNewNote),
        )
    } else {
        listOf(SideAction.forward("new", "New Sidenote", onNewNote))
    }

    SidekitScaffold(title = "My Sidenotes", actions = actions) { padding ->
        if (state.loaded && state.notes.isEmpty()) {
            EmptyNotes(Modifier.padding(padding))
            return@SidekitScaffold
        }
        val listState = rememberLazyListState()
        val swipe = rememberSwipeRevealGroup()
        // Scrolling closes an open row, as on iOS.
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { swipe.closeAll() }
        }
        SidekitList(padding, Modifier.testTag(SidenotesTags.LIST), state = listState) {
            itemsIndexed(state.notes, key = { _, note -> note.id }, contentType = { _, _ -> 0 }) { index, note ->
                SidekitSwipeRow(
                    key = note.id,
                    group = swipe,
                    onDelete = { onDelete(note) },
                    modifier = Modifier
                        .animateItem()
                        .listFade(listState, index),
                    deleteLabel = "Delete ${note.title}",
                ) {
                    SidekitItem(
                        title = note.title,
                        secondary = note.preview.ifEmpty { null },
                        onClick = { onOpenNote(note) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNotes(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.pageGap)) {
            SidekitText("No Sidenotes yet", style = SidekitTheme.typography.headline, textAlign = TextAlign.Center)
            SidekitText(
                "Your notes will appear here",
                style = SidekitTheme.typography.label,
                color = SidekitTheme.colors.surfaceA6,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private enum class EditorMode { Clean, Dirty, ConfirmDelete }

/**
 * "Existing Note" (1:312) / "Blank New note" (1:389), as one editor.
 *
 * The action bar narrates the editor's state:
 * - Clean: "← My Sidenotes" (+ a square delete for saved notes).
 * - Edited: back shrinks to a square and a green "Save ✓" splits out. Saving morphs back to Clean.
 * - Delete tapped: back turns into a grey "Cancel" and the delete square stretches into a red
 *   "Delete" button, a confirmation without a dialog.
 *
 * Leaving by any route saves; nothing is lost by backing out. With the T9 keypad, typing
 * replaces all of this with word suggestions until Done.
 */
@Composable
fun NoteEditorScreen(
    initialText: String,
    isNew: Boolean,
    canDelete: Boolean,
    onSave: (String) -> Unit,
    onClose: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val text = rememberTextFieldState(initialText)
    var saved by rememberSaveable { mutableStateOf(initialText) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val dirty by remember { derivedStateOf { !text.text.contentEquals(saved) } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Read the keyboard height only inside derivedStateOf: the editor recomposes when the keyboard
    // opens or closes, not on each frame of its slide.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardOpen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }

    val mode = when {
        confirmingDelete -> EditorMode.ConfirmDelete
        dirty -> EditorMode.Dirty
        else -> EditorMode.Clean
    }
    val close = { onClose(text.text.toString()) }
    val save = {
        val body = text.text.toString()
        onSave(body)
        saved = body
        focusManager.clearFocus()
    }

    BackHandler { if (confirmingDelete) confirmingDelete = false else close() }

    val actions = when (mode) {
        EditorMode.Clean -> listOfNotNull(
            SideAction.back("back", "My Sidenotes", close),
            if (canDelete) {
                SideAction.square("delete", SidekitIcon.DeleteForever, "Delete note", { focusManager.clearFocus(); confirmingDelete = true }, tone = ActionTone.Destructive)
            } else null,
        )
        EditorMode.Dirty -> listOf(
            SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Save and go back", close, motion = ActionMotion.Back),
            SideAction("save", "Save", SidekitIcon.Check, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Save note", onClick = save),
        )
        EditorMode.ConfirmDelete -> listOf(
            SideAction("back", "Cancel", tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Keep note") { confirmingDelete = false },
            SideAction("delete", "Delete", SidekitIcon.DeleteForever, tone = ActionTone.Destructive, weight = 1.2f, motion = ActionMotion.Up, contentDescription = "Confirm delete", onClick = onDelete),
        )
    }

    val title = remember(saved, isNew) { if (saved.isBlank()) "New Sidenote" else Note(0, saved, 0).title }
    SidekitScaffold(
        // On a 640 px panel a touch keyboard leaves little room; the title gives way to the text.
        title = if (keyboardOpen) null else title,
        actions = actions,
        // Keypad Options key: delete without hunting for the bin button (it may be hidden while editing).
        options = if (canDelete && mode != EditorMode.ConfirmDelete) {
            listOf(SideAction("delete", "Delete note", SidekitIcon.DeleteForever, tone = ActionTone.Destructive) { focusManager.clearFocus(); confirmingDelete = true })
        } else {
            emptyList()
        },
    ) { padding ->
        SidekitCard(Modifier.fillMaxSize().padding(padding)) {
            // With the T9 keypad attached the action bar becomes word suggestions while typing.
            SidekitTextField(
                state = text,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(SidenotesTags.EDITOR),
                placeholder = "Start typing…",
                focusRequester = focus,
                focusOnKeypad = true,
                autoFocus = isNew,
            )
        }
    }
}

@SidephonePreview
@Composable
private fun SidenotesListPreview() = SidekitPreviewFrame {
    SidenotesListScreen(SidenotesUiState(SidenotesSampleData.notes), {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun SidenotesUndoPreview() = SidekitPreviewFrame {
    SidenotesListScreen(SidenotesUiState(SidenotesSampleData.notes.drop(1), recentlyDeleted = SidenotesSampleData.notes.first()), {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun NoteEditorPreview() = SidekitPreviewFrame {
    NoteEditorScreen(SidenotesSampleData.notes.first().body, isNew = false, canDelete = true, {}, {}, {})
}

@SidephonePreview
@Composable
private fun EmptyNotesPreview() = SidekitPreviewFrame {
    SidenotesListScreen(SidenotesUiState(emptyList()), {}, {}, {}, {})
}
