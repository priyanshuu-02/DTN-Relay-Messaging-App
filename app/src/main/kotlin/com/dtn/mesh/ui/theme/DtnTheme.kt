package com.dtn.mesh.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours from docs/ui-redesign/visual-design.md §1. */
object DtnColors {
    val Background = Color(0xFFF4F7FC)
    val Card = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFE6ECF5)
    val Primary = Color(0xFF1769FF)
    val PrimarySoft = Color(0xFFE8F0FF)
    val TextTitle = Color(0xFF0F1B33)
    val TextSubtitle = Color(0xFF5B6B82)
    val TextHint = Color(0xFF94A3B8)
    val Green = Color(0xFF22A447)
    val GreenSoft = Color(0xFFE7F6EC)
    val Orange = Color(0xFFF59E0B)
    val OrangeSoft = Color(0xFFFFF4E0)
    val Red = Color(0xFFE5484D)
    val RedSoft = Color(0xFFFDECEC)
    val Purple = Color(0xFF7C5CFF)
    val PurpleSoft = Color(0xFFEFEAFF)
    val Grey = Color(0xFF9AA4B2)
    val GreySoft = Color(0xFFEEF1F5)
    /** Search pills, segmented-control tracks, unselected chips. */
    val Track = Color(0xFFEAF0F8)
    /** Console terminal panel. */
    val Terminal = Color(0xFF0F172A)
    /** Incoming chat bubble (light grey, a step darker than the background). */
    val BubbleIn = Color(0xFFE6EBF2)

    /** Darker text tones on the soft fills (≥ 4.5:1 for small text). */
    val GreenText = Color(0xFF15803D)
    val OrangeText = Color(0xFF9A5B00)
    val RedText = Color(0xFFC0262D)

    // Algorithm colours (tables, charts, chips).
    val AlgoProphet = Color(0xFF1769FF)
    val AlgoStable = Color(0xFF22A447)
    val AlgoMaxProp = Color(0xFFF59E0B)
    val AlgoDiMaxProp = Color(0xFF7C5CFF)
    val AlgoEpidemic = Color(0xFFE5484D)
    val AlgoQLearning = Color(0xFF0EA5A4)

    /** Colour of a routing algorithm by its StrategySelector name ("PROPHET", "QLEARNING", …). */
    fun algorithm(name: String): Color = when (name.uppercase().replace("-", "").replace("_", "")) {
        "PROPHET" -> AlgoProphet
        "STABLE", "STABLEPROPHET" -> AlgoStable
        "MAXPROP" -> AlgoMaxProp
        "DIMAXPROP" -> AlgoDiMaxProp
        "EPIDEMIC" -> AlgoEpidemic
        "QLEARNING" -> AlgoQLearning
        else -> Grey
    }

    // Avatars: a fixed colour per contact, picked from its node id. Groups use lavender.
    private val avatars = listOf(
        Color(0xFF1769FF), Color(0xFF22A447), Color(0xFFE5539A),
        Color(0xFF7C5CFF), Color(0xFF38A3F1), Color(0xFF0EA5A4),
    )
    val GroupAvatar = Color(0xFFB9A8FF)

    fun avatarFor(nodeId: String): Color = avatars[Math.floorMod(nodeId.hashCode(), avatars.size)]
}

/** Text styles from visual-design.md §1. */
object DtnType {
    val AppTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextTitle)
    val PageTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
    val PageSubtitle = TextStyle(fontSize = 13.sp, color = DtnColors.TextSubtitle)
    val SectionHeader = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.Primary, letterSpacing = 1.2.sp,
    )
    val RowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
    val RowSubtitle = TextStyle(fontSize = 13.sp, color = DtnColors.TextSubtitle)
    val Meta = TextStyle(fontSize = 12.sp, color = DtnColors.TextSubtitle)
    val Mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DtnColors.TextTitle)
}

/** Corners from visual-design.md §1. */
object DtnShapes {
    val Card = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(12.dp)
    val Sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val Pill = CircleShape
    val IconSquare = RoundedCornerShape(12.dp)
}

/** Spacing from visual-design.md §1. */
object DtnSpacing {
    val Page = 16.dp
    val CardPadding = 16.dp
    val BetweenCards = 12.dp
    val RowMinHeight = 64.dp
    val MainButtonHeight = 52.dp
    val Icon = 24.dp
    val IconSquare = 40.dp
    val StatusDot = 8.dp
    val MinTouch = 48.dp
}

private val LightColors = lightColorScheme(
    primary = DtnColors.Primary,
    onPrimary = Color.White,
    primaryContainer = DtnColors.PrimarySoft,
    onPrimaryContainer = DtnColors.TextTitle,
    secondary = DtnColors.Purple,
    onSecondary = Color.White,
    secondaryContainer = DtnColors.PurpleSoft,
    onSecondaryContainer = DtnColors.Purple,
    tertiary = DtnColors.Green,
    background = DtnColors.Background,
    onBackground = DtnColors.TextTitle,
    surface = DtnColors.Card,
    onSurface = DtnColors.TextTitle,
    surfaceVariant = DtnColors.Track,
    onSurfaceVariant = DtnColors.TextSubtitle,
    surfaceContainerLow = DtnColors.Card,
    surfaceContainer = DtnColors.Card,
    surfaceContainerHigh = DtnColors.Card,
    surfaceContainerHighest = DtnColors.GreySoft,
    outline = DtnColors.CardBorder,
    outlineVariant = DtnColors.CardBorder,
    error = DtnColors.Red,
    onError = Color.White,
    errorContainer = DtnColors.RedSoft,
)

private val DtnTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp),
    )
}

private val DtnMaterialShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** The app theme: light, flat cards with thin borders, blue primary. */
@Composable
fun DtnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = DtnTypography,
        shapes = DtnMaterialShapes,
        content = content,
    )
}
