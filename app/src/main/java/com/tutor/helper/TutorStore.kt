package com.tutor.helper

import android.content.Context

/**
 * 接口 Key 的取用顺序：
 * 1. 用户在设置里自己填过 → 用用户的
 * 2. 没填 → 用编译时内置的 BuildConfig.ZHIPU_KEY
 *
 * 答案不进本地存储，使用次数不设上限。
 */
class TutorStore(context: Context) {

    private val sp = context.getSharedPreferences("tutor", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty().ifBlank { BuildConfig.ZHIPU_KEY }
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    /** 当前是否在用内置 Key */
    val usingBuiltIn: Boolean
        get() = sp.getString("api_key", "").orEmpty().isBlank()
}
