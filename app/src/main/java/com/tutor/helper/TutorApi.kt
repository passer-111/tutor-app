package com.tutor.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Mode {
    const val SOLVE = "solve"
    const val EXPLAIN = "explain"
    const val PRACTICE = "practice"
    const val OFF_TOPIC = "off_topic"
}

/**
 * 第一次调用（看图解答或纯文本追问）的结果。
 * mode 决定界面怎么渲染：解题走三段式，讲概念直接讲。
 * answer 不进界面，只用于审核比对。
 */
data class TutorReply(
    val mode: String,
    val problemText: String,
    val knowledgePoint: String,
    val hint: String,
    val question: String,
    val explanation: String,
    val practiceProblem: String,
    val practiceHint: String,
    val answer: String,
)

/**
 * 第二次调用（审核）的结果。
 * 不合格时若带回改写稿，就用改写稿；改写稿也空，就该整条拦下。
 */
data class ReviewResult(
    val passed: Boolean,
    val issues: List<String>,
    val hint: String,
    val question: String,
    val explanation: String,
)

/** 举一反三生成出来的同类题 */
data class SimilarProblem(
    val problem: String,
    val hint: String,
    val answer: String,
)

/** 同类题的审核结果 */
data class SimilarReview(
    val passed: Boolean,
    val issues: List<String>,
    val revisedProblem: String,
)

/** 审核不通过时，让模型自己改一版 */
data class RevisedContent(
    val hint: String,
    val question: String,
    val explanation: String,
)

/** 带状态码的接口异常，用来区分「值得重试」和「不必要重试」 */
class ApiException(val code: Int, message: String) : Exception(message) {
    val retryable: Boolean get() = code == 429 || code >= 500
    val isAuthError: Boolean get() = code == 401 || code == 403
}

class TutorApi(
    private val apiKey: String,
    // 免费模型高峰期会限流，按顺序降级
    private val visionModels: List<String> = listOf(
        "glm-4.6v-flash",
        "glm-4.1v-thinking-flash",
        "glm-4v-flash",
    ),
    private val textModels: List<String> = listOf(
        "glm-4.7-flash",
        "glm-4-flash-250414",
    ),
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    // ---------- 第一步：生成讲解（有图走多模态，无图走纯文本） ----------

    suspend fun explain(
        imageBase64: String?,
        userText: String,
        history: List<Pair<String, String>>,
    ): TutorReply = withContext(Dispatchers.IO) {
        val userContent: Any = if (imageBase64.isNullOrBlank()) {
            userText
        } else {
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                        )
                    }
                )
                put(JSONObject().put("type", "text").put("text", userText))
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            history.takeLast(4).forEach { (role, text) ->
                put(JSONObject().put("role", role).put("content", text))
            }
            put(JSONObject().put("role", "user").put("content", userContent))
        }

        // 没有图片就走纯文本模型，比多模态模型快一倍以上
        val models = if (imageBase64.isNullOrBlank()) textModels else visionModels

        var lastError: ApiException? = null
        repeat(2) { round ->
            for (model in models) {
                try {
                    val body = JSONObject().apply {
                        put("model", model)
                        put("messages", messages)
                        put("temperature", 0.3)
                        put("max_tokens", 500)
                        put("response_format", JSONObject().put("type", "json_object"))
                    }
                    return@withContext parseReply(post(body))
                } catch (e: ApiException) {
                    if (!e.retryable) throw e
                    lastError = e
                }
            }
            if (round == 0) Thread.sleep(2000)
        }
        throw lastError ?: IllegalStateException("没有可用的模型")
    }

    // ---------- 第二步：把生成的内容交给另一个模型审 ----------

    suspend fun review(reply: TutorReply): ReviewResult = withContext(Dispatchers.IO) {
        val payload = buildString {
            append("模式：").append(reply.mode).append('\n')
            append("内容摘要：").append(reply.problemText.ifBlank { "（未提供）" }).append('\n')
            append("知识点：").append(reply.knowledgePoint).append('\n')
            append("提示：").append(reply.hint).append('\n')
            append("提问：").append(reply.question).append('\n')
            append("讲解：").append(reply.explanation).append('\n')
            if (reply.answer.isBlank()) {
                append("标准答案：（本题没有唯一答案，属于概念性内容，只需判断是否与学习相关）")
            } else {
                append("标准答案：").append(reply.answer)
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", REVIEW_PROMPT))
            put(JSONObject().put("role", "user").put("content", payload))
        }

        val text = callText(messages)
        parseReview(text)
    }

    // ---------- 第二步补充：审核没过时，让模型自己改一版 ----------

    suspend fun revise(reply: TutorReply, issues: List<String>): RevisedContent =
        withContext(Dispatchers.IO) {
            val payload = buildString {
                append("模式：").append(reply.mode).append('\n')
                append("原提示：").append(reply.hint).append('\n')
                append("原提问：").append(reply.question).append('\n')
                append("原讲解：").append(reply.explanation).append('\n')
                append("标准答案（改写结果里绝对不能出现）：")
                    .append(reply.answer.ifBlank { "（无）" }).append('\n')
                append("审核指出的问题：")
                    .append(issues.joinToString("；").ifBlank { "含有答案或不适内容" })
            }
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", REVISE_PROMPT))
                put(JSONObject().put("role", "user").put("content", payload))
            }
            parseRevised(callText(messages))
        }

    private fun parseRevised(text: String): RevisedContent {
        val json = parseJsonObject(text)
        return RevisedContent(
            hint = json.optString("hint").trim(),
            question = json.optString("question").trim(),
            explanation = json.optString("explanation").trim(),
        )
    }

    // ---------- 第三步：举一反三，出一道方法相同的题 ----------

    suspend fun generateSimilar(source: String): SimilarProblem = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SIMILAR_PROMPT))
            put(JSONObject().put("role", "user").put("content", source))
        }
        parseSimilar(callText(messages))
    }

    suspend fun reviewSimilar(problem: String, answer: String): SimilarReview =
        withContext(Dispatchers.IO) {
            val payload = "题目：" + problem + "\n标准答案：" + answer.ifBlank { "（未提供）" }
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SIMILAR_REVIEW_PROMPT))
                put(JSONObject().put("role", "user").put("content", payload))
            }
            parseSimilarReview(callText(messages))
        }

    // ---------- 公共请求 ----------

    private fun callText(messages: JSONArray): String {
        var lastError: ApiException? = null
        repeat(2) { round ->
            for (model in textModels) {
                try {
                    val body = JSONObject().apply {
                        put("model", model)
                        put("messages", messages)
                        put("temperature", 0.2)
                        put("max_tokens", 320)
                        put("response_format", JSONObject().put("type", "json_object"))
                    }
                    return post(body)
                } catch (e: ApiException) {
                    if (!e.retryable) throw e
                    lastError = e
                }
            }
            if (round == 0) Thread.sleep(1500)
        }
        throw lastError ?: IllegalStateException("没有可用的模型")
    }

    @Volatile
    private var currentCall: okhttp3.Call? = null

    /** 从外部打断正在飞的请求 */
    fun cancel() {
        runCatching { currentCall?.cancel() }
    }

    private fun post(body: JSONObject): String {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, "接口返回 ${response.code}：${raw.take(160)}")
                }
                val content = JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                android.util.Log.d("TutorRaw", content.take(1200))

                // 模型偶尔返回空内容，当成可重试错误，让调用方换下一个模型
                if (content.isBlank()) {
                    throw ApiException(503, "模型返回了空内容")
                }
                return content
            }
        } finally {
            currentCall = null
        }
    }

    private fun parseReply(text: String): TutorReply {
        val json = parseJsonObject(text)
        return TutorReply(
            mode = json.optString("mode").trim().ifBlank { Mode.SOLVE },
            problemText = json.optString("problem_text").trim(),
            knowledgePoint = json.optString("knowledge_point").trim(),
            hint = json.optString("hint").trim(),
            question = json.optString("question").trim(),
            explanation = json.optString("explanation").trim(),
            practiceProblem = json.optString("practice_problem").trim(),
            practiceHint = json.optString("practice_hint").trim(),
            answer = json.optString("answer").trim(),
        )
    }

    private fun parseReview(text: String): ReviewResult {
        val json = parseJsonObject(text)
        return ReviewResult(
            passed = json.optBoolean("passed", false),
            issues = readIssues(json),
            hint = json.optString("revised_hint").trim(),
            question = json.optString("revised_question").trim(),
            explanation = json.optString("revised_explanation").trim(),
        )
    }

    private fun parseSimilar(text: String): SimilarProblem {
        val json = parseJsonObject(text)
        return SimilarProblem(
            problem = json.optString("problem").trim(),
            hint = json.optString("hint").trim(),
            answer = json.optString("answer").trim(),
        )
    }

    private fun parseSimilarReview(text: String): SimilarReview {
        val json = parseJsonObject(text)
        return SimilarReview(
            passed = json.optBoolean("passed", false),
            issues = readIssues(json),
            revisedProblem = json.optString("revised_problem").trim(),
        )
    }

    private fun readIssues(json: JSONObject): List<String> {
        val issues = mutableListOf<String>()
        json.optJSONArray("issues")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) issues.add(s)
            }
        }
        return issues
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }

    /**
     * 模型偶尔会在字符串里写未转义的反斜杠（典型是 LaTeX 的 \frac），
     * 直接解析会抛 JSONException。先按原样试，失败再把反斜杠整体加倍重试。
     */
    private fun parseJsonObject(text: String): JSONObject {
        val raw = extractJson(text)
        if (raw == "{}") {
            throw JSONException("模型没有返回 JSON")
        }

        // 1. 正常解析
        try {
            return JSONObject(raw)
        } catch (_: Exception) {
        }

        // 2. 反斜杠整体加倍后重试
        try {
            return JSONObject(raw.replace("\\", "\\\\"))
        } catch (_: Exception) {
        }

        // 3. 兜底：JSON 整体已经非法，逐字段把内容抠出来
        val json = JSONObject()
        FIELD_NAMES.forEach { key ->
            extractStringField(raw, key)?.let { if (it.isNotEmpty()) json.put(key, it) }
        }
        if (json.length() == 0) {
            throw JSONException("回答格式没对上")
        }
        return json
    }

    /** 从已经损坏的 JSON 文本里，按字段名手工抠出字符串值 */
    private fun extractStringField(src: String, key: String): String? {
        val keyIdx = src.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        val colon = src.indexOf(':', keyIdx)
        if (colon < 0) return null
        var i = src.indexOf('"', colon)
        if (i < 0) return null
        i += 1

        val sb = StringBuilder()
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                when (val n = src[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append(' ')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    // 未知转义（多半是 LaTeX）原样保留，不要吞掉
                    else -> sb.append('\\').append(n)
                }
                i += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            i += 1
        }
        return sb.toString()
    }

    companion object {
        private const val ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** JSON 损坏时逐个尝试抠取的字段名 */
        private val FIELD_NAMES = listOf(
            "mode", "problem_text", "knowledge_point", "hint", "question",
            "explanation", "practice_problem", "practice_hint", "answer",
            "passed", "issues", "revised_hint", "revised_question",
            "revised_explanation", "problem", "revised_problem",
        )

        val SYSTEM_PROMPT = """
            你是一位耐心的中小学老师，正在陪孩子做作业。

            【第一步：判断这次属于哪一类】
            - solve：孩子拍了一张具体的题目，或者描述了一道待解的题 → 引导他自己解
            - explain：孩子在问一个概念、原理、公式是什么意思 → 直接讲清楚
            - practice：孩子明确要"题"才算——说"再来一道""换一道""多练几道""出题给我练"。
              如果只说"更多""继续""还有吗"这类含糊的话，要看上一轮在做什么：
                上一轮在讲概念 → 继续讲更多同类内容（判为 explain）
                上一轮在解题 → 出一道同类新题（判为 practice）
            - off_topic：与学习无关 → 拒绝

            【solve 的规则】
            不给出最终答案，不写结果数值，不给选项字母，不复述完整解题步骤。
            只给三样：考查的知识点、一条"下一步做什么"的提示、一个引导他自己想的提问。

            【explain 的规则】
            直接回答孩子问的概念，用孩子能听懂的日常语言。
            公式本身可以直接写出来，用普通符号（如 x²、√2、π、S=ab），不要用 LaTeX。
            讲完可以举一个最小的例子说明，但不要针对孩子正在做的具体题目算出答案。
            篇幅控制在 200 字以内，讲完就停，不要分点罗列也不要反复总结。

            【practice 的规则】
            参照对话里刚才讲过的题型，出一道同样方法就能解决的新题。
            换数字、换情境，难度相当，条件完整，孩子只靠题目本身就能做。
            题目描述里不要出现答案或关键中间结果，只给一条轻提示。
            只出一道，不要一口气出好几道。

            【数学符号写法，必须严格遵守】
            你的回答是 JSON，一个反斜杠就可能让整条内容报废。
            所以严禁出现反斜杠，也严禁 LaTeX（\frac、\sqrt、\sin、\times、$ 全都不许）。
            指数一律用 Unicode 上标字符，不要用 ^ 号：
              正确：x²、x³、x⁴、eˣ
              错误：x^2、x^3、e^x
            其他符号：根号写 √2，乘除写 × ÷，分数写 3/4 或「四分之三」。
            公式长就拆成几行，一行一个公式，不要堆成一坨。

            【绝对禁止】
            不讨论与学习无关的任何话题。

            【输出格式】
            只输出一个 JSON 对象，不要代码块标记，不要任何解释文字：
            {
              "mode": "solve" 或 "explain" 或 "off_topic",
              "problem_text": "内容摘要，30 字以内",
              "knowledge_point": "solve 时填，考查的知识点，20 字以内",
              "hint": "solve 时填，一条提示，60 字以内，不含答案",
              "question": "solve 时填，一个引导提问，30 字以内",
              "explanation": "explain 时填，概念讲解，150 字以内",
              "practice_problem": "practice 时填，新题目，80 字以内",
              "practice_hint": "practice 时填，一条轻提示，40 字以内",
              "answer": "完整答案与解析，孩子看不到，仅供审核比对"
            }

            不适用的字段留空字符串。
        """.trimIndent()

        val REVIEW_PROMPT = """
            你是内容审核员，负责检查 AI 给孩子的讲题内容是否越界。
            输入会标明模式是 solve 还是 explain。

            【模式为 solve 时的三条红线】
            1. 提示或提问里出现了标准答案的最终结论——具体数值、选项字母、或"所以答案是……"这类断言。
            2. 提示里写出了超过一步的解题过程。
            3. 出现与学习无关的内容。

            【模式为 explain 时的两条红线】
            1. 出现与学习无关的内容。
            2. 针对孩子问的这道具体题目，直接算出了最终数值答案。

            【重要区分，避免误判】
            - 定义、定理、公式、通式、性质、典型例子，全部属于知识内容，不是泄露答案。
            - 例如讲"圆的面积公式是 πr²"，这是正常教学，必须通过。
            - 举例说明中的数字（例如"2、3、5、7 都是质数"）属于说明性举例，不算泄露。
            - 只有"针对孩子问的这道具体题目，直接给出计算结果"才算泄露，
              例如"你问的这道题，x 等于 3"。
            - 如果输入里没有提供标准答案，说明这是纯概念问题，此时不存在"泄露答案"这回事，
              只要内容与学习相关就应当通过。
            - 宁可放过正常的知识讲解，也不要因为出现公式或数字就判不合格。

            【输出要求】
            只输出一个 JSON 对象，不要代码块标记，不要解释：
            {
              "passed": true 或 false,
              "issues": ["发现的问题，没有就空数组"],
              "revised_hint": "solve 不合格可救时，改写后的提示",
              "revised_question": "solve 不合格可救时，改写后的提问",
              "revised_explanation": "explain 不合格可救时，改写后的讲解"
            }

            合格时 passed 为 true，三个 revised 字段留空字符串。
            无法挽救时 revised 也留空，让程序去兜底。
        """.trimIndent()

        val REVISE_PROMPT = """
            你是老师，正在修改一段被审核打回的讲题内容。

            【必须做到】
            1. 保留原本想引导的知识点方向和教学意图，不要改成空话套话。
            2. 只删掉"针对具体题目的计算结果"：最终数值、选项字母、"所以答案是"这类断言。
               公式、定理、定义、通性通法都是知识内容，必须原样保留，不要一起删掉。
            3. 如果原文其实没有针对具体题目的计算结果（说明它被误判了），
               那就原样保留，不要为了显得"安全"而删减内容。
            4. 仍然要给出一条"下一步做什么"的提示，以及一个引导性的提问。
            5. 长度不超过原文，语气自然。

            【输出】
            只输出 JSON：
            {
              "hint": "改写后的提示",
              "question": "改写后的提问",
              "explanation": "改写后的讲解，原内容是概念讲解时才填"
            }
        """.trimIndent()

        val SIMILAR_PROMPT = """
            你是出题老师。根据给出的原题，出一道用完全相同方法就能解决的新题。

            【要求】
            1. 换数字、换情境、换问法，但解题思路必须和原题一致。
            2. 难度相当，条件完整，只靠题目本身就能做。
            3. 题目描述里绝对不能出现答案或关键中间结果。
            4. 只出一道题，不要出多道。

            【输出】
            只输出 JSON：
            {
              "problem": "题目，80 字以内",
              "hint": "一条轻提示，40 字以内，不含答案",
              "answer": "答案与简要解法，孩子看不到"
            }
        """.trimIndent()

        val SIMILAR_REVIEW_PROMPT = """
            你是内容审核员，检查一道给孩子的练习题能否直接使用。

            【三条红线】
            1. 题目文本里已经写出了答案或关键中间结果。
            2. 条件缺失、表述不清，孩子无法作答。
            3. 与学习无关。

            【输出】
            只输出 JSON：
            {
              "passed": true 或 false,
              "issues": ["问题描述，没有就空数组"],
              "revised_problem": "可修正时给出改写后的题目，否则留空"
            }
        """.trimIndent()
    }
}
