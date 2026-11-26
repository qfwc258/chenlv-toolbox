package com.wb.mdgw.injury

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 09 工伤与人损：顶层双 Tab 容器。
 * - 工伤赔偿：保留原湖南工伤口径（残疾/死亡）
 * - 人身损害：道交/一般人身损害城乡统一标准
 */
@Composable
fun InjuryHubScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                Text("工伤赔偿", fontSize = 13.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal)
            })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                Text("人身损害 · 交通事故", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal)
            })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) InjuryScreen() else PersonInjuryScreen()
        }
    }
}
