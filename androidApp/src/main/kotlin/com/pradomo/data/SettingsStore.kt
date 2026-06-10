package com.pradomo.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pradomo.control.ButtonAction
import com.pradomo.control.ControllerModeGroup
import com.pradomo.control.SmoothLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Persisted user preferences (deck height, controller button mapping, controlled
 * deceleration). Blade speed is deliberately NOT persisted — it always starts Off
 * for safety. The mower doesn't report its current deck height over BLE, so we
 * remember the last value the user set and show that on startup.
 */
class SettingsStore(private val context: Context) {
    private val keyDeck = intPreferencesKey("deck_height_mm")
    private val keyTop = stringPreferencesKey("btn_top_action")
    private val keyBottom = stringPreferencesKey("btn_bottom_action")
    private val keySmooth = booleanPreferencesKey("smooth_enabled")
    private val keySmoothLevel = stringPreferencesKey("smooth_level")
    private val keyCuttingWidth = intPreferencesKey("cutting_width_mm")
    private val keyRowOverlap = intPreferencesKey("row_overlap_mm")
    private val keyModeGroup = stringPreferencesKey("controller_mode_group")

    val deckHeightMm: Flow<Int> = context.dataStore.data.map { it[keyDeck] ?: DEFAULT_DECK_MM }
    val topButton: Flow<ButtonAction> = context.dataStore.data.map { it.action(keyTop, ButtonAction.SLOW) }
    val bottomButton: Flow<ButtonAction> = context.dataStore.data.map { it.action(keyBottom, ButtonAction.TURBO) }
    val smoothEnabled: Flow<Boolean> = context.dataStore.data.map { it[keySmooth] ?: false }
    val smoothLevel: Flow<SmoothLevel> = context.dataStore.data.map { prefs ->
        prefs[keySmoothLevel]?.let { runCatching { SmoothLevel.valueOf(it) }.getOrNull() } ?: SmoothLevel.MEDIUM
    }

    /** Mower cutting width (mm) — used with overlap to place auto-turns one row over. */
    val cuttingWidthMm: Flow<Int> = context.dataStore.data.map { it[keyCuttingWidth] ?: DEFAULT_CUTTING_WIDTH_MM }
    /** How much each row overlaps the last (mm); row pitch = cutting width − overlap. */
    val rowOverlapMm: Flow<Int> = context.dataStore.data.map { it[keyRowOverlap] ?: DEFAULT_ROW_OVERLAP_MM }
    val modeGroup: Flow<ControllerModeGroup> = context.dataStore.data.map { prefs ->
        prefs[keyModeGroup]?.let { runCatching { ControllerModeGroup.valueOf(it) }.getOrNull() }
            ?: ControllerModeGroup.SPEED
    }

    suspend fun setDeckHeightMm(mm: Int) = context.dataStore.edit { it[keyDeck] = mm }
    suspend fun setTopButton(a: ButtonAction) = context.dataStore.edit { it[keyTop] = a.name }
    suspend fun setBottomButton(a: ButtonAction) = context.dataStore.edit { it[keyBottom] = a.name }
    suspend fun setSmoothEnabled(on: Boolean) = context.dataStore.edit { it[keySmooth] = on }
    suspend fun setSmoothLevel(l: SmoothLevel) = context.dataStore.edit { it[keySmoothLevel] = l.name }
    suspend fun setCuttingWidthMm(mm: Int) = context.dataStore.edit { it[keyCuttingWidth] = mm }
    suspend fun setRowOverlapMm(mm: Int) = context.dataStore.edit { it[keyRowOverlap] = mm }
    suspend fun setModeGroup(g: ControllerModeGroup) = context.dataStore.edit { it[keyModeGroup] = g.name }

    private fun androidx.datastore.preferences.core.Preferences.action(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        default: ButtonAction,
    ): ButtonAction = this[key]?.let { runCatching { ButtonAction.valueOf(it) }.getOrNull() } ?: default

    companion object {
        const val DEFAULT_DECK_MM = 50
        // Placeholder cutting width — MUST be set to the real mower's value for gap-free
        // rows. The Lymow's true cutting width has not been measured yet.
        const val DEFAULT_CUTTING_WIDTH_MM = 200
        const val DEFAULT_ROW_OVERLAP_MM = 20
        // Physical controller keycodes: top (smaller) = BUTTON_B, bottom (larger) = BUTTON_A.
        const val KEYCODE_TOP = 97
        const val KEYCODE_BOTTOM = 96
    }
}
