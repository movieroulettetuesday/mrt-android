package com.movieroulettetuesday.app.roulette

/**
 * Which control does what (mrt-shared flows.json `spinControls`, iOS RouletteView): only the hub
 * (and the screen-level accessibility action, Android's stand-in for VoiceOver magic tap) can stop
 * a spin; tapping or activating the wheel face only ever starts one.
 */
object SpinControls {
    enum class Input { HubButton, WheelFaceTap, MagicTap, AccessibilityActivate }
    enum class Action { Spin, StopEarly, Nothing }

    fun resolve(input: Input, canSpin: Boolean, canStop: Boolean): Action = when {
        canSpin -> Action.Spin
        canStop && (input == Input.HubButton || input == Input.MagicTap) -> Action.StopEarly
        else -> Action.Nothing
    }
}
