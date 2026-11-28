package com.wb.mdgw.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.wb.mdgw.BrandTokens
import com.wb.mdgw.tableform.TableFormScreen

/**
 * 证据目录入口：法援目录、证据目录、表格填报统一从此处进入（参照源码的入口聚合思路）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceHubScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("证据目录", style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("证据目录") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("法援目录") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("表格填报") })
                }
            }
        }
    ) { pad ->
        // 三个子屏同时存活，切 Tab 不丢失编辑状态
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(pad)) {
            androidx.compose.animation.AnimatedVisibility(visible = tab == 0) {
                CatalogScreen(kind = CatalogKind.EVIDENCE, onBack = onBack, showTopBar = false)
            }
            androidx.compose.animation.AnimatedVisibility(visible = tab == 1) {
                CatalogScreen(kind = CatalogKind.ARCHIVE, onBack = onBack, showTopBar = false)
            }
            androidx.compose.animation.AnimatedVisibility(visible = tab == 2) {
                TableFormScreen(onBack = onBack, showTopBar = false)
            }
        }
    }
}
