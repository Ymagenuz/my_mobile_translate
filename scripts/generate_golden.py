"""Record the desktop app's prompts and text rules as golden data for the Kotlin tests.

Usage: python scripts/generate_golden.py <desktop project dir> <output json>

The desktop GUI module is parsed, not imported, so PyQt6 isn't needed.
"""
from __future__ import annotations

import ast
import json
import re
import sys
from pathlib import Path
from types import SimpleNamespace

CORPUS = [
    "",
    "hello",
    "Hello",
    "Hello world",
    "look up to",
    "one two three four",
    "state-of-the-art",
    "don't",
    "A plain translation sentence.",
    "The weather is nice and the sun is out.",
    "这是一个测试句子。",
    "翻译",
    "人工智能",
    "我喜欢",
    "这是",
    "一二三四五",
    "混合 text",
    "mixed 文本 words",
    "これはテストです。",
    "이것은 테스트입니다.",
    "Это проверка связи.",
    "Le chat est sur la table et il dort.",
    "Der Hund ist nicht in der Küche.",
    "El perro está en la casa con los niños.",
    "Il gatto non è sul tavolo della cucina.",
    "12345",
    "カタカナ",
    "한국어",
    "😀",
    "😀 hello",
    "ab😀cd",
    "Prices range from $5 to $10.",
    "The result is $E = mc^2$.",
    r"C:\Users\example\notes.txt",
    r"\section{Introduction}",
    r"\section*{Intro} text",
    r"\[x^2 + y^2 = z^2\]",
    r"\(a + b\) and more",
    r"$$\sum_{i=1}^n i$$",
    r"\alpha中",
    r"\alpha x",
    r"\alphabet",
    r"\begin{align} F &= ma \end{align} then prose",
    "\\section{Energy}\nThe relation is $E = mc^2$; see \\ref{eq:mass}.\n% a comment",
    "\\documentclass{article}\n\\usepackage{amsmath}\n\\section{实验说明}\n"
    "\\begin{equation}\nabcdefghijklmnopqrstuvwxyz = E + mc^2\n\\end{equation}\n这是中文正文。\n",
    r"\verb|x y| and \cite[p. 3]{knuth} done",
    "# Title\n\n- Item",
    "1. first\n2. second",
    "> quoted line",
    "| a | b |\n| - | - |\n| 1 | 2 |",
    "```\ncode\n```",
    "   - indented bullet",
    "-not a bullet",
    "exam-\nple of a wrapped word",
    "A line that wraps\nonto the next line.\nAnother sentence here\n\nNew paragraph",
    "第一行没有结束\n第二行结束。\n第三行",
    "line one\r\nline two\rline three",
    "tabs\tand  double  spaces",
    "ideographic\u3000space",
    "nbsp\u00a0here",
    "trailing newline\n",
    "  surrounded  ",
    "quote”\nnext",
    "ends with colon:\nnext",
    "semi;\nnext",
    "问题？\n回答",
    "你好",
    "很好",
    "没有",
    "苹果 apple",
    "苹果 apple banana",
    "a.b",
    "foo: bar",
    "abcdefghijklmnopqrstuvwxyzabcdef",
    "abcdefghijklmnopqrstuvwxyzabcdefg",
    "aaaaaaaa bbbbbbbb cccccccc d",
    "aaaaaaaa bbbbbbbb cccccccccc",
    "éééééééééé",
    "ééééééééééé",
    "1234567890a",
    "123",
    "Very long Chinese 中文中文中文中文中文 mixed with some english words here",
    "这个 product 的 quality 非常好",
    "日本語のテキストと漢字",
    "漢字が多い文章にかなが少し",
    "$2$ is two",
    "$a$5",
]

def function_source(tree: ast.Module, source: str, name: str) -> str:
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == name:
            return ast.get_source_segment(source, node)
    raise LookupError(name)


def class_node(tree: ast.Module, name: str) -> ast.ClassDef:
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name == name:
            return node
    raise LookupError(name)


def method_node(cls: ast.ClassDef, name: str) -> ast.FunctionDef:
    for node in cls.body:
        if isinstance(node, ast.FunctionDef) and node.name == name:
            return node
    raise LookupError(name)


def fstring_templates(function: ast.FunctionDef) -> list[ast.JoinedStr]:
    return [
        node.value
        for node in ast.walk(function)
        if isinstance(node, ast.Return) and isinstance(node.value, ast.JoinedStr)
    ]


def render(node: ast.JoinedStr, names: dict[str, object]) -> str:
    code = compile(ast.Expression(body=node), "<prompt>", "eval")
    return eval(code, {}, names)


def main(desktop: Path, output: Path) -> None:
    sys.path.insert(0, str(desktop))
    import latex_support

    source = (desktop / "snipdo_translate.pyw").read_text(encoding="utf-8")
    tree = ast.parse(source)
    rich_text = (desktop / "ui_rich_text.py").read_text(encoding="utf-8")
    block_re = re.search(r"_MARKDOWN_BLOCK_RE = re\.compile\(\s*(r'.*?')\s*\)", rich_text, re.S)
    markdown_block = re.compile(ast.literal_eval(block_re.group(1)))

    def is_markdown_structured_text(text: str) -> bool:
        return bool(text) and bool(markdown_block.search(text))

    namespace: dict[str, object] = {
        "re": re,
        "unquote": lambda text: text,
        "is_latex_text": latex_support.is_latex_text,
        "latex_prose_for_language_detection": latex_support.latex_prose_for_language_detection,
        "is_markdown_structured_text": is_markdown_structured_text,
    }
    for assignment in tree.body:
        if isinstance(assignment, ast.Assign) and getattr(assignment.targets[0], "id", "") == "_LATIN_LANGUAGE_MARKERS":
            exec(ast.get_source_segment(source, assignment), namespace)
    for name in (
        "normalize_newlines",
        "is_structured_text",
        "markdown_format_instruction",
        "normalize_input_text",
        "is_dictionary_mode",
        "resolve_auto_translation_mode",
        "detect_text_language",
    ):
        exec(function_source(tree, source, name), namespace)

    rules = []
    for text in CORPUS:
        protected, replacements = latex_support.protect_latex_fragments(text)
        rules.append(
            {
                "text": text,
                "isLatex": latex_support.is_latex_text(text),
                "isMarkdown": is_markdown_structured_text(text),
                "isStructured": namespace["is_structured_text"](text),
                "protected": protected,
                "replacements": replacements,
                "prose": latex_support.latex_prose_for_language_detection(text),
                "formatInstruction": namespace["markdown_format_instruction"](text),
                "normalized": namespace["normalize_input_text"](text),
                "isDictionary": namespace["is_dictionary_mode"](text),
                "autoMode": namespace["resolve_auto_translation_mode"](text),
                "language": namespace["detect_text_language"](text),
            }
        )

    translation = class_node(tree, "TranslationThread")
    dictionary, auto, to_english, to_chinese = fstring_templates(method_node(translation, "build_prompt"))
    back = fstring_templates(method_node(class_node(tree, "BackTranslationThread"), "build_prompt"))[0]
    names = {"source_lang": "SOURCE", "target_lang": "TARGET", "text_clean": "TEXT"}

    ocr_prompt = next(
        node.values[node.keys.index(next(k for k in node.keys if getattr(k, "value", "") == "text"))].value
        for node in ast.walk(class_node(tree, "OcrThread"))
        if isinstance(node, ast.Dict)
        and any(getattr(k, "value", "") == "type" for k in node.keys)
        and any(getattr(v, "value", "") == "text" for v in node.values)
    )

    golden = {
        "prompts": {
            "dictionary": render(dictionary, names),
            "auto": render(auto, names),
            "toEnglish": render(to_english, names),
            "toChinese": render(to_chinese, names),
            "back": render(back, {"self": SimpleNamespace(language="LANGUAGE"), "text_clean": "TEXT"}),
            "ocr": ocr_prompt,
        },
        "rules": rules,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(golden, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {len(rules)} rule cases to {output}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
