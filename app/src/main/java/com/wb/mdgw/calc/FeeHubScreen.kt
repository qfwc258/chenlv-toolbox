package com.wb.mdgw.calc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
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

/**
 * 诉讼费入口：诉讼费计算与律师费统一从此处进入（参照源码工具箱分组思路）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeeHubScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("诉讼费与律师费", style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("诉讼费计算") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("律师费") })
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AnimatedVisibility(visible = tab == 0) { LitigationFeeScreen() }
            AnimatedVisibility(visible = tab == 1) { LawyerFeeScreen() }
        }
    }
}
