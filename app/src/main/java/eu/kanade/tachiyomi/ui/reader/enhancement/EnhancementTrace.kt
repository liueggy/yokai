package eu.kanade.tachiyomi.ui.reader.enhancement

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import java.io.File
import org.json.JSONObject

/** Private debug evidence only; never rendered over a page or enabled in release builds. */
internal object EnhancementTrace {
    private var file: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (BuildConfig.DEBUG) file = File(context.applicationContext.filesDir, "enhancement-performance.jsonl")
    }

    @Synchronized
    fun record(event: String, vararg fields: Pair<String, Any>) {
        val target = file ?: return
        runCatching {
            if (target.length() > 2 * 1024 * 1024) return
            val entry = JSONObject().put("event", event).put("timeMs", System.nanoTime() / 1_000_000)
            fields.forEach { (key, value) -> entry.put(key, value) }
            target.appendText(entry.toString() + "\n")
        }
    }
}
