package hrln.interactive.sidekit.apps.sideport.interpret

/**
 * Builds one continuous list out of the windows of rows an app shows as it scrolls.
 *
 * Apps only expose the rows on screen (RecyclerView and LazyColumn don't create the rest), so the
 * projection scrolls the real app as the Sidekit list nears an end and merges each new window into
 * what it has seen. Windows are aligned on the first row they share with the stitched list and
 * written over it at that offset, which works in both scroll directions. A window with nothing in
 * common (the app jumped, or the content changed) replaces the list.
 */
class ListStitcher {
    var rows: List<Block> = emptyList()
        private set

    /** Index range in [rows] that the app is showing right now. */
    var visible: IntRange = IntRange.EMPTY
        private set

    private var signature: String? = null

    fun reset() {
        rows = emptyList()
        visible = IntRange.EMPTY
        signature = null
    }

    /** Merges the [window] of rows currently on screen for the screen identified by [screen]. */
    fun merge(screen: String, window: List<Block>): List<Block> {
        if (screen != signature) {
            signature = screen
            rows = window
            visible = window.indices
            return rows
        }
        val (merged, at) = mergeWindow(rows, window)
        rows = merged
        visible = if (window.isEmpty()) IntRange.EMPTY else at until at + window.size
        return rows
    }

    companion object {
        /** Returns the merged list and the index the window starts at within it. */
        fun mergeWindow(stitched: List<Block>, window: List<Block>): Pair<List<Block>, Int> {
            if (window.isEmpty()) return stitched to 0
            if (stitched.isEmpty()) return window to 0
            val index = HashMap<String, Int>(stitched.size * 2)
            stitched.forEachIndexed { i, b -> index.putIfAbsent(b.key, i) }
            val anchorInWindow = window.indexOfFirst { it.key in index }
            if (anchorInWindow < 0) return window to 0
            val start = index.getValue(window[anchorInWindow].key) - anchorInWindow
            val before = if (start > 0) stitched.subList(0, start) else emptyList()
            val end = start + window.size
            val after = if (end < stitched.size) stitched.subList(end, stitched.size) else emptyList()
            // Rows from beyond the window that also appear inside it (the app inserted rows) would repeat.
            val inWindow = window.mapTo(HashSet()) { it.key }
            return (before.filterNot { it.key in inWindow } + window + after.filterNot { it.key in inWindow }) to
                (before.count { it.key !in inWindow })
        }
    }
}
