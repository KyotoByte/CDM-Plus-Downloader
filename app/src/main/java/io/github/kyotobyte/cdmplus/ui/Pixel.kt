package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Sharp 2px pixel border. */
fun Modifier.pixelBorder(color: Color = Px.Line, width: Dp = 2.dp) = this.border(width, color, RectangleShape)

/** Hand-drawn pixel icons (original art, 9x9 grids). */
object PxIcons {
    val Download = listOf("...###...", "...###...", "...###...", "...###...", "#########", ".#######.", "..#####..", "...###...", "....#....")
    val Globe = listOf("..#####..", ".#..#..#.", "#...#...#", "#########", "#...#...#", "#########", "#...#...#", ".#..#..#.", "..#####..")
    val Gear = listOf("...#.#...", ".#######.", ".##...##.", "###...###", ".##...##.", "###...###", ".##...##.", ".#######.", "...#.#...")
    val Music = listOf("....#####", "....#####", "....#...#", "....#...#", "....#...#", "..###.###", ".########", ".###..###", ".........")
    val Qr = listOf("###.#.###", "#.#...#.#", "###.#.###", "....#....", "#.###.#.#", "....#....", "###..#.#.", "#.#.#.#..", "###..###.")
    val Key = listOf("..###....", ".#...#...", ".#...#...", "..###....", "...#.....", "...#.....", "...###...", "...#.....", "...##....")
    val Plus = listOf("...###...", "...###...", "...###...", "#########", "#########", "#########", "...###...", "...###...", "...###...")
    val Pause = listOf("###...###", "###...###", "###...###", "###...###", "###...###", "###...###", "###...###", "###...###", "###...###")
    val Play = listOf("##.......", "####.....", "######...", "########.", "#########", "########.", "######...", "####.....", "##.......")
    val Close = listOf("##.....##", "###...###", ".###.###.", "..#####..", "...###...", "..#####..", ".###.###.", "###...###", "##.....##")
    val Retry = listOf("..#####..", ".##...##.", "##.......", "##....#..", "##...###.", "##..#####", ".##......", "..#####..", ".........")
    val Open = listOf("#####....", "#...#####", "#.......#", "#.......#", "#.......#", "#.......#", "#.......#", "#########", ".........")
    val Share = listOf("......##.", ".....####", "......##.", "....##...", "..##.....", "##.......", "..##.....", "....##...", "......##.")
    val Trash = listOf("..#####..", "#########", ".#.....#.", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#.....#.", "..#####..")
    val Back = listOf("...#.....", "..##.....", ".###.....", "#########", "#########", ".###.....", "..##.....", "...#.....", ".........")
    val Fwd = listOf(".....#...", ".....##..", ".....###.", "#########", "#########", ".....###.", ".....##..", ".....#...", ".........")
    val Reload = Retry
    val Star = listOf("....#....", "...###...", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", "#.......#")
    val List = listOf(".........", "#.#######", ".........", "#.#######", ".........", "#.#######", ".........", "#.#######", ".........")
    val Mask = listOf(".........", "#########", "#########", "##..#..##", "#...#...#", "##..#..##", "#########", ".###.###.", ".........")
    val Clock = listOf("..#####..", ".#..#..#.", "#...#...#", "#...#...#", "#...###.#", "#.......#", "#.......#", ".#.....#.", "..#####..")
    val Shield = listOf(".#######.", "##.....##", "##..#..##", "##.##..##", "##..#..##", ".##.#.##.", "..##.##..", "...###...", "....#....")
    val Hash = listOf("..#...#..", "..#...#..", "#########", "..#...#..", "..#...#..", "#########", "..#...#..", "..#...#..", ".........")
    val Link = listOf("....####.", "...#....#", "...#...#.", "..#.#.#..", ".#...#...", "#...#....", "#....#...", ".####....", ".........")
    val Unzip = listOf("#########", "#...#...#", "#..###..#", "#...#...#", "#..###..#", "#...#...#", "#..###..#", "#...#...#", "#########")
    val Clip = listOf("..###....", ".#...#...", ".#.#.#...", ".#.#.#...", ".#.#.#...", ".#.#.#...", ".#...#...", "..###....", ".........")
}

@Composable
fun PixelIcon(pattern: List<String>, color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val rows = pattern.size
        val cols = pattern.maxOf { it.length }
        val px = minOf(this.size.width / cols, this.size.height / rows)
        pattern.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '#') drawRect(color, Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f))
            }
        }
    }
}

/** 8-bit style progress bar: discrete blocks inside a 2px frame. */
@Composable
fun PixelProgress(progress: Float, color: Color, modifier: Modifier = Modifier, indeterminate: Boolean = false, tick: Int = 0) {
    Box(modifier.height(14.dp).pixelBorder(Px.Line).padding(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val block = 6.dp.toPx(); val gap = 2.dp.toPx()
            val count = ((size.width + gap) / (block + gap)).toInt().coerceAtLeast(1)
            val filled = (progress.coerceIn(0f, 1f) * count).toInt()
            for (i in 0 until count) {
                val on = if (indeterminate) ((i + tick) % 8) < 3 else i < filled
                drawRect(if (on) color else Px.Panel2, Offset(i * (block + gap), 0f), Size(block, size.height))
            }
        }
    }
}

@Composable
fun PixelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, color: Color = LocalAccent.current, icon: List<String>? = null, enabled: Boolean = true) {
    val bg = if (filled) color else Color.Transparent
    val fg = if (filled) Px.Bg else color
    Row(
        modifier
            .background(if (enabled) bg else Px.Panel2)
            .pixelBorder(if (enabled) color else Px.Line)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { PixelIcon(icon, if (enabled) fg else Px.Dim, 12.dp); Box(Modifier.width(8.dp)) }
        Text(text.uppercase(), style = Type.Label.copy(color = if (enabled) fg else Px.Dim, textAlign = TextAlign.Center))
    }
}

@Composable
fun PixelIconButton(icon: List<String>, onClick: () -> Unit, color: Color = Px.Text, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(modifier.size(size).pixelBorder(Px.Line).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        PixelIcon(icon, color, 14.dp)
    }
}

@Composable
fun PixelSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.width(44.dp).height(22.dp).pixelBorder(if (checked) accent else Px.Line).clickable { onChange(!checked) }.padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(16.dp).background(if (checked) accent else Px.Dim)) }
}

/** - [value] + stepper with pixel blocks showing the level. */
@Composable
fun PixelStepper(value: Int, range: IntRange, onChange: (Int) -> Unit, suffix: String = "") {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelIconButton(listOf(".........", ".........", ".........", "#########", "#########", ".........", ".........", ".........", "........."), { if (value > range.first) onChange(value - 1) }, size = 30.dp)
        Text("$value$suffix", style = Type.H2.copy(color = accent), modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
        PixelIconButton(PxIcons.Plus, { if (value < range.last) onChange(value + 1) }, size = 30.dp)
    }
}

@Composable
fun PixelTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, counts: List<Int>? = null) {
    val accent = LocalAccent.current
    Row(modifier.fillMaxWidth().pixelBorder(Px.Line)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Box(
                Modifier.weight(1f).background(if (on) accent else Color.Transparent).clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                val c = counts?.getOrNull(i)
                Text((t + if (c != null && c > 0) " $c" else "").uppercase(), style = Type.Label.copy(color = if (on) Px.Bg else Px.Dim))
            }
        }
    }
}

@Composable
fun PixelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.pixelBorder(if (selected) accent else Px.Line, 1.dp).background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(text.uppercase(), style = Type.Label.copy(color = if (selected) accent else Px.Dim, fontSize = Type.Label.fontSize * 0.9f)) }
}

/** Live speed graph: last 60 seconds as pixel columns. */
@Composable
fun PixelSpeedGraph(history: List<Long>, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(54.dp).pixelBorder(Px.Line).padding(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val max = (history.maxOrNull() ?: 0L).coerceAtLeast(1L)
            val n = history.size.coerceAtLeast(1)
            val colW = size.width / n
            val cell = 3.dp.toPx()
            history.forEachIndexed { i, v ->
                val h = (v.toFloat() / max * size.height)
                val cells = (h / cell).toInt()
                for (c in 0 until cells) {
                    drawRect(color.copy(alpha = 0.35f + 0.65f * (c + 1) / (size.height / cell)), Offset(i * colW, size.height - (c + 1) * cell), Size((colW - 1f).coerceAtLeast(1f), cell - 1f))
                }
            }
        }
    }
}
