package eu.kanade.tachiyomi.ui.setting.controllers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.ui.reader.enhancement.EnhancementModelStore
import eu.kanade.tachiyomi.ui.reader.enhancement.EnhancementRuntime
import eu.kanade.tachiyomi.ui.reader.enhancement.InstalledEnhancementModel
import eu.kanade.tachiyomi.ui.reader.settings.OrientationType
import eu.kanade.tachiyomi.ui.reader.settings.PageLayout
import eu.kanade.tachiyomi.ui.reader.settings.ReaderBackgroundColor
import eu.kanade.tachiyomi.ui.reader.settings.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.settings.ReadingModeType
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.listPreference
import eu.kanade.tachiyomi.ui.setting.multiSelectListPreferenceMat
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.util.lang.addBetaTag
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isTablet
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.widget.preference.ListMatPreference
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.settings.ReaderPreferences
import yokai.domain.ui.settings.ReaderPreferences.CutoutBehaviour
import yokai.domain.ui.settings.ReaderPreferences.LandscapeCutoutBehaviour
import yokai.i18n.MR
import yokai.util.lang.getString
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

class SettingsReaderController : SettingsLegacyController() {

    private val readerPreferences: ReaderPreferences by injectLazy()
    private var enhancementModelPreference: ListMatPreference? = null
    private var importEnhancementModelPreference: Preference? = null
    private var isImportingEnhancementModel = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.reader

        preferenceCategory {
            titleRes = MR.strings.general
            intListPreference(activity) {
                key = Keys.defaultReadingMode
                titleRes = MR.strings.default_reading_mode
                entriesRes = ReadingModeType.entries.drop(1)
                    .map { value -> value.stringRes }.toTypedArray()
                entryValues = ReadingModeType.entries.drop(1)
                    .map { value -> value.flagValue }
                defaultValue = 2
            }
            intListPreference(activity) {
                key = Keys.doubleTapAnimationSpeed
                titleRes = MR.strings.double_tap_anim_speed
                entries = listOf(
                    context.getString(MR.strings.no_animation),
                    context.getString(
                        MR.strings.fast,
                    ),
                    context.getString(MR.strings.normal),
                )
                entryValues = listOf(1, 250, 500) // using a value of 0 breaks the image viewer, so
                // min is 1
                defaultValue = 500
            }
            switchPreference {
                key = Keys.enableTransitions
                titleRes = MR.strings.animate_page_transitions
                defaultValue = true
            }
            switchPreference {
                key = Keys.enhancePages
                titleRes = MR.strings.enhance_pages
                summaryRes = MR.strings.enhance_pages_summary
                defaultValue = false
            }
            enhancementModelPreference = listPreference(activity) {
                bindTo(readerPreferences.enhancementModel())
                titleRes = MR.strings.enhancement_model
                onChange {
                    EnhancementRuntime.invalidate()
                    true
                }
            }
            loadEnhancementModels()
            importEnhancementModelPreference = preference {
                titleRes = MR.strings.import_enhancement_model
                summaryRes = MR.strings.import_enhancement_model_summary
                onClick { openEnhancementModelPicker() }
            }
            intListPreference(activity) {
                key = Keys.preloadSize
                titleRes = MR.strings.page_preload_amount
                entryValues = listOf(4, 6, 8, 10, 12, 14, 16, 20)
                entries = entryValues.map { context.getString(MR.plurals.pages_plural, it, it) }
                defaultValue = 6
                summaryRes = MR.strings.amount_of_pages_to_preload
            }
            multiSelectListPreferenceMat(activity) {
                key = Keys.readerBottomButtons
                titleRes = MR.strings.display_buttons_bottom_reader
                val enumConstants = ReaderBottomButton.entries
                entriesRes = ReaderBottomButton.entries.map { it.stringRes }.toTypedArray()
                entryValues = enumConstants.map { it.value }
                allSelectionRes = MR.strings.display_options
                allIsAlwaysSelected = true
                showAllLast = true
                val defaults = ReaderBottomButton.BUTTONS_DEFAULTS.toMutableList()
                if (context.isTablet()) {
                    defaults.add(ReaderBottomButton.ShiftDoublePage.value)
                }
                defaultValue = defaults
            }
            infoPreference(MR.strings.certain_buttons_can_be_found)
        }

        preferenceCategory {
            titleRes = MR.strings.display

            intListPreference(activity) {
                key = Keys.defaultOrientationType
                titleRes = MR.strings.default_orientation
                val enumConstants = OrientationType.entries.drop(1)
                entriesRes = enumConstants.map { it.stringRes }.toTypedArray()
                entryValues = enumConstants.map { value -> value.flagValue }
                defaultValue = OrientationType.FREE.flagValue
            }
            intListPreference(activity) {
                key = Keys.readerTheme
                titleRes = MR.strings.background_color
                val enumConstants = ReaderBackgroundColor.entries
                entriesRes = enumConstants.map { it.longStringRes ?: it.stringRes }.toTypedArray()
                entryValues = enumConstants.map { it.prefValue }
                defaultValue = ReaderBackgroundColor.SMART_PAGE.prefValue
            }
            switchPreference {
                key = Keys.fullscreen
                titleRes = MR.strings.fullscreen
                defaultValue = true
            }
            switchPreference {
                bindTo(readerPreferences.cutoutShort())
                // FIXME: Transition from reader to homepage is broken when cutout short is disabled
                title = context.getString(MR.strings.pref_cutout_short).addBetaTag(context)

                preferences.fullscreen().changesIn(viewScope) {
                    isVisible = DeviceUtil.hasCutout(activity).ordinal >= DeviceUtil.CutoutSupport.MODERN.ordinal && it
                }
            }
            if (DeviceUtil.isVivo && DeviceUtil.hasCutout(activity) == DeviceUtil.CutoutSupport.LEGACY) {
                preference {
                    title = context.getString(MR.strings.pref_legacy_cutout).addBetaTag(context)
                    summaryRes = MR.strings.pref_legacy_cutout_info

                    onClick {
                        val intent = Intent().apply {
                            setComponent(ComponentName("com.android.settings", "com.vivo.settings.display.FullScreenDisplayActivity"))
                        }
                        startActivity(intent)
                    }
                }
            }
            listPreference(activity) {
                bindTo(readerPreferences.landscapeCutoutBehavior())
                title = "${context.getString(MR.strings.cutout_area_behavior)} (${context.getString(MR.strings.landscape)})"
                val values = LandscapeCutoutBehaviour.entries
                entriesRes = values.map { it.titleResId }.toTypedArray()
                entryValues = values.map { it.name }

                preferences.fullscreen().changesIn(viewScope) {
                    isVisible = DeviceUtil.hasCutout(activity).ordinal >= DeviceUtil.CutoutSupport.MODERN.ordinal && it
                }
            }
            switchPreference {
                key = Keys.keepScreenOn
                titleRes = MR.strings.keep_screen_on
                defaultValue = true
            }
            switchPreference {
                key = Keys.showPageNumber
                titleRes = MR.strings.show_page_number
                defaultValue = true
            }
        }

        preferenceCategory {
            titleRes = MR.strings.reading

            switchPreference {
                key = Keys.skipRead
                titleRes = MR.strings.skip_read_chapters
                defaultValue = false
            }
            switchPreference {
                key = Keys.skipFiltered
                titleRes = MR.strings.skip_filtered_chapters
                defaultValue = true
            }
            switchPreference {
                bindTo(preferences.skipDupe())
                titleRes = MR.strings.skip_dupe_chapters
            }
            switchPreference {
                key = Keys.alwaysShowChapterTransition
                titleRes = MR.strings.always_show_chapter_transition
                summaryRes = MR.strings.if_disabled_transition_will_skip
                defaultValue = true
            }
        }

        preferenceCategory {
            titleRes = MR.strings.paged

            intListPreference(activity) {
                key = Keys.navigationModePager
                titleRes = MR.strings.tap_zones
                entries = context.resources.getStringArray(R.array.reader_nav).also { values ->
                    entryRange = 0..values.size
                }.toList()
                defaultValue = "0"
            }
            listPreference(activity) {
                key = Keys.pagerNavInverted
                titleRes = MR.strings.invert_tapping
                entriesRes = arrayOf(
                    MR.strings.none,
                    MR.strings.horizontally,
                    MR.strings.vertically,
                    MR.strings.both_axes,
                )
                entryValues = listOf(
                    ViewerNavigation.TappingInvertMode.NONE.name,
                    ViewerNavigation.TappingInvertMode.HORIZONTAL.name,
                    ViewerNavigation.TappingInvertMode.VERTICAL.name,
                    ViewerNavigation.TappingInvertMode.BOTH.name,
                )
                defaultValue = ViewerNavigation.TappingInvertMode.NONE.name
            }

            intListPreference(activity) {
                key = Keys.imageScaleType
                titleRes = MR.strings.scale_type
                entriesRes = arrayOf(
                    MR.strings.fit_screen,
                    MR.strings.stretch,
                    MR.strings.fit_width,
                    MR.strings.fit_height,
                    MR.strings.original_size,
                    MR.strings.smart_fit,
                )
                entryRange = 1..6
                defaultValue = 1
            }

            listPreference(activity) {
                bindTo(readerPreferences.pagerCutoutBehavior())
                titleRes = MR.strings.cutout_area_behavior
                val values = CutoutBehaviour.entries
                entriesRes = values.map { it.titleResId }.toTypedArray()
                entryValues = values.map { it.name }

                // Calling this once to show only on cutout
                isVisible = DeviceUtil.hasCutout(activity).ordinal >= DeviceUtil.CutoutSupport.LEGACY.ordinal

                // Calling this a second time in case activity is recreated while on this page
                // Keep the first so it shouldn't animate hiding the preference for phones without
                // cutouts
                activityBinding?.root?.post {
                    isVisible = DeviceUtil.hasCutout(activity).ordinal >= DeviceUtil.CutoutSupport.LEGACY.ordinal
                }
            }

            switchPreference {
                bindTo(preferences.landscapeZoom())
                titleRes = MR.strings.zoom_double_page_spreads
                visibleIf(preferences.imageScaleType()) { it == 1 }
            }
            intListPreference(activity) {
                key = Keys.zoomStart
                titleRes = MR.strings.zoom_start_position
                entriesRes = arrayOf(
                    MR.strings.automatic,
                    MR.strings.left,
                    MR.strings.right,
                    MR.strings.center,
                )
                entryRange = 1..4
                defaultValue = 1
            }
            switchPreference {
                key = Keys.cropBorders
                titleRes = MR.strings.crop_borders
                defaultValue = false
            }
            switchPreference {
                bindTo(preferences.navigateToPan())
                titleRes = MR.strings.navigate_pan
            }
            intListPreference(activity) {
                key = Keys.pageLayout
                title = context.getString(MR.strings.page_layout).addBetaTag(context)
                dialogTitleRes = MR.strings.page_layout
                val enumConstants = PageLayout.entries
                entriesRes = enumConstants.map { it.fullStringRes }.toTypedArray()
                entryValues = enumConstants.map { it.value }
                defaultValue = PageLayout.AUTOMATIC.value
            }
            infoPreference(MR.strings.automatic_can_still_switch).apply {
                preferences.pageLayout().changesIn(viewScope) { isVisible = it == PageLayout.AUTOMATIC.value }
            }
            switchPreference {
                key = Keys.automaticSplitsPage
                titleRes = MR.strings.split_double_pages_portrait
                defaultValue = false
                preferences.pageLayout().changesIn(viewScope) { isVisible = it == PageLayout.AUTOMATIC.value }
            }
            switchPreference {
                key = Keys.invertDoublePages
                titleRes = MR.strings.invert_double_pages
                defaultValue = false
                preferences.pageLayout().changesIn(viewScope) { isVisible = it != PageLayout.SINGLE_PAGE.value }
            }
        }
        preferenceCategory {
            titleRes = MR.strings.long_strip

            intListPreference(activity) {
                key = Keys.navigationModeWebtoon
                titleRes = MR.strings.tap_zones
                entries = context.resources.getStringArray(R.array.reader_nav).also { values ->
                    entryRange = 0..values.size
                }.toList()
                defaultValue = "0"
            }
            listPreference(activity) {
                key = Keys.webtoonNavInverted
                titleRes = MR.strings.invert_tapping
                entriesRes = arrayOf(
                    MR.strings.none,
                    MR.strings.horizontally,
                    MR.strings.vertically,
                    MR.strings.both_axes,
                )
                entryValues = listOf(
                    ViewerNavigation.TappingInvertMode.NONE.name,
                    ViewerNavigation.TappingInvertMode.HORIZONTAL.name,
                    ViewerNavigation.TappingInvertMode.VERTICAL.name,
                    ViewerNavigation.TappingInvertMode.BOTH.name,
                )
                defaultValue = ViewerNavigation.TappingInvertMode.NONE.name
            }
            listPreference(activity) {
                bindTo(preferences.webtoonReaderHideThreshold())
                titleRes = MR.strings.pref_hide_threshold
                val enumValues = PreferenceValues.ReaderHideThreshold.entries
                entriesRes = enumValues.map { it.titleResId }.toTypedArray()
                entryValues = enumValues.map { it.name }
            }
            switchPreference {
                key = Keys.cropBordersWebtoon
                titleRes = MR.strings.crop_borders
                defaultValue = false
            }

            intListPreference(activity) {
                key = Keys.webtoonSidePadding
                titleRes = MR.strings.pref_long_strip_side_padding
                entriesRes = arrayOf(
                    MR.strings.long_strip_side_padding_0,
                    MR.strings.long_strip_side_padding_5,
                    MR.strings.long_strip_side_padding_10,
                    MR.strings.long_strip_side_padding_15,
                    MR.strings.long_strip_side_padding_20,
                    MR.strings.long_strip_side_padding_25,
                )
                entryValues = listOf(0, 5, 10, 15, 20, 25)
                defaultValue = "0"
            }

            intListPreference(activity) {
                key = Keys.webtoonPageLayout
                title = context.getString(MR.strings.page_layout)
                dialogTitleRes = MR.strings.page_layout
                val enumConstants = arrayOf(PageLayout.SINGLE_PAGE, PageLayout.SPLIT_PAGES)
                entriesRes = enumConstants.map { it.fullStringRes }.toTypedArray()
                entryValues = enumConstants.map { it.webtoonValue }
                defaultValue = PageLayout.SINGLE_PAGE.value
            }

            switchPreference {
                bindTo(preferences.webtoonInvertDoublePages())
                titleRes = MR.strings.invert_double_pages
            }

            switchPreference {
                bindTo(preferences.webtoonEnableZoomOut())
                titleRes = MR.strings.enable_zoom_out
            }

            switchPreference {
                bindTo(readerPreferences.webtoonDoubleTapZoomEnabled())
                titleRes = MR.strings.pref_double_tap_zoom
            }
        }
        preferenceCategory {
            titleRes = MR.strings.navigation

            switchPreference {
                key = Keys.readWithVolumeKeys
                titleRes = MR.strings.volume_keys
                defaultValue = false
            }
            switchPreference {
                key = Keys.readWithVolumeKeysInverted
                titleRes = MR.strings.invert_volume_keys
                defaultValue = false

                preferences.readWithVolumeKeys().changesIn(viewScope) { isVisible = it }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.actions

            switchPreference {
                key = Keys.readWithLongTap
                titleRes = MR.strings.show_on_long_press
                defaultValue = true
            }
            switchPreference {
                bindTo(preferences.folderPerManga())
                titleRes = MR.strings.save_pages_separately
                summaryRes = MR.strings.create_folders_by_manga_title
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != CODE_IMPORT_ENHANCEMENT_MODEL || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val context = activity ?: return
        if (isImportingEnhancementModel) return
        setEnhancementImportInProgress(true)

        viewScope.launchIO {
            try {
                val model = EnhancementModelStore.import(context, uri)
                readerPreferences.enhancementModel().set(model.nameWithoutExtension)
                EnhancementRuntime.invalidate()
                val models = EnhancementModelStore.models(context)
                withUIContext {
                    refreshEnhancementModels(models)
                    activity?.toast(MR.strings.enhancement_model_imported)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                withUIContext {
                    activity?.toast(MR.strings.enhancement_model_import_failed)
                }
            } finally {
                withUIContext { setEnhancementImportInProgress(false) }
            }
        }
    }

    private fun openEnhancementModelPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(intent, CODE_IMPORT_ENHANCEMENT_MODEL)
        } catch (_: ActivityNotFoundException) {
            activity?.toast(MR.strings.file_picker_error)
        }
    }

    private fun loadEnhancementModels() {
        val context = activity ?: return
        viewScope.launchIO {
            val models = EnhancementModelStore.models(context)
            withUIContext { refreshEnhancementModels(models) }
        }
    }

    private fun refreshEnhancementModels(models: List<InstalledEnhancementModel>) {
        val preference = enhancementModelPreference ?: return
        preference.entries = models.map { model ->
            if (model.isBundled) {
                preference.context.getString(MR.strings.enhancement_model_bundled)
            } else {
                model.displayName
            }
        }
        preference.entryValues = models.map { it.id }

        val selectedId = readerPreferences.enhancementModel().get()
        if (selectedId !in preference.entryValues) {
            models.firstOrNull()?.id?.let {
                readerPreferences.enhancementModel().set(it)
                EnhancementRuntime.invalidate()
            }
        }
        preference.preSummaryRes = null
    }

    private fun setEnhancementImportInProgress(importing: Boolean) {
        isImportingEnhancementModel = importing
        importEnhancementModelPreference?.apply {
            isEnabled = !importing
            title = context.getString(
                if (importing) MR.strings.enhancement_model_importing else MR.strings.import_enhancement_model,
            )
        }
    }
}

private const val CODE_IMPORT_ENHANCEMENT_MODEL = 506
