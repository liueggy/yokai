package eu.kanade.tachiyomi.ui.reader.settings

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.enhancement.EnhancementModelStore
import eu.kanade.tachiyomi.ui.reader.enhancement.EnhancementPreviewResult
import eu.kanade.tachiyomi.ui.reader.enhancement.EnhancementRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import yokai.util.lang.getString

/** Reader-local controls and an uncached, inspectable preview of the current page crop. */
class ReaderEnhancementDialog(private val activity: ReaderActivity) {

    private val readerPreferences: ReaderPreferences by injectLazy()
    private var dialog: AlertDialog? = null
    private var modelLoadJob: Job? = null
    private var previewJob: Job? = null
    private var changed = false
    private var dismissed = false
    private var previewResult: EnhancementPreviewResult? = null
    private var showingEnhanced = true

    private lateinit var modelButton: MaterialButton
    private lateinit var testButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var timing: TextView
    private lateinit var timingNote: TextView
    private lateinit var preview: PhotoView
    private lateinit var previewToggle: MaterialButtonToggleGroup
    private lateinit var originalButton: MaterialButton
    private lateinit var enhancedButton: MaterialButton

    fun show() {
        val content = createContent()
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(MR.strings.reader_image_enhancement))
            .setView(content)
            .setNegativeButton(activity.getString(MR.strings.cancel), null)
            .create()

        dialog?.setOnDismissListener {
            dismissed = true
            modelLoadJob?.cancel()
            previewJob?.cancel()
            preview.setImageDrawable(null)
            recyclePreview()
            if (changed) activity.refreshPageEnhancement()
            dialog = null
        }
        dialog?.show()
        loadModels()
    }

    private fun createContent(): View {
        val padding = 24.dp
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 8.dp, padding, 8.dp)
        }

        val enabledSwitch = MaterialSwitch(activity).apply {
            text = activity.getString(MR.strings.enable_reader_image_enhancement)
            isChecked = readerPreferences.enhancePages().get()
            setOnCheckedChangeListener { _, enabled ->
                if (readerPreferences.enhancePages().get() != enabled) {
                    readerPreferences.enhancePages().set(enabled)
                    EnhancementRuntime.invalidate()
                    changed = true
                }
            }
        }
        column.addView(enabledSwitch, matchWrap())

        column.addView(label(activity.getString(MR.strings.enhancement_model)), matchWrap(top = 12.dp))
        modelButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isEnabled = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = activity.getString(MR.strings.enhancement_models_loading)
        }
        column.addView(modelButton, matchWrap(top = 4.dp))

        val manageButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(MR.strings.manage_enhancement_models)
            setOnClickListener {
                activity.startActivity(SearchActivity.openReaderSettings(activity))
                dialog?.dismiss()
            }
        }
        column.addView(manageButton, matchWrap(top = 8.dp))

        column.addView(label(activity.getString(MR.strings.current_page_partial_preview)), matchWrap(top = 16.dp))
        previewToggle = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isVisible = false
        }
        originalButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = activity.getString(MR.strings.original_image)
        }
        enhancedButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = activity.getString(MR.strings.enhanced_image)
        }
        previewToggle.addView(originalButton, weightedWrap())
        previewToggle.addView(enhancedButton, weightedWrap())
        previewToggle.check(enhancedButton.id)
        previewToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showingEnhanced = checkedId == enhancedButton.id
            showSelectedPreview()
        }
        column.addView(previewToggle, matchWrap(top = 6.dp))

        preview = PhotoView(activity).apply {
            setBackgroundColor(Color.rgb(24, 24, 24))
            contentDescription = activity.getString(MR.strings.current_page_partial_preview)
            minimumScale = 1f
            mediumScale = 2f
            maximumScale = 5f
        }
        column.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 260.dp).apply { topMargin = 8.dp })

        progress = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            isVisible = false
        }
        column.addView(progress, matchWrap(top = 8.dp))

        status = label(activity.getString(MR.strings.enhancement_preview_hint)).apply {
            gravity = Gravity.CENTER
        }
        column.addView(status, matchWrap(top = 6.dp))

        timing = label("").apply {
            gravity = Gravity.CENTER
            isVisible = false
        }
        column.addView(timing, matchWrap(top = 6.dp))

        timingNote = label(activity.getString(MR.strings.enhancement_preview_duration_note)).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            isVisible = false
        }
        column.addView(timingNote, matchWrap(top = 2.dp))

        testButton = MaterialButton(activity).apply {
            text = activity.getString(MR.strings.test_current_page_partial)
            isEnabled = false
            setOnClickListener {
                if (previewJob?.isActive == true) cancelPreview() else runPreview()
            }
        }
        column.addView(testButton, matchWrap(top = 8.dp))

        return ScrollView(activity).apply { addView(column) }
    }

    private fun loadModels() {
        modelLoadJob = activity.lifecycleScope.launch {
            val models = try {
                withContext(Dispatchers.IO) { EnhancementModelStore.models(activity) }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                if (!dismissed) {
                    modelButton.text = activity.getString(MR.strings.no_enhancement_models)
                    status.text = activity.getString(MR.strings.enhancement_preview_error)
                }
                return@launch
            }
            if (dismissed) return@launch
            if (models.isEmpty()) {
                modelButton.text = activity.getString(MR.strings.no_enhancement_models)
                status.text = activity.getString(MR.strings.enhancement_preview_unsupported)
                return@launch
            }

            fun displayName(index: Int): String {
                val model = models[index]
                return if (model.isBundled) activity.getString(MR.strings.enhancement_model_bundled) else model.displayName
            }

            var selected = models.indexOfFirst { it.id == readerPreferences.enhancementModel().get() }
                .takeIf { it >= 0 } ?: models.indexOfFirst { it.isBundled }.coerceAtLeast(0)
            val selectedModel = models[selected]
            if (readerPreferences.enhancementModel().get() != selectedModel.id) {
                readerPreferences.enhancementModel().set(selectedModel.id)
                EnhancementRuntime.invalidate()
                changed = true
            }
            modelButton.text = displayName(selected)
            modelButton.isEnabled = true
            testButton.isEnabled = true
            modelButton.setOnClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(MR.strings.enhancement_model))
                    .setSingleChoiceItems(models.indices.map(::displayName).toTypedArray(), selected) { chooser, which ->
                        selected = which
                        val model = models[which]
                        modelButton.text = displayName(which)
                        if (readerPreferences.enhancementModel().get() != model.id) {
                            readerPreferences.enhancementModel().set(model.id)
                            EnhancementRuntime.invalidate()
                            changed = true
                            clearPreview(activity.getString(MR.strings.enhancement_preview_hint))
                        }
                        chooser.dismiss()
                    }
                    .setNegativeButton(activity.getString(MR.strings.cancel), null)
                    .show()
            }
        }
    }

    private fun runPreview() {
        if (previewJob?.isActive == true) return
        previewJob = activity.lifecycleScope.launch {
            clearPreview(activity.getString(MR.strings.enhancement_preview_running))
            progress.isVisible = true
            testButton.text = activity.getString(MR.strings.cancel_enhancement_test)
            modelButton.isEnabled = false
            try {
                val source = activity.enhancementPreviewSource()
                var elapsedMillis = 0L
                val result = source?.let {
                    val selectedModelId = readerPreferences.enhancementModel().get()
                    val startedAt = SystemClock.elapsedRealtime()
                    EnhancementRuntime.preview(activity, it, selectedModelId).also {
                        elapsedMillis = SystemClock.elapsedRealtime() - startedAt
                    }
                }
                if (result == null) {
                    clearPreview(activity.getString(MR.strings.enhancement_preview_unsupported))
                } else {
                    preview.setImageDrawable(null)
                    recyclePreview()
                    previewResult = result
                    previewToggle.isVisible = true
                    showSelectedPreview()
                    status.text = activity.getString(MR.strings.enhancement_preview_success)
                    timing.text = activity.getString(
                        MR.strings.enhancement_preview_duration,
                        elapsedMillis / 1_000.0,
                    )
                    timing.isVisible = true
                    timingNote.isVisible = true
                }
            } catch (_: CancellationException) {
                clearTiming()
                if (!dismissed) status.text = activity.getString(MR.strings.enhancement_preview_cancelled)
            } catch (error: Throwable) {
                android.util.Log.e("ReaderEnhancement", "Preview failed", error)
                if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                    runCatching { java.io.File(activity.filesDir, "enhancement-preview-error.txt").writeText(error.stackTraceToString()) }
                }
                clearPreview(activity.getString(MR.strings.enhancement_preview_error))
            } finally {
                if (!dismissed) {
                    progress.isVisible = false
                    testButton.text = activity.getString(MR.strings.test_current_page_partial)
                    testButton.isEnabled = true
                    modelButton.isEnabled = true
                }
                previewJob = null
            }
        }
    }

    private fun cancelPreview() {
        testButton.isEnabled = false
        previewJob?.cancel()
    }

    private fun showSelectedPreview() {
        val result = previewResult ?: return
        preview.setImageBitmap(if (showingEnhanced) result.enhanced else result.original)
        preview.setScale(1f, false)
    }

    private fun clearPreview(message: String) {
        preview.setImageDrawable(null)
        recyclePreview()
        previewToggle.isVisible = false
        clearTiming()
        status.text = message
    }

    private fun clearTiming() {
        timing.text = ""
        timing.isVisible = false
        timingNote.isVisible = false
    }

    private fun recyclePreview() {
        previewResult?.let { result ->
            recycle(result.original)
            if (result.enhanced !== result.original) recycle(result.enhanced)
        }
        previewResult = null
    }

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun label(value: String) = TextView(activity).apply { text = value }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun weightedWrap() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private val Int.dp: Int get() = (this * activity.resources.displayMetrics.density).toInt()
}
