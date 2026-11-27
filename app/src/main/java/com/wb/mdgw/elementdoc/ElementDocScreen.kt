package com.wb.mdgw.elementdoc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.BrandTokens

/**
 * 要素式文书模块入口：领域 → 案由/文书 两级导航，选中进入填表页。
 *
 * 数据：法〔2025〕82号 9 大领域 · 67 类纠纷 · 113 份官方原表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementDocScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 阶段：领域选择 / 文书列表 / 填表
    var selectedDomain by remember { mutableStateOf<String?>(null) }
    var selectedTemplate by remember { mutableStateOf<SpcTemplate?>(null) }
    var roleFilter by remember { mutableStateOf("all") }
    var keyword by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val roles = listOf("all" to "全部", SpcRole.CLAIM to "起诉/自诉", SpcRole.DEFENSE to "答辩", SpcRole.APPLICATION to "申请", SpcRole.STATEMENT to "陈述")

    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // 填表页自身有完整 Scaffold/TopAppBar，外层隐藏避免双层标题叠加
            if (selectedTemplate != null) { Spacer(Modifier.height(0.dp)) } else TopAppBar(
                title = {
                    Column {
                        Text(
                            if (selectedDomain == null) "要素式文书" else "选择文书",
                            style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp)
                        )
                        Text("最高法 113 份官方要素式示范文本", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            selectedTemplate != null -> selectedTemplate = null
                            selectedDomain != null -> { selectedDomain = null; keyword = "" }
                            else -> onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (selectedDomain != null) {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, contentDescription = "关于") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
            )
        }
    ) { padding ->
        val template = selectedTemplate
        if (template != null) {
            ElementFillScreen(
                template = template,
                initialDraft = null, // 填表页内按案名加载草稿
                onBack = { selectedTemplate = null },
                snackbar = snackbar
            )
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selectedDomain == null) {
                    DomainList(
                        onSelect = { selectedDomain = it },
                        context = context
                    )
                } else {
                    if (showSearch) {
                        OutlinedTextField(
                            value = keyword, onValueChange = { keyword = it },
                            label = { Text("搜索案由 / 文书名") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            singleLine = true,
                            trailingIcon = { IconButton(onClick = { keyword = "" }) { Icon(Icons.Default.Close, null) } }
                        )
                    }
                    if (selectedDomain != "popular") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(8.dp))
                            roles.forEach { (code, label) ->
                                FilterChip(
                                    selected = roleFilter == code,
                                    onClick = { roleFilter = code },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                    }
                    TemplateList(
                        domainId = selectedDomain!!,
                        role = roleFilter,
                        keyword = keyword,
                        onSelect = { selectedTemplate = it },
                        context = context
                    )
                }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("要素式文书") },
            text = {
                Column {
                    Text("依据最高人民法院、司法部、中华全国律师协会《法〔2025〕82号》官方示范文本，完整内置 9 大领域 · 67 类纠纷 · 113 份要素式起诉状/答辩状/申请书/陈述书原表。")
                    Spacer(Modifier.height(8.dp))
                    Text("在线逐项勾选填空，一键导出保留官方版式的 Word（.docx），可直接在“人民法院在线服务”导入立案。")
                    Spacer(Modifier.height(8.dp))
                    Text("数据与生成引擎移植自 qwertyzhu/cn-lawyer-ai-toolbox（lawyer朱思浩），Apache-2.0 开源，保留原始署名。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } }
        )
    }
}

@Composable
private fun DomainList(onSelect: (String) -> Unit, context: android.content.Context) {
    val domains = remember { SpcCorpusStore.domains(context) }
    val total = remember { SpcCorpusStore.load(context).count }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("最高人民法院 法〔2025〕82号 · 共 $total 份官方要素式文书", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        }
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onSelect("popular") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("高频精选", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("离婚、民间借贷、买卖合同等常用 16 份", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${SpcCorpusStore.popular(context).size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        items(domains) { d ->
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { onSelect(d.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(d.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${d.count} 份", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun TemplateList(
    domainId: String,
    role: String,
    keyword: String,
    onSelect: (SpcTemplate) -> Unit,
    context: android.content.Context
) {
    var list = SpcCorpusStore.templates(context, domainId, role, keyword)
    // 按案由分组
    val groups = remember(list) { SpcCorpusStore.groupByCause(list) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (groups.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("没有匹配的文书", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        groups.forEach { group ->
            item {
                Text("${group.cause} · ${group.domainName}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            items(group.templates) { t ->
                Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onSelect(t) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(t.roleLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val draftCount = ElementDraftStore.listForTemplate(context, t.id).size
                        if (draftCount > 0) {
                            Badge { Text("$draftCount 份草稿") }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                    }
                }
            }
        }
    }
}
