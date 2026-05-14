package com.scenarioexplorer.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * Reusable modern UI components.
 */
object UIComponents {

    /** Rounded card panel with subtle shadow-like border */
    fun card(content: JComponent? = null): RoundedPanel {
        return RoundedPanel(UIConstants.CARD_ARC).apply {
            background = UIConstants.cardBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.subtleBorder()),
                JBUI.Borders.empty(UIConstants.CARD_PADDING.top, UIConstants.CARD_PADDING.left,
                    UIConstants.CARD_PADDING.bottom, UIConstants.CARD_PADDING.right)
            )
            if (content != null) {
                layout = BorderLayout()
                add(content, BorderLayout.CENTER)
            }
        }
    }

    /** Stat card with large value, label, and accent color stripe */
    fun statCard(label: String, value: String, accentColor: Color, icon: String? = null): JPanel {
        return RoundedPanel(UIConstants.CARD_ARC).apply {
            layout = BorderLayout()
            background = UIConstants.cardBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.subtleBorder()),
                JBUI.Borders.empty(14, 16)
            )

            // Accent stripe on top
            val stripe = object : JPanel() {
                override fun getPreferredSize() = Dimension(0, 3)
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = accentColor
                    g2.fillRoundRect(0, 0, width, height, 4, 4)
                }
            }.apply { isOpaque = false }

            val centerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false

                val displayText = if (icon != null) "$icon $value" else value
                add(JBLabel(displayText).apply {
                    font = font.deriveFont(Font.BOLD, 22f)
                    foreground = accentColor
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                add(Box.createVerticalStrut(4))
                add(JBLabel(label).apply {
                    foreground = UIUtil.getLabelDisabledForeground()
                    font = font.deriveFont(11f)
                    alignmentX = Component.CENTER_ALIGNMENT
                })
            }

            add(stripe, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
        }
    }

    /** Section title with subtle underline */
    fun sectionTitle(text: String): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 0, 8, 0)

            add(JBLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 14f)
                foreground = UIUtil.getLabelForeground()
            }, BorderLayout.WEST)

            // Subtle line
            add(object : JPanel() {
                override fun getPreferredSize() = Dimension(0, 1)
                override fun paintComponent(g: Graphics) {
                    g.color = UIConstants.subtleBorder()
                    g.fillRect(0, height / 2, width, 1)
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(12)
            }, BorderLayout.CENTER)
        }
    }

    /** Modern gradient progress bar */
    fun progressBar(passRate: Double, ran: Int): JPanel {
        return JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 44)
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 8, 0)

            val bar = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    val arc = 10
                    val passW = (width * passRate / 100.0).toInt().coerceIn(0, width)
                    val failW = width - passW

                    // Clip to rounded rect for the whole bar
                    val clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat())
                    val oldClip = g2.clip
                    g2.clip = clip

                    // Fail portion (full background)
                    g2.color = UIConstants.RED.let { Color(it.red, it.green, it.blue, 140) }
                    g2.fillRect(0, 0, width, height)

                    // Pass portion (left side, gradient)
                    if (passW > 0) {
                        val gradient = GradientPaint(
                            0f, 0f, UIConstants.GREEN,
                            passW.toFloat(), 0f, UIConstants.GREEN_LIGHT
                        )
                        g2.paint = gradient
                        g2.fillRect(0, 0, passW, height)
                    }

                    g2.clip = oldClip
                }
            }.apply {
                preferredSize = Dimension(0, 24)
                isOpaque = false
            }

            val rateLabel = JBLabel("  %.1f%%  (%d koşuldu)".format(passRate, ran)).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = font.deriveFont(12f)
            }

            add(bar, BorderLayout.CENTER)
            add(rateLabel, BorderLayout.EAST)
        }
    }
}

/** JPanel with rounded corners */
open class RoundedPanel(private val arc: Int = 12) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        super.paintComponent(g)
    }
}

/** Rounded line border */
class RoundedBorder(private val arc: Int, private val color: Color) : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
    }

    override fun getBorderInsets(c: Component) = Insets(1, 1, 1, 1)
}
