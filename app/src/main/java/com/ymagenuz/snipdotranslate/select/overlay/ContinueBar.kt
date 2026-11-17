package com.ymagenuz.snipdotranslate.select.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.ui.SnipDoTheme

private val COMPACT_BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/**
 * The small bar over the live screen while a selection continues: the user scrolls the app
 * below as usual, then captures the next screen. Only the bar itself takes touches. [parts] is
 * how many screens have given words so far. [onTouchElsewhere] hears of each touch on the app
 * below, though not where it was, when the bar's window watches outside touches.
 */
class ContinueBar(
    context: Context,
    private val parts: Int,
    private val attach: (View) -> Unit,
    private val detach: (View) -> Unit,
    private val onCapture: () -> Unit,
    private val onTranslate: () -> Unit,
    private val onDiscard: () -> Unit,
    private val onTouchElsewhere: () -> Unit,
) {
    private val owner = ComposeWindowOwner()
    private val view = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onTouchElsewhere()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(ComposeView(context).apply { setContent { SnipDoTheme { Bar() } } }, wrap, wrap)
    }
    private var shown = false

    fun show() {
        owner.attachTo(view)
        attach(view)
        shown = true
    }

    fun remove() {
        if (!shown) return
        shown = false
        detach(view)
        owner.destroy()
    }

    @Composable
    private fun Bar() {
        Surface(
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
            // Room for the shadow, since the window is only as big as what's in it.
            modifier = Modifier.padding(8.dp),
        ) {
            // As wide as its wider row, so the buttons line up at the right edge.
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        pluralStringResource(R.plurals.continue_parts_kept, parts, parts),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    // Gives way first when the screen is narrow or the text is large.
                    Text(
                        " · " + stringResource(R.string.continue_bar_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val discard = stringResource(R.string.continue_discard)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = discard }
                            .clickable(onClick = onDiscard),
                    ) { Text("✕", fontSize = 18.sp) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onTranslate, contentPadding = COMPACT_BUTTON_PADDING) {
                        Text(stringResource(R.string.action_translate))
                    }
                    Button(onClick = onCapture, contentPadding = COMPACT_BUTTON_PADDING) {
                        Text(stringResource(R.string.action_capture))
                    }
                }
            }
        }
    }
}
