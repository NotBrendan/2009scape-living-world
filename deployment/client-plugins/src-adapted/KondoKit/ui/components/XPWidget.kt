package KondoKit.ui.components

import java.awt.Container
import javax.swing.JLabel

/**
 * Shared widget state used by XP-related features so the structure isn't duplicated.
 */
data class XPWidget(
    val container: Container,
    val skillId: Int,
    val xpGainedLabel: JLabel,
    val xpLeftLabel: JLabel,
    val xpPerHourLabel: JLabel,
    val actionsRemainingLabel: JLabel,
    val progressBar: ProgressBar,
    var totalXpGained: Int = 0,
    var startTime: Long = System.currentTimeMillis(),
    var previousXp: Int = 0
)
