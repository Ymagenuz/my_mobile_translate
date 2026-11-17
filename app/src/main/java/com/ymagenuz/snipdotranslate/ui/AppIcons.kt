package com.ymagenuz.snipdotranslate.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private const val BRACKETS = "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2"
private const val RING = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0"
private const val CLIPBOARD = "M9 4.5H7a2 2 0 0 0-2 2V19a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V6.5a2 2 0 0 0-2-2h-2M9 3h6v3.5H9z"
private const val SPEAKER_BODY = "M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z"

/**
 * The app's icons: one family of 24 dp outlines with rounded 1.8 dp strokes. They are drawn here
 * instead of written as text glyphs, which each phone maker's font draws differently.
 */
object AppIcons {
    val Back = icon("M19 12H5M11 6l-6 6 6 6")
    val Close = icon("M6 6l12 12M18 6L6 18")
    val Check = icon("M5 12.5l4.5 4.5L19 7.5")
    val Plus = icon("M12 5v14M5 12h14")
    val ChevronDown = icon("M6 9l6 6 6-6")
    val ChevronRight = icon("M9 6l6 6-6 6")
    val ArrowRight = icon("M5 12h14M13 6l6 6-6 6")
    val ArrowDown = icon("M12 4v16M6 14l6 6 6-6")

    val History = icon("M3.5 12a8.5 8.5 0 1 0 2.6-6.1L3.5 8.4M3.5 4v4.4h4.4M12 7.5V12l3 1.8")
    val Settings = icon(
        "M10.3 4.9L10.66 2.49L13.34 2.49L13.7 4.9L15.81 5.78L17.78 4.33L19.67 6.22L18.22 8.19L19.1 10.3L21.51 10.66" +
            "L21.51 13.34L19.1 13.7L18.22 15.81L19.67 17.78L17.78 19.67L15.81 18.22L13.7 19.1L13.34 21.51L10.66 21.51" +
            "L10.3 19.1L8.19 18.22L6.22 19.67L4.33 17.78L5.78 15.81L4.9 13.7L2.49 13.34L2.49 10.66L4.9 10.3L5.78 8.19" +
            "L4.33 6.22L6.22 4.33L8.19 5.78zM9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    )
    val Search = icon("M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0M15.5 15.5L20 20")
    val Trash = icon("M4 7h16M9 7V4.5h6V7M6.5 7l1 12.5a1 1 0 0 0 1 .5h7a1 1 0 0 0 1-.5l1-12.5M10 11v5.5M14 11v5.5")
    val Info = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 11v5.5M12 7.5h.01")
    val Globe = icon(
        "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M3 12h18" +
            "M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9s1.3-6.4 3.8-9z",
    )
    val Download = icon("M12 4v11M7 10.5l5 5 5-5M5 20h14")
    val Key = icon("M3 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0M11 12h10M17 12v3.5M20.5 12v2.5")
    val Eye = icon("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0")
    val EyeOff = icon(
        "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M4 4l16 16",
    )

    val Translate = icon("M3 6h10M8 3.5V6M11 6c-.6 3.4-3.3 6.6-7.5 8.5M5.5 9c1 2.4 3.2 4.4 6 5.4M13 20l4-9 4 9M14.4 17h5.2")
    val Swap = icon("M4 8h14M14 4l4 4-4 4M20 16H6M10 12l-4 4 4 4")
    val Paste = icon(CLIPBOARD + "M9 11.5h6M9 15.5h4")
    val Copy = icon(
        "M10 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z" +
            "M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3",
    )
    val Share = icon(
        "M15 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M4 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0" +
            "M15 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M8.7 10.7l6.6-3.9M8.7 13.3l6.6 3.9",
    )
    val Book = icon("M12 6.5c-1.8-1.3-4.3-2-8-2v13c3.7 0 6.2.7 8 2 1.8-1.3 4.3-2 8-2v-13c-3.7 0-6.2.7-8 2zM12 6.5v13")
    val BackTranslate = icon("M4 10V9a3 3 0 0 1 3-3h12M16 3l3 3-3 3M20 14v1a3 3 0 0 1-3 3H5M8 21l-3-3 3-3")
    val Edit = icon("M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4")
    val OpenInApp = icon("M14 4h6v6M20 4l-8 8M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4")
    val Stop = icon("M7.5 6h9a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 16.5v-9A1.5 1.5 0 0 1 7.5 6z")
    val Retry = icon("M20 12a8 8 0 1 1-2.4-5.7L20 8.6M20 4v4.6h-4.6")

    val Camera = icon(
        "M4 8h3l1.5-2.5h7L17 8h3a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z" +
            "M8.5 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0",
    )
    val Image = icon(
        "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M7 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M21 15.5l-4.5-4.5L6 20",
    )
    val Mic = icon("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3")
    val Speaker = icon(SPEAKER_BODY + "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11")
    val SpeakerOff = icon(SPEAKER_BODY + "M16 9.5l5 5M21 9.5l-5 5")
    val ScreenSelect = icon(BRACKETS + "M8 10h8M8 14h5")
    val Capture = icon(BRACKETS + "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0")

    // The selection toolbar.
    val PenText = icon("M15.5 4l4.5 4.5-9 9L6.5 13zM6.5 13L4 17.5 6.5 20l4.5-2.5M13.5 20H20")
    val PenPlus = icon(RING + "M12 8v8M8 12h8")
    val PenMinus = icon(RING + "M8 12h8")
    val PenLasso = icon("M12 4c-4.7 0-8.5 2.7-8.5 6s3.8 6 8.5 6 8.5-2.7 8.5-6-3.8-6-8.5-6zM7 15.2c-1 2-.3 4.2 2 5.3")
    val PenParagraph = icon("M17 4H9.5a4 4 0 0 0 0 8H12M12 4v16M16 4v16")
    val PenArea = icon("M4 8V5h3M17 5h3v3M20 16v3h-3M7 19H4v-3M12 8.5l1.1 2.4 2.4 1.1-2.4 1.1L12 15.5l-1.1-2.4L8.5 12l2.4-1.1z")
    val Sparkles = icon(
        "M10 4l1.6 4.4L16 10l-4.4 1.6L10 16l-1.6-4.4L4 10l4.4-1.6z" +
            "M18 14l0.8 2.2L21 17l-2.2 0.8L18 20l-0.8-2.2L15 17l2.2-0.8z",
    )
    val Undo = icon("M9 14L4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Redo = icon("M15 14l5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val SelectAll = icon(BRACKETS + "M9 9h6v6H9z")
    val ClearSelection = icon("M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7 6-7zM12 9.5l5 5M17 9.5l-5 5")
    val OrderReading = icon("M4 6h16M4 12h16M4 18h10")
    val OrderPicked = icon("M7 4v16M4 17l3 3 3-3M17 20V4M14 7l3-3 3 3")
    val Collapse = icon("M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7")
    val Expand = icon("M14 4h6v6M20 4l-7 7M10 20H4v-6M4 20l7-7")

    /** The app's mark: the source going out in blue, the translation coming back in teal. */
    val Brand: ImageVector = ImageVector.Builder(
        defaultWidth = 34.dp,
        defaultHeight = 32.dp,
        viewportWidth = 64f,
        viewportHeight = 60f,
    )
        .addPath(addPathNodes("M12 10H38V4L61 18L38 32V26H12a8 8 0 0 1 0-16z"), fill = SolidColor(Color(0xFF2F6FED)))
        .addPath(addPathNodes("M52 50H26V56L3 42L26 28V34H52a8 8 0 0 1 0 16z"), fill = SolidColor(Color(0xFF12B5A4)))
        .build()
}

private fun icon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(path),
            // Replaced by the tint wherever an icon is shown.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
