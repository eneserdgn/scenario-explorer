package com.scenarioexplorer.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.JToggleButton

/** iOS-style toggle switch. Knob on left = OFF, knob on right = ON. */
class ToggleSwitchButton(private val label: String, initiallySelected: Boolean = false) : JToggleButton() {
    companion object {
        private const val TW = 26; private const val TH = 13; private const val KD = 11
    }
    init {
        isSelected = initiallySelected
        isOpaque = false; isFocusPainted = false
        isBorderPainted = false; isContentAreaFilled = false
        font = font.deriveFont(10f)
        border = JBUI.Borders.empty(2, TW + 6, 2, 6)
    }
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val ty = (height - TH) / 2
        g2.color = if (isSelected) Color(0x4C, 0xAF, 0x50) else Color(160, 160, 160)
        g2.fillRoundRect(2, ty, TW, TH, TH, TH)
        g2.color = Color.WHITE
        val kx = if (isSelected) 2 + TW - KD - 1 else 3
        val ky = ty + (TH - KD) / 2
        g2.fillOval(kx, ky, KD, KD)
        g2.color = if (isEnabled) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
        val fm = g2.fontMetrics
        g2.drawString(label, TW + 8, (height + fm.ascent - fm.descent) / 2)
        g2.dispose()
    }
    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(TW + 8 + fm.stringWidth(label) + 8, 22)
    }
    override fun getMinimumSize() = preferredSize
    override fun getMaximumSize() = preferredSize
}
