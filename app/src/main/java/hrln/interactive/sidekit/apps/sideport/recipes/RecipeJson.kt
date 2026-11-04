package hrln.interactive.sidekit.apps.sideport.recipes

import android.content.Context
import android.util.Log
import hrln.interactive.sidekit.apps.sideport.interpret.Tone
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads recipes. One file holds one recipe object or an array of them:
 *
 * ```json
 * {
 *   "name": "Android dialog",
 *   "packages": ["*"],
 *   "whenPresent": ["id:android:id/button1"],
 *   "title": "id:android:id/alertTitle",
 *   "actions": [
 *     { "select": "id:android:id/button2", "tone": "muted" },
 *     { "select": "id:android:id/button1", "tone": "primary" }
 *   ],
 *   "options": [{ "select": "id:android:id/button3" }]
 * }
 * ```
 * Other keys: `screen`, `hide` (array of selectors), `list`, `itemTitle`, `itemSecondary`,
 * `priority`. An action may set `label` to rename the button.
 */
object RecipeJson {
    private const val TAG = "Sideport"
    const val ASSET_DIR = "sideport/recipes"

    fun parse(text: String, origin: String = "recipe"): List<Recipe> {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            (0 until array.length()).map { recipe(array.getJSONObject(it), "$origin[$it]") }
        } else listOf(recipe(JSONObject(trimmed), origin))
    }

    private fun recipe(o: JSONObject, origin: String) = Recipe(
        name = o.optString("name", origin),
        packages = o.optJSONArray("packages")?.strings() ?: listOf(o.getString("package")),
        whenPresent = o.optJSONArray("whenPresent")?.strings().orEmpty().map(Selector::parse),
        screen = o.optString("screen").takeIf { it.isNotEmpty() },
        title = o.selector("title"),
        hide = o.optJSONArray("hide")?.strings().orEmpty().map(Selector::parse),
        list = o.selector("list"),
        itemTitle = o.selector("itemTitle"),
        itemSecondary = o.selector("itemSecondary"),
        actions = o.optJSONArray("actions")?.actions().orEmpty(),
        options = o.optJSONArray("options")?.actions().orEmpty(),
        priority = o.optInt("priority", 0),
    )

    private fun JSONObject.selector(key: String): Selector? = optString(key).takeIf { it.isNotEmpty() }?.let(Selector::parse)

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONArray.actions(): List<RecipeAction> = (0 until length()).map { i ->
        when (val entry = get(i)) {
            is String -> RecipeAction(Selector.parse(entry))
            is JSONObject -> RecipeAction(
                selector = Selector.parse(entry.getString("select")),
                label = entry.optString("label").takeIf { it.isNotEmpty() },
                tone = entry.optString("tone").takeIf { it.isNotEmpty() }?.let { t -> Tone.entries.first { it.name.equals(t, true) } },
            )
            else -> throw IllegalArgumentException("Action $i must be a selector or an object")
        }
    }

    /** Where recipes can be dropped on the phone (`adb push my.json <this>/`). */
    fun userDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, ASSET_DIR)

    /** Built-in recipes plus the user's. A broken file is skipped (and logged), never fatal. */
    fun load(context: Context): RecipeBook {
        val out = ArrayList<Recipe>()
        val assets = context.assets
        assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            runCatching { out += parse(assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }, name) }
                .onFailure { Log.w(TAG, "Skipping recipe $name", it) }
        }
        userDir(context).listFiles { f -> f.extension == "json" }.orEmpty().forEach { file ->
            runCatching { out += parse(file.readText(), file.name) }
                .onFailure { Log.w(TAG, "Skipping recipe ${file.name}", it) }
        }
        return RecipeBook(out)
    }
}
