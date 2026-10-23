package com.pianoscribe.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.data.Notation
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.NoteValue
import com.pianoscribe.core.theory.Pitch
import java.util.Locale

@Composable
fun NotesList(
    notes: List<Note>,
    grid: TimeGrid,
    notation: Notation,
    saPitchClass: Int,
    preferFlats: Boolean,
    selectedId: Long?,
    onNotation: (Notation) -> Unit,
    onSelect: (Note) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMusicColors.current
    val sorted = remember(notes) { notes.sortedWith(compareBy({ it.start }, { it.pitch })) }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId) {
        val idx = sorted.indexOfFirst { it.id == selectedId }
        if (idx >= 0 && (idx < listState.firstVisibleItemIndex || idx > listState.firstVisibleItemIndex + 10)) listState.animateScrollToItem(maxOf(0, idx - 3))
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${notes.size} notes", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(notation == Notation.WESTERN, { onNotation(Notation.WESTERN) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("C D E") }
                SegmentedButton(notation == Notation.SARGAM, { onNotation(Notation.SARGAM) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sa Re Ga") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Header("#", 0.8f); Header("Note", 1.1f); Header("Time", 1.2f); Header("Bar:Beat", 1.2f); Header("Duration", 2.2f); Header("Hand", 0.8f); Header("Conf.", 1f)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(sorted, key = { _, n -> n.id }) { i, n ->
                val selected = n.id == selectedId
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { onSelect(n) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name = if (notation == Notation.WESTERN) Pitch.name(n.pitch, preferFlats) else Pitch.sargam(n.pitch, saPitchClass)
                    Cell("${i + 1}", 0.8f, mono = true)
                    Text(name, Modifier.weight(1.1f), fontWeight = FontWeight.Bold, color = if (n.hand == Hand.RIGHT) colors.rightHand else colors.leftHand)
                    Cell(String.format(Locale.US, "%.2fs", n.start), 1.2f, mono = true)
                    Cell(NoteValue.barBeat(n.start, grid).compact, 1.2f, mono = true)
                    Cell(String.format(Locale.US, "%.2fs %s", n.duration, NoteValue.nameForSeconds(n.duration, grid)), 2.2f)
                    Cell(if (n.hand == Hand.RIGHT) "RH" else "LH", 0.8f)
                    Text(
                        "${Math.round(n.confidence * 100)}%" + if (n.verified) " ✓" else "",
                        Modifier.weight(1f),
                        color = if (n.confidence < 0.5f && !n.verified) colors.warning else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.Header(text: String, weight: Float) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RowScope.Cell(text: String, weight: Float, mono: Boolean = false) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
