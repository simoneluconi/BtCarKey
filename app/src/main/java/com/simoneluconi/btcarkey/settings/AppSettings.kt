package com.simoneluconi.btcarkey.settings

import android.content.Context
import androidx.core.content.edit
import com.simoneluconi.btcarkey.protocol.RemoteButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Accent colors the driver can pick; values are (day, night). */
enum class Accent(val day: Long, val night: Long) {
    BLUE(0xFF0B63CE, 0xFF5EA8FF),
    TEAL(0xFF00796B, 0xFF4DD0C1),
    GREEN(0xFF2E7D32, 0xFF7BD389),
    AMBER(0xFFB26A00, 0xFFFFB74D),
    RED(0xFFC62828, 0xFFFF7A7A),
    PURPLE(0xFF6A3FC8, 0xFFB79CFF),
    GRAPHITE(0xFF37474F, 0xFFB0BEC5),
}

/** What a home-screen button does. */
enum class HomeAction {
    UNLOCK, LOCK, TRUNK, REMOTE_START, DROP_WINDOWS,
    /** The remote's buttons held down (long press), released when the finger lifts. */
    HOLD_UNLOCK, HOLD_LOCK, HOLD_TRUNK, HOLD_FUNCTION,
}

/**
 * How a one-tap action reaches the car. Cars differ: one opens the trunk with the box's trunk
 * command, another wants the remote's trunk button held for two seconds or pressed twice.
 * - [Method.BOX]: the box's own command for the action;
 * - [Method.REMOTE]: the box presses [button] [presses] times, each held [holdMs].
 */
data class ActionConfig(
    val method: Method = Method.BOX,
    val button: RemoteButton = RemoteButton.UNLOCK,
    val presses: Int = 1,
    val holdMs: Int = SHORT_PRESS_MS,
) {
    enum class Method { BOX, REMOTE }

    fun encode(): String = "${method.name}:${button.name}:$presses:$holdMs"

    companion object {
        /** A click of the remote. */
        const val SHORT_PRESS_MS = 200
        const val MAX_PRESSES = 3
        val HOLD_RANGE_MS = 200..6000

        fun decode(text: String): ActionConfig? {
            val parts = text.split(":")
            if (parts.size != 4) return null
            return ActionConfig(
                method = Method.entries.firstOrNull { it.name == parts[0] } ?: return null,
                button = RemoteButton.entries.firstOrNull { it.name == parts[1] } ?: return null,
                presses = parts[2].toIntOrNull()?.coerceIn(1, MAX_PRESSES) ?: return null,
                holdMs = parts[3].toIntOrNull()?.coerceIn(HOLD_RANGE_MS.first, HOLD_RANGE_MS.last) ?: return null,
            )
        }

        /** Sensible remote button for each action, offered when the driver switches to REMOTE. */
        fun remoteDefault(action: HomeAction): ActionConfig = when (action) {
            HomeAction.LOCK -> ActionConfig(Method.REMOTE, RemoteButton.LOCK)
            HomeAction.TRUNK -> ActionConfig(Method.REMOTE, RemoteButton.TRUNK, holdMs = 2000)
            HomeAction.DROP_WINDOWS -> ActionConfig(Method.REMOTE, RemoteButton.UNLOCK, holdMs = 3000)
            HomeAction.REMOTE_START -> ActionConfig(Method.REMOTE, RemoteButton.LOCK, presses = 3)
            else -> ActionConfig(Method.REMOTE, RemoteButton.UNLOCK)
        }
    }
}

/**
 * The driver's preferences, persisted, as StateFlows the UI follows. Per-box data (names, keys)
 * lives in DeviceStore instead.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOr(prefs.getString(THEME, null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _accent = MutableStateFlow(enumOr(prefs.getString(ACCENT, null), Accent.BLUE))
    val accent: StateFlow<Accent> = _accent.asStateFlow()

    private val _homeActions = MutableStateFlow(loadActions())
    /** Buttons on the home screen, in order. */
    val homeActions: StateFlow<List<HomeAction>> = _homeActions.asStateFlow()

    private val _confirm = MutableStateFlow(loadConfirm())
    /** Actions that need a long press, so a pocket tap can't start the engine. */
    val confirm: StateFlow<Set<HomeAction>> = _confirm.asStateFlow()

    private val _actionConfigs = MutableStateFlow(loadActionConfigs())
    /** How each one-tap action is sent; missing = the box's own command. */
    val actionConfigs: StateFlow<Map<HomeAction, ActionConfig>> = _actionConfigs.asStateFlow()

    private val _haptics = MutableStateFlow(prefs.getBoolean(HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _keepConnected = MutableStateFlow(prefs.getBoolean(KEEP_CONNECTED, false))
    /** Stay connected with the app closed (needed by comfort entry); a notification shows it. */
    val keepConnected: StateFlow<Boolean> = _keepConnected.asStateFlow()

    fun setTheme(value: ThemeMode) {
        prefs.edit { putString(THEME, value.name) }
        _theme.value = value
    }

    fun setAccent(value: Accent) {
        prefs.edit { putString(ACCENT, value.name) }
        _accent.value = value
    }

    fun setHomeActions(value: List<HomeAction>) {
        prefs.edit { putString(HOME_ACTIONS, value.joinToString(",") { it.name }) }
        _homeActions.value = value
    }

    fun setConfirm(action: HomeAction, required: Boolean) {
        val next = if (required) _confirm.value + action else _confirm.value - action
        prefs.edit { putString(CONFIRM, next.joinToString(",") { it.name }) }
        _confirm.value = next
    }

    fun actionConfig(action: HomeAction): ActionConfig = _actionConfigs.value[action] ?: ActionConfig()

    fun setActionConfig(action: HomeAction, config: ActionConfig) {
        prefs.edit { putString(ACTION_CONFIG + action.name, config.encode()) }
        _actionConfigs.value = _actionConfigs.value + (action to config)
    }

    fun setHaptics(value: Boolean) {
        prefs.edit { putBoolean(HAPTICS, value) }
        _haptics.value = value
    }

    fun setKeepConnected(value: Boolean) {
        prefs.edit { putBoolean(KEEP_CONNECTED, value) }
        _keepConnected.value = value
    }

    private fun loadActions(): List<HomeAction> {
        val stored = prefs.getString(HOME_ACTIONS, null) ?: return DEFAULT_ACTIONS
        return stored.split(",").mapNotNull { name -> HomeAction.entries.firstOrNull { it.name == name } }
    }

    private fun loadConfirm(): Set<HomeAction> {
        val stored = prefs.getString(CONFIRM, null) ?: return DEFAULT_CONFIRM
        return stored.split(",").mapNotNull { name -> HomeAction.entries.firstOrNull { it.name == name } }.toSet()
    }

    private fun loadActionConfigs(): Map<HomeAction, ActionConfig> = HomeAction.entries.mapNotNull { action ->
        prefs.getString(ACTION_CONFIG + action.name, null)?.let(ActionConfig::decode)?.let { action to it }
    }.toMap()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val THEME = "theme"
        private const val ACCENT = "accent"
        private const val HOME_ACTIONS = "home_actions"
        private const val CONFIRM = "confirm"
        private const val HAPTICS = "haptics"
        private const val KEEP_CONNECTED = "keep_connected"
        private const val ACTION_CONFIG = "action_config_"

        val DEFAULT_ACTIONS = listOf(HomeAction.UNLOCK, HomeAction.LOCK, HomeAction.TRUNK, HomeAction.DROP_WINDOWS, HomeAction.REMOTE_START)
        val DEFAULT_CONFIRM = setOf(HomeAction.REMOTE_START, HomeAction.DROP_WINDOWS)
    }
}
