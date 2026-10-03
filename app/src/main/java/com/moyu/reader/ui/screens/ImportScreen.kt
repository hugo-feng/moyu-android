package com.moyu.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.model.ImportResult
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.ShelfViewModel
import com.moyu.reader.ui.components.MoyuPrimaryButton
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.MoyuTopBar
import com.moyu.reader.ui.components.ThinProgressBar
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 导入页。
 *
 * 提供三种导入方式，覆盖不同使用习惯：
 *   1. **选择文件**：最常用，支持一次多选；
 *   2. **选择文件夹**：批量导入整个书库目录（通过 SAF 授权目录，之后可长期访问）；
 *   3. **加载示例书**：无需准备文件即可体验。
 *
 * 权限说明：SAF 由用户主动授权，因此不需要申请任何存储权限，
 * 在 Android 10+ 也能正常读取授权范围内的文件。
 */
@Composable
fun ImportScreen(
    factory: MoyuViewModelFactory,
    onBack: () -> Unit,
    /** 导入完成后直接去读这本书 —— 少了这一步，导入流程是断的。 */
    onOpenBook: (String) -> Unit,
) {
    val viewModel: ShelfViewModel = viewModel(factory = factory)
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val results by viewModel.importResults.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    // 离开导入页时清掉上一轮的进度，避免下次进来看到陈旧记录
    LaunchedEffect(Unit) { viewModel.clearImportResults() }

    // —— 文件选择器（多选）——
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        // SAF 返回的 Uri 默认只在本次会话有效，必须立刻申请持久化权限，
        // 否则应用重启后这些书会「打不开」。BookRepository.importFromUri 里已处理。
        viewModel.import(uris)
    }

    // —— 目录选择器 ——
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // 目录授权必须持久化，否则每次启动都要重新选一次
            runCatching {
                // 权限申请在 DocumentStore 内部完成（persistPermission），
                // 这里只需要把 tree Uri 交给 ViewModel 去扫描。
                viewModel.importFolder(uri)
            }
        }
    }

    val done = results.count { it is ImportResult.Success }
    val total = results.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        MoyuTopBar(title = "导入书籍", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // —— 拖放区（点击选择文件）——
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .clickable(enabled = !importing) {
                        filePicker.launch(
                            arrayOf(
                                "text/plain",
                                "application/epub+zip",
                                "application/pdf",
                                "application/octet-stream",
                            )
                        )
                    }
                    .padding(vertical = 36.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(38.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "选择本地文件",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "支持 TXT / EPUB / PDF，可一次多选",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MoyuTextButton(
                    text = "选择文件夹",
                    icon = Icons.Filled.Folder,
                    onClick = { folderPicker.launch(null) },
                    enabled = !importing,
                )
                MoyuTextButton(
                    text = "加载示例书",
                    onClick = { viewModel.loadSampleBook() },
                    enabled = !importing,
                )
            }

            // —— 导入进度 ——
            if (importing || results.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (importing) "正在导入…（$done/$total）" else "导入完成（成功 $done / 共 $total）",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.text,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ThinProgressBar(
                    progress = if (total > 0) done.toFloat() / total else 0f,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                /**
                 * 导入完成后的去向。
                 *
                 * 之前这里只有一份结果列表，**没有任何「去读书」的入口** ——
                 * 用户导入完只能自己返回书库、点开书、进详情页、再点开始阅读，
                 * 四步跳转里少了三步。刚导入的那本书就在手边，直接给按钮。
                 *
                 * 取**最后一本成功导入**的书：批量导入时用户最新关心的就是它。
                 */
                val lastImported = results.filterIsInstance<ImportResult.Success>().lastOrNull()
                if (!importing && lastImported != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MoyuPrimaryButton(
                            text = "开始阅读《${lastImported.title}》",
                            icon = Icons.Filled.PlayArrow,
                            onClick = { onOpenBook(lastImported.bookId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                /**
                 * 汇总提示（「已导入 N 本…」这类）。
                 *
                 * ViewModel 早就在写这条消息，但界面从来没渲染过它 ——
                 * 状态白白算了一遍，用户什么也看不到。
                 */
                message?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                results.forEach { result ->
                    ImportResultRow(result)
                }
            }

            // —— 说明 ——
            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "关于格式支持",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.text,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                BulletText("文本（.txt）：自动识别 UTF-8 / GB18030 / Big5 / UTF-16 编码，并按章节标题自动分章。")
                BulletText("电子书（.epub）：读取目录与正文，自动提取封面。")
                // 如实描述：PDF 目前只建书目记录，不能翻页阅读（渲染尚未实现）。
                // 不能写成「可在阅读器里逐页查看」—— 那是谎报功能。
                BulletText("PDF 文档（.pdf）：当前只保存书目记录，暂不支持翻页阅读。")
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "所有文件都从你授权的目录读取，应用不会主动扫描设备存储。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun BulletText(text: String) {
    val palette = moyuPalette()
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = 8.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.primary),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun ImportResultRow(result: ImportResult) {
    val palette = moyuPalette()
    val (name, status, isError) = when (result) {
        is ImportResult.Success -> Triple(
            result.title,
            buildString {
                append("${result.chapterCount} 章 · ${result.charCount} 字 · ${result.encoding}")
                if (result.encodingUncertain) append(" · 编码可能不准确")
                if (result.warnings.isNotEmpty()) append(" · ${result.warnings.first()}")
            },
            false,
        )

        is ImportResult.Duplicate -> Triple(result.title, "书架中已有同名同字数的书", false)
        is ImportResult.Failure -> Triple(result.fileName, result.message, true)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) Color(0xFFA5453A) else palette.textSecondary,
            maxLines = 2,
        )
    }
}
