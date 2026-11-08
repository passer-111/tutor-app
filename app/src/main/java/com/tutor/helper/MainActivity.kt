package com.tutor.helper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

private val AppColors = lightColorScheme(
    primary = Color(0xFF4C6FFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EBFF),
    onPrimaryContainer = Color(0xFF152A6E),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1D26),
    surface = Color.White,
    onSurface = Color(0xFF1A1D26),
    surfaceVariant = Color(0xFFF1F3F9),
    onSurfaceVariant = Color(0xFF7A8194),
    outline = Color(0xFFE3E7F0),
    secondaryContainer = Color(0xFFEFF2F9),
    onSecondaryContainer = Color(0xFF3A4055),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                TutorScreen()
            }
        }
    }
}

private data class Msg(
    val id: Long,
    val mine: Boolean,
    val text: String = "",
    val imagePath: String = "",
    val problemText: String = "",
    val knowledgePoint: String = "",
    val hint: String = "",
    val question: String = "",
    val explanation: String = "",
    val practiceProblem: String = "",
    val note: String = "",
)

private val LEAK_PATTERNS = listOf(
    Regex("答案\\s*(是|为|就是)"),
    Regex("正确(选项|答案)"),
    Regex("选\\s*[A-Da-d](?![\\w\\u4e00-\\u9fa5])"),
    // 必须带因果连词才算结论，避免把公式里的 "x=0" 误判成答案
    Regex("(所以|因此|故|于是)\\s*[^。！？]{0,12}[=＝]\\s*[-−]?\\d"),
    Regex("最终(结果|答案|得数)"),
)

private fun looksLikeAnswerLeak(text: String): Boolean =
    LEAK_PATTERNS.any { it.containsMatchIn(text) }

private const val FALLBACK_HINT = "先看清楚题目问的是什么，再想想该用哪个公式或方法。"

@Composable
fun TutorScreen() {
    val color = MaterialTheme.colorScheme
    val context = LocalContext.current
    val store = remember { TutorStore(context) }
    val scope = rememberCoroutineScope()

    var msgs by remember { mutableStateOf(listOf<Msg>()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var seq by remember { mutableStateOf(0L) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingThumb by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun nextId(): Long {
        seq += 1
        return seq
    }

    fun append(m: Msg) {
        msgs = msgs + m
    }

    fun buildHistory(): List<Pair<String, String>> = msgs.mapNotNull { m ->
        val content = when {
            m.practiceProblem.isNotBlank() -> "同类练习：" + m.practiceProblem
            m.hint.isNotBlank() -> buildString {
                if (m.knowledgePoint.isNotBlank()) {
                    append("考点：").append(m.knowledgePoint).append("；")
                }
                append("提示：").append(m.hint)
                if (m.question.isNotBlank()) {
                    append("；提问：").append(m.question)
                }
            }
            m.explanation.isNotBlank() -> "讲解：" + m.explanation
            m.imagePath.isNotBlank() -> "（拍了一张题目）"
            else -> m.text
        }
        when {
            content.isBlank() -> null
            m.note.contains("拦下") -> null
            m.mine -> "user" to content
            else -> "assistant" to content
        }
    }

    fun friendlyError(e: Exception): String = when {
        e is org.json.JSONException -> "这次回答乱掉了，再问我一次试试。"
        e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException ->
            "等太久了，网络有点慢，再问我一次吧。"
        e is java.net.UnknownHostException || e is java.net.ConnectException ->
            "好像没网了，检查一下网络再试。"
        e is ApiException && e.isAuthError -> "接口 Key 好像不对，请让家长检查设置。"
        e is ApiException && e.code == 429 -> "老师现在有点忙，过一会儿再问我吧。"
        e is ApiException -> "服务暂时用不了（" + e.code + "），稍后再试。"
        else -> "出了点小问题，再问我一次吧。"
    }

    suspend fun doRequest(
        imageBase64: String?,
        userText: String,
        history: List<Pair<String, String>>,
    ) {
        val t0 = System.currentTimeMillis()
        try {
            val api = TutorApi(store.apiKey)
            val reply = api.explain(imageBase64, userText, history)

            when (reply.mode) {
                Mode.OFF_TOPIC -> {
                    append(Msg(nextId(), false, text = "这一步只讲作业里的题目，我们换一道题好不好？"))
                    return
                }

                Mode.EXPLAIN -> {
                    status = "正在检查…"
                    val r = runCatching { api.review(reply) }.getOrNull()

                    if (r == null) {
                        val safeText =
                            if (looksLikeAnswerLeak(reply.explanation)) "" else reply.explanation
                        if (safeText.isBlank()) {
                            append(
                                Msg(
                                    nextId(),
                                    false,
                                    text = "这个概念我暂时讲不好，你翻翻课本这一节，或者问问老师吧。"
                                )
                            )
                        } else {
                            append(
                                Msg(
                                    nextId(),
                                    false,
                                    explanation = safeText,
                                    note = "审核服务繁忙，已按本地规则检查"
                                )
                            )
                        }
                        return
                    }

                    var text = if (r.passed) reply.explanation else r.explanation
                    var note = if (r.passed) "已审核" else "已审核 · 已修正"

                    if (text.isBlank()) {
                        status = "正在调整说法…"
                        val revised = api.revise(reply, r.issues)
                        text = revised.explanation
                        note = "已审核 · 已改写"
                    }

                    if (text.isBlank()) {
                        append(
                            Msg(
                                nextId(),
                                false,
                                text = "这个概念我讲了两版都不太合适，你翻翻课本这一节，或者问问老师吧。"
                            )
                        )
                    } else {
                        append(Msg(nextId(), false, explanation = text, note = note))
                    }
                    return
                }

                Mode.PRACTICE -> {
                    status = "正在检查题目…"
                    val r = runCatching {
                        api.reviewSimilar(reply.practiceProblem, reply.answer)
                    }.getOrNull()

                    val problem = when {
                        r == null ->
                            if (looksLikeAnswerLeak(reply.practiceProblem)) "" else reply.practiceProblem
                        r.passed -> reply.practiceProblem
                        else -> r.revisedProblem
                    }

                    if (problem.isBlank()) {
                        append(
                            Msg(nextId(), false, text = "这道练习我不太满意，再跟我要一次。")
                        )
                    } else {
                        val note = when {
                            r == null -> "同类练习 · 本地检查"
                            r.passed -> "同类练习 · 已审核"
                            else -> "同类练习 · 已修正"
                        }
                        append(
                            Msg(
                                id = nextId(),
                                mine = false,
                                problemText = problem,
                                practiceProblem = problem,
                                hint = reply.practiceHint,
                                note = note,
                            )
                        )
                    }
                    return
                }

                else -> {
                    status = "正在检查有没有泄露答案…"
                    val r = runCatching { api.review(reply) }.getOrNull()

                    if (r == null) {
                        val safeHint =
                            if (looksLikeAnswerLeak(reply.hint)) FALLBACK_HINT else reply.hint
                        append(
                            Msg(
                                id = nextId(),
                                mine = false,
                                problemText = reply.problemText,
                                knowledgePoint = reply.knowledgePoint,
                                hint = safeHint,
                                question = reply.question,
                                note = "审核服务繁忙，已按本地规则检查",
                            )
                        )
                        return
                    }

                    var hint = reply.hint
                    var question = reply.question
                    var note = "已审核"

                    when {
                        r.passed -> Unit

                        r.hint.isNotBlank() && !looksLikeAnswerLeak(r.hint) -> {
                            hint = r.hint
                            question = r.question.ifBlank { reply.question }
                            note = "已审核 · 已修正"
                        }

                        else -> {
                            status = "正在调整说法…"
                            val revised = api.revise(reply, r.issues)
                            if (revised.hint.isNotBlank() && !looksLikeAnswerLeak(revised.hint)) {
                                hint = revised.hint
                                question = revised.question.ifBlank { reply.question }
                                note = "已审核 · 已改写"
                            } else {
                                hint = FALLBACK_HINT
                                question = "你先说说，这道题读到哪一步卡住了？"
                                note = "已改写两版仍不合规，改用通用提示"
                            }
                        }
                    }

                    // 内容为空绝不渲染气泡，否则会出现只剩「已审核」两字的空白卡片
                    if (hint.isBlank() && question.isBlank()) {
                        append(
                            Msg(
                                nextId(),
                                false,
                                text = "这次没想出怎么讲，你再问我一次吧。"
                            )
                        )
                        return
                    }

                    val displayHint = if (looksLikeAnswerLeak(hint)) FALLBACK_HINT else hint

                    append(
                        Msg(
                            id = nextId(),
                            mine = false,
                            problemText = reply.problemText,
                            knowledgePoint = reply.knowledgePoint,
                            hint = displayHint,
                            question = question,
                            note = note,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            append(Msg(nextId(), false, text = friendlyError(e)))
        } finally {
            android.util.Log.d("TutorTiming", "total " + (System.currentTimeMillis() - t0) + "ms")
            loading = false
            status = ""
        }
    }

    fun sendImage(uri: Uri, note: String) {
        if (loading) return
        if (store.apiKey.isBlank()) {
            showSettings = true
            return
        }
        val history = buildHistory()
        val thumb = if (pendingThumb.isNotBlank()) pendingThumb else makeThumbnail(context, uri)
        append(
            Msg(
                nextId(),
                true,
                text = if (note.isNotBlank()) note else if (thumb.isBlank()) "（拍了一张题目）" else "",
                imagePath = thumb
            )
        )
        pendingUri = null
        pendingThumb = ""
        loading = true
        status = "正在看这道题…"

        scope.launch {
            val base64 = try {
                compressToBase64(context, uri)
            } catch (e: Exception) {
                append(Msg(nextId(), false, text = "图片读不出来：" + (e.message ?: "")))
                loading = false
                status = ""
                return@launch
            }
            val prompt = if (note.isBlank()) "孩子拍了这道题，请开始引导。" else note
            doRequest(base64, prompt, history)
        }
    }

    fun send() {
        if (loading) return
        val note = input.trim()
        val uri = pendingUri

        if (uri != null) {
            input = ""
            sendImage(uri, note)
            return
        }

        if (note.isEmpty()) return
        if (store.apiKey.isBlank()) {
            showSettings = true
            return
        }
        val history = buildHistory()
        input = ""
        append(Msg(nextId(), true, text = note))
        loading = true
        status = "正在想…"
        scope.launch {
            doRequest(null, note, history)
        }
    }

    /** 举一反三：根据刚讲过的这道题，出一道方法相同的 */
    fun requestSimilar(source: Msg) {
        if (loading) return
        if (store.apiKey.isBlank()) {
            showSettings = true
            return
        }
        loading = true
        status = "正在出一道同类题…"

        scope.launch {
            try {
                val api = TutorApi(store.apiKey)
                val brief = buildString {
                    append("原题：").append(source.problemText.ifBlank { source.knowledgePoint }).append('\n')
                    append("考查的知识点：").append(source.knowledgePoint).append('\n')
                    append("原题解法要点：").append(source.hint)
                }

                val similar = api.generateSimilar(brief)
                if (similar.problem.isBlank()) {
                    append(Msg(nextId(), false, text = "这次没出出来，再点一次试试。"))
                    return@launch
                }

                status = "正在检查题目…"
                val r = api.reviewSimilar(similar.problem, similar.answer)
                val problem = if (r.passed) similar.problem else r.revisedProblem

                if (problem.isBlank()) {
                    append(
                        Msg(
                            nextId(),
                            false,
                            text = "这道练习不太合适，我再出一遍。",
                            note = "已拦下（" +
                                r.issues.joinToString("、").ifBlank { "题目不合格" } + "）"
                        )
                    )
                    return@launch
                }

                append(
                    Msg(
                        id = nextId(),
                        mine = false,
                        practiceProblem = problem,
                        hint = similar.hint,
                        note = if (r.passed) "同类练习 · 已审核" else "同类练习 · 已修正",
                    )
                )
            } catch (e: Exception) {
                append(Msg(nextId(), false, text = friendlyError(e)))
            } finally {
                loading = false
                status = ""
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = photoUri
        if (ok && uri != null) {
            pendingUri = uri
            pendingThumb = makeThumbnail(context, uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingThumb = makeThumbnail(context, uri)
        }
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "shot_" + System.currentTimeMillis() + ".jpg")
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        photoUri = uri
        cameraLauncher.launch(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color.background)
    ) {
        Header(onSettings = { showSettings = true })

        LaunchedEffect(msgs.size, loading) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.animateScrollToItem(total - 1)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (msgs.isEmpty()) {
                item { EmptyState() }
            }
            items(msgs, key = { it.id }) { m ->
                MessageItem(
                    m = m,
                    enabled = !loading,
                    onPractice = { requestSimilar(m) }
                )
            }
            if (loading) {
                item { LoadingItem(status) }
            }
        }

        BottomBar(
            enabled = !loading,
            input = input,
            onInputChange = { input = it },
            pendingThumb = pendingThumb,
            onClearPending = {
                pendingUri = null
                pendingThumb = ""
            },
            onSend = { send() },
            onCamera = { openCamera() },
            onGallery = { galleryLauncher.launch("image/*") }
        )
    }

    if (showSettings) {
        SettingsDialog(store) { showSettings = false }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    val color = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "讲题",
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = color.onBackground
            )
            Text(
                "只讲思路，不给答案",
                fontSize = 12.sp,
                color = color.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = color.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState() {
    val color = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(color.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_camera),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = color.primary
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "拍一道题，或者打字问我",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = color.onBackground
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "题目带你一步步想，概念直接讲清楚",
            fontSize = 13.sp,
            color = color.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageItem(
    m: Msg,
    enabled: Boolean,
    onPractice: () -> Unit,
) {
    val color = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start
    ) {
        val shape = if (m.mine) {
            RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
        } else {
            RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
        }

        Surface(
            shape = shape,
            color = if (m.mine && m.imagePath.isBlank()) color.primary else Color.White,
            border = if (m.mine && m.imagePath.isBlank()) null else BorderStroke(1.dp, color.outline),
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            when {
                m.imagePath.isNotBlank() -> {
                    Column(Modifier.padding(5.dp)) {
                        ThumbImage(m.imagePath)
                        if (m.text.isNotBlank()) {
                            Text(
                                m.text,
                                Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = color.onSurface
                            )
                        }
                    }
                }

                m.explanation.isNotBlank() -> {
                    Column(Modifier.padding(16.dp)) {
                        LabeledBlock("讲一讲", m.explanation)
                    }
                }

                m.practiceProblem.isNotBlank() -> {
                    PracticeBlock(m.practiceProblem, m.hint)
                }

                m.hint.isNotBlank() -> {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        if (m.knowledgePoint.isNotBlank()) {
                            LabeledBlock("考点", m.knowledgePoint)
                        }
                        LabeledBlock("提示", m.hint)
                        if (m.question.isNotBlank()) {
                            LabeledBlock("想一想", m.question, accent = true)
                        }
                    }
                }

                else -> {
                    Text(
                        m.text,
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = if (m.mine) Color.White else color.onSurface
                    )
                }
            }
        }

        val canPractice = !m.mine && (m.hint.isNotBlank() || m.practiceProblem.isNotBlank())
        if (canPractice) {
            TextButton(onClick = onPractice, enabled = enabled) {
                Text(
                    if (m.practiceProblem.isNotBlank()) "再来一道" else "举一反三",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) color.primary else color.onSurfaceVariant
                )
            }
        }

        if (m.note.isNotBlank()) {
            Text(
                m.note,
                fontSize = 11.sp,
                color = color.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp)
            )
        }
    }
}

@Composable
private fun PracticeBlock(problem: String, hint: String) {
    val color = MaterialTheme.colorScheme
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "同类练习",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = color.primary
        )
        Text(
            problem,
            fontSize = 15.sp,
            lineHeight = 24.sp,
            color = color.onSurface
        )
        if (hint.isNotBlank()) {
            Text(
                "提示：" + hint,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = color.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThumbImage(path: String, modifier: Modifier = Modifier) {
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "题目照片",
            modifier = modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            "（图片已失效）",
            Modifier.padding(14.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LabeledBlock(label: String, content: String, accent: Boolean = false) {
    val color = MaterialTheme.colorScheme
    Column {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = if (accent) color.primary else color.onSurfaceVariant
        )
        Spacer(Modifier.height(5.dp))
        BodyText(content, color.onSurface)
    }
}

/** 按行拆分渲染，避免一整段文字糊成一坨，编号列表也能自然分行 */
@Composable
private fun BodyText(content: String, color: Color) {
    val lines = remember(content) {
        content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        lines.forEach { line ->
            Text(
                line,
                fontSize = 15.sp,
                lineHeight = 25.sp,
                color = color
            )
        }
    }
}

@Composable
private fun LoadingItem(status: String) {
    val color = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            strokeWidth = 2.dp,
            color = color.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(status, fontSize = 13.sp, color = color.onSurfaceVariant)
    }
}

@Composable
private fun BottomBar(
    enabled: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    pendingThumb: String,
    onClearPending: () -> Unit,
    onSend: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val color = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (pendingThumb.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color.surfaceVariant)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThumbImage(pendingThumb, Modifier.size(46.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "已选好图片，说说要问哪一题",
                    fontSize = 12.sp,
                    color = color.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClearPending,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消图片",
                        modifier = Modifier.size(16.dp),
                        tint = color.onSurfaceVariant
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (pendingThumb.isNotBlank()) "要问哪一题？比如：第三题"
                        else "打字描述题目，或回答老师",
                        fontSize = 14.sp
                    )
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = color.surfaceVariant,
                    unfocusedContainerColor = color.surfaceVariant,
                    focusedBorderColor = color.primary,
                    unfocusedBorderColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onSend,
                enabled = enabled && (input.isNotBlank() || pendingThumb.isNotBlank()),
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color.primary)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCamera,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, color.outline)
            ) {
                Icon(
                    painterResource(R.drawable.ic_camera),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = color.primary
                )
                Spacer(Modifier.width(7.dp))
                Text("拍题目", fontSize = 14.sp, color = color.primary)
            }
            OutlinedButton(
                onClick = onGallery,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, color.outline)
            ) {
                Icon(
                    painterResource(R.drawable.ic_photo),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = color.onSurfaceVariant
                )
                Spacer(Modifier.width(7.dp))
                Text("从相册选", fontSize = 14.sp, color = color.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsDialog(store: TutorStore, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(store.apiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        title = { Text("设置", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("接口 Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (BuildConfig.ZHIPU_KEY.isBlank()) "还没有内置 Key，请填写"
                    else "留空则使用内置 Key",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.apiKey = key.trim()
                onDismiss()
            }) { Text("保存", fontWeight = FontWeight.Medium) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

private fun compressToBase64(context: android.content.Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("打不开这张图片")
    val original = input.use { BitmapFactory.decodeStream(it) }
        ?: throw IllegalStateException("无法解析图片")
    val maxSide = 1024
    val scale = minOf(
        maxSide.toFloat() / original.width,
        maxSide.toFloat() / original.height,
        1f
    )
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else {
        original
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** 生成一张用于界面显示的缩略图，避免解码原图撑爆内存 */
private fun makeThumbnail(context: android.content.Context, uri: Uri): String {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > 960) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return ""

        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "t_" + System.currentTimeMillis() + ".jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        file.absolutePath
    } catch (e: Exception) {
        ""
    }
}
