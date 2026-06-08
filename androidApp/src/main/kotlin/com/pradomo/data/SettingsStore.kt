package com.pradomo.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pradomo.control.ButtonAction
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

    val deckHeightMm: Flow<Int> = context.dataStore.data.map { it[keyDeck] ?: DEFAULT_DECK_MM }
    val topButton: Flow<ButtonAction> = context.dataStore.data.map { it.action(keyTop, ButtonAction.SLOW) }
    val bottomButton: Flow<ButtonAction> = context.dataStore.data.map { it.action(keyBottom, ButtonAction.TURBO) }
    val smoothEnabled: Flow<Boolean> = context.dataStore.data.map { it[keySmooth] ?: false }
    val smoothLevel: Flow<SmoothLevel> = context.dataStore.data.map { prefs ->
        prefs[keySmoothLevel]?.let { runCatching { SmoothLevel.valueOf(it) }.getOrNull() } ?: SmoothLevel.MEDIUM
    }

    suspend fun setDeckHeightMm(mm: Int) = context.dataStore.edit { it[keyDeck] = mm }
    suspend fun setTopButton(a: ButtonAction) = context.dataStore.edit { it[keyTop] = a.name }
    suspend fun setBottomButton(a: ButtonAction) = context.dataStore.edit { it[keyBottom] = a.name }
    suspend fun setSmoothEnabled(on: Boolean) = context.dataStore.edit { it[keySmooth] = on }
    suspend fun setSmoothLevel(l: SmoothLevel) = context.dataStore.edit { it[keySmoothLevel] = l.name }

    private fun androidx.datastore.preferences.core.Preferences.action(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        default: ButtonAction,
    ): ButtonAction = this[key]?.let { runCatching { ButtonAction.valueOf(it) }.getOrNull() } ?: default

    companion object {
        const val DEFAULT_DECK_MM = 50
        // Physical controller keycodes: top (smaller) = BUTTON_B, bottom (larger) = BUTTON_A.
        const val KEYCODE_TOP = 97
        const val KEYCODE_BOTTOM = 96
    }
}
