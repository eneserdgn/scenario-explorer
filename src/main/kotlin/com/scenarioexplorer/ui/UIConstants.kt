package com.scenarioexplorer.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * Shared color palette and UI helpers for a polished, modern look.
 */
object UIConstants {
    // Status colors
    val GREEN = Color(0x4C, 0xAF, 0x50)
    val GREEN_LIGHT = Color(0x81, 0xC7, 0x84)
    val RED = Color(0xEF, 0x53, 0x50)
    val RED_LIGHT = Color(0xE5, 0x73, 0x73)
    val YELLOW = Color(0xFF, 0xB3, 0x00)
    val BLUE = Color(0x42, 0xA5, 0xF5)
    val GRAY = Color(0x9E, 0x9E, 0x9E)

    // Card / surface
    val CARD_ARC = 12
    val CARD_PADDING = JBUI.insets(12, 16)

    fun cardBackground(): Color {
        val base = UIUtil.getPanelBackground()
        return if (isDark()) base.brighter(12) else base.darker(6)
    }

    fun surfaceBackground(): Color = UIUtil.getPanelBackground()

    fun subtleBorder(): Color {
        return if (isDark()) Color(255, 255, 255, 30) else Color(0, 0, 0, 20)
    }

    fun isDark(): Boolean {
        val bg = UIUtil.getPanelBackground()
        val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        return luminance < 128
    }

    fun statusColor(status: String?): Color = when (status?.uppercase()) {
        "PASSED" -> GREEN
        "FAILED" -> RED
        else -> GRAY
    }

    // Helpers
    private fun Color.brighter(amount: Int): Color {
        return Color(
            (red + amount).coerceIn(0, 255),
            (green + amount).coerceIn(0, 255),
            (blue + amount).coerceIn(0, 255),
            alpha
        )
    }

    private fun Color.darker(amount: Int): Color {
        return Color(
            (red - amount).coerceIn(0, 255),
            (green - amount).coerceIn(0, 255),
            (blue - amount).coerceIn(0, 255),
            alpha
        )
    }
}
