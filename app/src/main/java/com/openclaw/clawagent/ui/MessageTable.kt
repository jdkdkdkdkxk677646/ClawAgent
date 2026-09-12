package com.openclaw.clawagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.clawagent.markdown.Segment

// 色板与旧版 item_table_header_cell / table_header_bg / item_table_cell 对齐:
// 表头底色 bg3 #22263a,数据行透明,单元格文字 #e2e8f0,行间分隔线 #2a2f3a。
private val HeaderBg = Color(0xFF22263A)
private val DividerColor = Color(0xFF2A2F3A)
private val CellTextColor = Color(0xFFE2E8F0)
private val CellHPadding = 12.dp
private val CellVPadding = 10.dp

/**
 * Compose 原生表格(T-302):替代旧的 `HorizontalScrollView` + 原生
 * `TableLayout`。整体可横向滚动(列宽自适应,内容再宽也能滑),行高随内容
 * 自然展开。
 *
 * 列宽按"该列最长单元格"的测量宽度对齐,复刻旧版 `TableLayout` 的列对齐
 * 视觉;表头加粗 + 底色,行与行之间画 1dp 分隔线。空表由调用方
 * ([com.openclaw.clawagent.MessageAdapter.appendTableWidget])提前跳过。
 */
@Composable
fun MessageTable(table: Segment.Table, modifier: Modifier = Modifier) {
    val colCount = (listOf(table.headers) + table.rows).maxOfOrNull { it.size } ?: 0
    if (colCount == 0) return

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = TextStyle(
        color = CellTextColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    )

    // 每列宽度 = 该列最长单元格的测量宽度 + 左右内边距。
    val colWidths: List<Dp> = remember(table) {
        (0 until colCount).map { c ->
            val texts = buildList {
                if (table.headers.isNotEmpty()) add(table.headers.getOrElse(c) { "" })
                table.rows.forEach { add(it.getOrElse(c) { "" }) }
            }
            val maxPx = texts.maxOfOrNull { t ->
                measurer.measure(AnnotatedString(t), headerStyle).size.width
            } ?: 0
            with(density) { maxPx.toDp() } + CellHPadding * 2
        }
    }

    val rows = buildList {
        if (table.headers.isNotEmpty()) add(table.headers to true)
        table.rows.forEach { add(it to false) }
    }
    val totalWidth = colWidths.fold(0.dp) { acc, w -> acc + w }

    Column(modifier = modifier.horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { index, (cells, isHeader) ->
            TableRowView(cells, colWidths, isHeader)
            if (index != rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(totalWidth)
                        .height(1.dp)
                        .background(DividerColor),
                )
            }
        }
    }
}

@Composable
private fun TableRowView(cells: List<String>, colWidths: List<Dp>, isHeader: Boolean) {
    Row(modifier = Modifier.background(if (isHeader) HeaderBg else Color.Transparent)) {
        colWidths.forEachIndexed { column, width ->
            Text(
                text = cells.getOrElse(column) { "" },
                color = CellTextColor,
                fontSize = 14.sp,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = CellHPadding, vertical = CellVPadding),
            )
        }
    }
}
