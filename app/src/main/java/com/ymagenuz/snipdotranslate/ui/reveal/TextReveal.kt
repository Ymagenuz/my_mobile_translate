package com.ymagenuz.snipdotranslate.ui.reveal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownExtendedSpans
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import kotlinx.coroutines.launch
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType

/** Whether text that arrives now is being streamed in, and so fades in. */
val LocalTextReveal = compositionLocalOf { false }

/**
 * The renderer's own components, except that streamed text fades in as on the desktop:
 * a new paragraph or heading fades in as a whole and its characters follow one after
 * another; list markers, quotes, code and tables fade in as they appear.
 */
@Composable
fun rememberRevealComponents(): MarkdownComponents = remember {
    markdownComponents(
        paragraph = { RevealingText(it, it.typography.paragraph) },
        heading1 = { RevealingHeading(it, it.typography.h1, MarkdownTokenTypes.ATX_CONTENT) },
        heading2 = { RevealingHeading(it, it.typography.h2, MarkdownTokenTypes.ATX_CONTENT) },
        heading3 = { RevealingHeading(it, it.typography.h3, MarkdownTokenTypes.ATX_CONTENT) },
        heading4 = { RevealingHeading(it, it.typography.h4, MarkdownTokenTypes.ATX_CONTENT) },
        heading5 = { RevealingHeading(it, it.typography.h5, MarkdownTokenTypes.ATX_CONTENT) },
        heading6 = { RevealingHeading(it, it.typography.h6, MarkdownTokenTypes.ATX_CONTENT) },
        setextHeading1 = { RevealingHeading(it, it.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
        setextHeading2 = { RevealingHeading(it, it.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
        unorderedList = {
            val live = LocalTextReveal.current
            MarkdownBulletList(it.content, it.node, style = it.typography.bullet, depth = it.listDepth, markerModifier = { Modifier.fadeInOnEnter(live) })
        },
        orderedList = {
            val live = LocalTextReveal.current
            MarkdownOrderedList(it.content, it.node, style = it.typography.ordered, depth = it.listDepth, markerModifier = { Modifier.fadeInOnEnter(live) })
        },
        blockQuote = { FadeInBox { MarkdownBlockQuote(it.content, it.node, style = it.typography.quote) } },
        codeFence = { FadeInBox { MarkdownCodeFence(it.content, it.node, style = it.typography.code) } },
        codeBlock = { FadeInBox { MarkdownCodeBlock(it.content, it.node, style = it.typography.code) } },
        table = { FadeInBox { MarkdownTable(it.content, it.node, style = it.typography.table) } },
    )
}

@Composable
private fun RevealingHeading(model: MarkdownComponentModel, style: TextStyle, contentType: IElementType) {
    RevealingText(model, style, Modifier.semantics { heading() }, contentType)
}

/** Renders text as the renderer does, with what just arrived fading in. */
@Composable
private fun RevealingText(
    model: MarkdownComponentModel,
    style: TextStyle,
    modifier: Modifier = Modifier,
    contentType: IElementType? = null,
) {
    val settings = annotatorSettings()
    val textNode = contentType?.let { model.node.findChildOfType(it) } ?: model.node
    val styled = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(model.content, textNode, settings)
        pop()
    }

    val timeline = remember { RevealTimeline() }
    timeline.note(styled.length, System.nanoTime(), LocalTextReveal.current)
    var frame by remember { mutableLongStateOf(System.nanoTime()) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(timeline, styled.length) {
        do {
            val now = withFrameNanos { it }
            timeline.prune(now)
            frame = now
        } while (timeline.isActive(now))
    }

    MarkdownText(
        content = styled,
        node = model.node,
        modifier = modifier
            .graphicsLayer {
                val now = frame
                alpha = timeline.blockAlpha(now)
                // Fading characters are cut out of the text's own layer, so whatever is
                // behind shows through and colours of links and code are left alone.
                compositingStrategy = if (timeline.hasVeils(now)) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                val result = layout ?: return@drawWithContent
                val length = result.layoutInput.text.length
                timeline.forEachVeiled(frame) { index, veil ->
                    if (index < length) {
                        val box = result.getBoundingBox(index)
                        drawRect(Color.Black, box.topLeft, box.size, alpha = veil, blendMode = BlendMode.DstOut)
                    }
                }
            },
        style = style,
        onTextLayout = { result, _ -> layout = result },
        sourceContent = model.content,
        extendedSpans = LocalMarkdownExtendedSpans.current.extendedSpans?.invoke(),
    )
}

@Composable
private fun FadeInBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().fadeInOnEnter(LocalTextReveal.current)) { content() }
}

/**
 * Fades in over [REVEAL_BLOCK_MS] when it first appears, if text is streaming in ([live])
 * at that moment. A later change of [live] doesn't replay it.
 */
fun Modifier.fadeInOnEnter(live: Boolean): Modifier = this then FadeInOnEnterElement(live)

private data class FadeInOnEnterElement(val live: Boolean) : ModifierNodeElement<FadeInOnEnterNode>() {
    override fun create() = FadeInOnEnterNode(live)

    override fun update(node: FadeInOnEnterNode) {
        node.live = live
    }
}

private class FadeInOnEnterNode(var live: Boolean) : Modifier.Node(), DrawModifierNode {
    private var alpha = 1f
    private val paint = Paint()

    override fun onAttach() {
        if (!live) return
        val start = System.nanoTime()
        alpha = 0f
        coroutineScope.launch {
            while (alpha < 1f) {
                alpha = blockFadeProgress(start, withFrameNanos { it })
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (alpha >= 1f) {
            drawContent()
            return
        }
        paint.alpha = alpha
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}
