package com.wb.mdgw.law

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.UI_RADIUS_MD
import com.wb.mdgw.BrandTokens

/**
 * 法律查询（按参照源码重新设计）：离线法规库 + 条号智能搜索 + 收藏引注 + 实务核对清单。
 * 三个 Tab：法规检索 / 核对清单 / 使用说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LawSearchNewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { LawCorpusStore.entries(context) }
    val checklists = remember { LawCorpusStore.checklists(context) }
    val dataAsOf = remember { LawCorpusStore.dataAsOf(context) }

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(StatuteEngine.Scope.ALL) }
    var starred by remember { mutableStateOf(LawCorpusStore.getStarred(context)) }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }

    val result = remember(query, scope, starred) {
        StatuteEngine.search(entries, query, scope, starred, limit = 80)
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("法规引文", text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("法律查询", style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
                )
                PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("法规检索") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("核对清单") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("说明") })
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
        when (tab) {
            0 -> {
                // 搜索框
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text("585 / 民法典第585条 / 违约金调减", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = UI_RADIUS_MD
                )

                // 分类筛选 chips
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatuteEngine.Scope.entries.forEach { s ->
                        FilterChip(
                            selected = scope == s,
                            onClick = { scope = s },
                            label = {
                                Text(
                                    when (s) {
                                        StatuteEngine.Scope.ALL -> "全部 ${entries.size}"
                                        StatuteEngine.Scope.CIVIL -> "民法典 1260"
                                        StatuteEngine.Scope.STARRED -> "⭐ 常看法条 ${starred.size}"
                                        else -> s.label
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                // 高频速跳
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("高频速跳：", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    StatuteEngine.QUICK_JUMPS.forEach { (q, label) ->
                        Text(
                            label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { scope = StatuteEngine.Scope.ALL; query = q }
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // 提示条
                if (result.uncollectedNotice.isNotBlank() || result.clauseNotice.isNotBlank()) {
                    Text(
                        result.uncollectedNotice.ifBlank { result.clauseNotice },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    )
                }

                // 统计
                Text(
                    "共 ${result.hits.size} 条${if (result.hits.size >= 80) "（仅显示前 80）" else ""} · 语料核验截至 $dataAsOf",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(result.hits, key = { it.key }) { entry ->
                        StatuteCard(
                            entry = entry,
                            expanded = expandedKey == entry.key,
                            starred = entry.key in starred,
                            copied = copiedKey == entry.key,
                            onToggleExpand = { expandedKey = if (expandedKey == entry.key) null else entry.key },
                            onToggleStar = { starred = LawCorpusStore.toggleStarred(context, entry.key) },
                            onCopy = { withMeta ->
                                copyText(StatuteEngine.citation(entry, withMeta, dataAsOf))
                                copiedKey = entry.key
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }

            1 -> ChecklistsTab(checklists)
            2 -> AboutTab(entries.size, dataAsOf)
        }
        }
    }
}

@Composable
private fun StatuteCard(
    entry: StatuteEntry,
    expanded: Boolean,
    starred: Boolean,
    copied: Boolean,
    onToggleExpand: () -> Unit,
    onToggleStar: () -> Unit,
    onCopy: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleExpand)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "《${entry.lawTitle}》${entry.articleLabel}",
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleStar, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Star, contentDescription = "收藏",
                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (entry.breadcrumb.isNotBlank()) {
            Text(entry.breadcrumb, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.summary.isNotBlank()) {
            Text(entry.summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            entry.text, fontSize = 12.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (expanded) {
            if (entry.litTips.isNotEmpty()) {
                Text("诉讼实务提示", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 8.dp))
                entry.litTips.forEach { Text("· $it", fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
            if (entry.nonLitTips.isNotEmpty()) {
                Text("非诉审查提示", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp))
                entry.nonLitTips.forEach { Text("· $it", fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
            if (entry.interpRefs.isNotEmpty()) {
                Text("配套司法解释（${entry.interpRefs.size}）", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp))
                entry.interpRefs.forEach {
                    Text("· ${it.docTitle} ${it.label}：${it.snippet}", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("复制引文", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCopy(false) }
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp))
                Text(if (copied) "已复制✓" else "规范引注", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCopy(true) }
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ChecklistsTab(checklists: List<PracticeChecklist>) {
    var selected by remember { mutableIntStateOf(0) }
    val current = checklists.getOrNull(selected)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            checklists.forEachIndexed { idx, c ->
                FilterChip(
                    selected = selected == idx,
                    onClick = { selected = idx },
                    label = { Text(c.tag, fontSize = 11.sp) }
                )
            }
        }
        if (current == null) {
            Text("暂无核对清单", modifier = Modifier.padding(16.dp))
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(current.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(current.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            current.sections.forEach { sec ->
                Text(sec.heading, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                sec.items.forEachIndexed { i, item ->
                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        Text("${i + 1}. ", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(item, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "复制全部核查项",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    val text = buildString {
                        append("【${current.title}】\n")
                        current.sections.forEach { sec ->
                            append("\n${sec.heading}\n")
                            sec.items.forEachIndexed { i, it -> append("${i + 1}. $it\n") }
                        }
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("核对清单", text))
                    Toast.makeText(context, "已复制核对清单", Toast.LENGTH_SHORT).show()
                }.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AboutTab(entries: Int, dataAsOf: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("法律查询 · 离线法规库", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        listOf(
            "离线收录 $entries 条法规：民法典 1260 条 + 40 份民商事核心司法解释。",
            "支持自然检索式：585、第五百八十五条、民法典第585条、民法典 第1165条 第2款。",
            "严格禁止未收录法规的母法静默替代（如查「刑法第264条」不会返回民法典条文）。",
            "⭐ 常看法条收藏本地保存，默认收藏 585/561/577/1195。",
            "规范引注附带文号、核验日期与国家法律法规数据库（flk.npc.gov.cn）核对提示。",
            "核对清单共 6 套，覆盖立案保全、执行回款、行政诉讼、刑事辩护、刑拘 37 天与年度合规。",
            "语料核验截至 $dataAsOf。法规以国家法律法规数据库现行有效文本为准。"
        ).forEach {
            Text("· $it", fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
