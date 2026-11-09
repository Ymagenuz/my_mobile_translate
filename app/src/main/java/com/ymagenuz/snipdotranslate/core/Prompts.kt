package com.ymagenuz.snipdotranslate.core

/** Model prompts, word for word as in the desktop app. They never follow the UI language. */
object Prompts {
    const val NO_TEXT_FOUND = "NO_TEXT_FOUND"

    const val OCR = "请对这张图片进行 OCR，只提取图片中可见的文字。\n" +
        "要求：\n" +
        "1. 保留原有段落、换行和阅读顺序。\n" +
        "2. 不要翻译、不要解释、不要添加标题或 Markdown。\n" +
        "3. 如果图片中没有可识别文字，只输出 NO_TEXT_FOUND。"

    fun dictionary(sourceLang: String, targetLang: String, text: String): String = """
你是一个专业的多语言词典助手。请根据用户选择的语言设置，对下面的单词或短语进行释义和对应表达整理。

语言设置：
1. 原文语言：${sourceLang}。如果是 Auto，请先自动识别原文语言。
2. 释义/对应表达语言：${targetLang}。如果是 默认，请按“中文词语优先给英文对应表达；非中文词语优先用简体中文释义”的默认中英查词习惯处理。

必须输出结构清晰、可直接渲染的 Markdown，并严格使用下面的层级和顺序：

# {待查词条}

> **语言**：{原文语言}
>
> **读音**：{常见读音、音标或罗马化；确实不适用时写“不适用”}

## 对应表达
1. **{表达一}** — {简短说明}
2. **{表达二}** — {简短说明；没有第二项时省略}

## 释义
- **{词性或义项一}**：{解释}
- **{词性或义项二}**：{解释；没有第二项时省略}

## 用法
- **常见搭配**：{常见搭配或固定用法}
- **语气与场景**：{语气、正式程度和使用场景}

## 例句
1. {原文例句}
   - **译文**：{目标语言译文}
2. {第二个原文例句；没有时整项省略}
   - **译文**：{目标语言译文}

要求：
- 一级标题只写待查词条；固定使用“对应表达、释义、用法、例句”四个二级标题，不得改成普通段落或连续编号
- 语言和读音必须放在标题后的引用块中；释义、用法必须使用列表；例句必须使用有序列表并把译文缩进到对应例句下
- 对应表达给出 1-3 项，例句给出 1-2 项；优先选择真实自然、常见的表达
- 如果是偏抽象概念，可给出意译而不是生硬直译
- 不要使用 Markdown 表格、代码块或额外的开场白、总结；直接从一级标题开始输出

待查内容：
${text}
"""

    fun autoTranslate(sourceLang: String, targetLang: String, text: String): String = """
你是一个专业的多语言翻译引擎。请根据用户选择的语言设置翻译下方文本。

语言设置：
1. 原文语言：${sourceLang}。
2. 目标语言：${targetLang}。

规则：
1. 追求信达雅：根据目标语言的表达习惯自由调整句式、段落和语序，确保译文流畅、自然、专业。
2. 直接输出译文，不要说明识别到的语言，不要添加前缀或解释。

待翻译文本：
${text}
"""

    fun toEnglish(text: String): String = """
你是一个专业的多语言翻译引擎。请将下方文本翻译成地道的英文。

规则：
1. 追求信达雅：根据英文母语者的表达习惯自由调整句式、段落和语序，确保译文流畅、自然、专业。
2. 直接输出译文，不要任何解释，不要加前缀。

待翻译文本：
${text}
"""

    fun toChinese(text: String): String = """
你是一个专业的多语言翻译引擎。请将下方文本翻译成地道的简体中文。

规则：
1. 追求信达雅：根据中文表达习惯自由调整句式、段落和语序，确保译文流畅、自然、专业。
2. 直接输出译文，不要任何解释，不要加前缀。

待翻译文本：
${text}
"""

    fun backTranslate(language: String, text: String): String = """
你是一个专业的回译引擎。用户要通过回译核对一段译文是否准确，请将下方文本忠实地翻译成${language}。

规则：
1. 忠实直译：逐句保留原意、语气、信息量和逻辑关系，不要润色、补充、删减或纠正。
2. 遇到含糊、生硬或可能有误的表达，按字面意思译出，不要猜测作者本意。
3. 直接输出译文，不要任何解释，不要加前缀。

待回译文本：
${text}
"""
}

/** A prompt ready to send, with the placeholders to restore in its response. */
data class BuiltPrompt(val prompt: String, val replacements: Map<String, String>)

fun buildTranslationPrompt(
    text: String,
    mode: String,
    sourceLang: String = SOURCE_AUTO,
    targetLang: String = TARGET_DEFAULT,
): BuiltPrompt {
    val raw = text.pyStrip()
    val protected = protectLatexFragments(raw)
    val source = promptLanguageLabel(sourceLang)
    val target = promptLanguageLabel(targetLang, target = true)

    if (mode == TranslationMode.DICTIONARY) {
        return BuiltPrompt(Prompts.dictionary(source, target, protected.text), protected.replacements)
    }

    val actualMode = if (mode == TranslationMode.AUTO) {
        resolveAutoTranslationMode(raw, sourceLang, targetLang)
    } else {
        mode
    }
    val prompt = when (actualMode) {
        TranslationMode.AUTO -> Prompts.autoTranslate(source, target, protected.text)
        TranslationMode.ZH_TO_EN -> Prompts.toEnglish(protected.text)
        else -> Prompts.toChinese(protected.text)
    }
    return BuiltPrompt(withFormatInstruction(text, prompt), protected.replacements)
}

/** Translates a finished translation back; the source text is never included. */
fun buildBackTranslationPrompt(translation: String, language: String): BuiltPrompt {
    val protected = protectLatexFragments(translation.pyStrip())
    return BuiltPrompt(
        withFormatInstruction(translation, Prompts.backTranslate(language, protected.text)),
        protected.replacements,
    )
}

private fun withFormatInstruction(text: String, prompt: String): String {
    val instruction = markdownFormatInstruction(text)
    return if (instruction.isEmpty()) prompt else "$instruction\n\n$prompt"
}
