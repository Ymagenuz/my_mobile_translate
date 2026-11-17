"""A local stand-in for an OpenAI-compatible API, for end-to-end checks without real keys.

Usage: python scripts/mock_llm.py [port]
Then, with a device attached: adb reverse tcp:<port> tcp:<port>
In the app, choose the OpenAI-compatible provider with base URL http://127.0.0.1:<port>/v1
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FAST_DELAY = 0.05
SLOW_DELAY = 1.0
STREAM_DELAY = 0.06
# How long "SIMULATE WAIT" stays silent first, like a model that thinks before it answers.
FIRST_CHUNK_WAIT = 6.0
STREAM_TEXT = (
    "## 流式译文\n\n"
    "敏捷的棕色狐狸跳过了那只懒狗。这句话包含了英文字母表中的每一个字母，常用于测试字体和键盘。\n\n"
    "- 第一项：**加粗**的内容\n"
    "- 第二项：`代码`和[链接](https://example.com)\n\n"
    "最后一段文字逐渐出现，用来观察淡入效果是否平滑。"
)


# Translations for the interpretation fixture's default lines (app/src/debug/.../InterpretFixtureActivity.kt).
INTERPRETATIONS = {
    ("你好，请问去火车站怎么走？", "English"): "Hello, could you tell me how to get to the train station?",
    ("你好，请问去火车站怎么走？", "日本語"): "こんにちは、駅へはどう行けばいいですか？",
    ("我是第一次来这个城市。", "English"): "It's my first time in this city.",
    ("我是第一次来这个城市。", "日本語"): "この街に来るのは初めてです。",
    ("Go straight for two blocks and turn left at the bank.", "中文"): "直走两个路口，在银行那里左转。",
    ("Go straight for two blocks and turn left at the bank.", "日本語"): "2ブロックまっすぐ進んで、銀行のところで左に曲がってください。",
    ("It takes about ten minutes on foot.", "中文"): "走路大概十分钟。",
    ("It takes about ten minutes on foot.", "日本語"): "歩いて10分くらいです。",
    ("駅の近くにおいしいラーメン屋がありますよ。", "中文"): "车站附近有一家很好吃的拉面店哦。",
    ("駅の近くにおいしいラーメン屋がありますよ。", "English"): "There's a great ramen shop near the station.",
}


# What the mock "hears" in a recording, by the language the prompt names.
TRANSCRIPTS = {
    "日本語": "駅はどこですか。",
    "한국어": "역이 어디예요?",
    "Français": "Où est la gare ?",
    "中文": "请问车站在哪里？",
    "English": "Where is the station?",
}
INTERPRETATIONS.update({
    ("駅はどこですか。", "English"): "Where is the station?",
    ("駅はどこですか。", "中文"): "车站在哪里？",
    ("駅はどこですか。", "한국어"): "역이 어디예요?",
    ("역이 어디예요?", "English"): "Where is the station?",
    ("역이 어디예요?", "中文"): "车站在哪里？",
    ("역이 어디예요?", "日本語"): "駅はどこですか。",
})


def transcript(prompt: str) -> str:
    language = re.search(r"说话人用(.+?)说的话", prompt)
    return TRANSCRIPTS.get(language.group(1) if language else "", "NO_SPEECH")


def interpretation(prompt: str) -> str:
    target = re.search(r"口译成(.+?)。", prompt)
    language = target.group(1) if target else "?"
    source = prompt[prompt.rfind("需要口译的内容：") + len("需要口译的内容："):].strip()
    return INTERPRETATIONS.get((source, language), f"[{language}] {source}")


def chunks_for(prompt: str) -> tuple[list[str], float]:
    if "请模拟失败" in prompt or "SIMULATE FAILURE" in prompt:
        return ["这是", "部分", "译文"], FAST_DELAY
    if "SIMULATE SLOW" in prompt:
        return [f"第{number}段慢速译文。\n\n" for number in range(1, 40)], SLOW_DELAY
    if "SIMULATE STREAM" in prompt:
        # A few characters at a time, like a real model, to watch text fade in.
        return [STREAM_TEXT[start:start + 3] for start in range(0, len(STREAM_TEXT), 3)], STREAM_DELAY
    if "同声传译员" in prompt:
        text = interpretation(prompt)
        return [text[start:start + 4] for start in range(0, len(text), 4)], STREAM_DELAY
    if "专业的多语言词典助手" in prompt:
        return ["# hello\n\n", "> **语言**：English\n\n", "## 释义\n", "- **感叹词**：你好\n"], FAST_DELAY
    if "回译引擎" in prompt:
        return ["This is ", "the back-", "translation."], FAST_DELAY
    return ["## 译文标题\n\n", "这是一段", "**流式**", "返回的译文。\n\n", "- 第一项\n", "- 第二项\n"], FAST_DELAY


def record(prompt: str) -> None:
    """Appends the text sent for translation to the MOCK_LOG file, if one is set."""
    path = os.environ.get("MOCK_LOG")
    if not path:
        return
    marks = ("待翻译文本：", "待查内容：", "待回译文本：", "需要口译的内容：")
    position = max(prompt.rfind(mark) + len(mark) if mark in prompt else -1 for mark in marks)
    source = prompt[position:].strip() if position >= 0 else "<image or audio request>"
    with open(path, "a", encoding="utf-8") as log:
        log.write(json.dumps(source, ensure_ascii=False) + "\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        pass

    def do_GET(self) -> None:
        body = json.dumps({"data": [{"id": "mock-model"}, {"id": "mock-model-large"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length))
        if self.headers.get("Authorization") != "Bearer fake-test-key":
            self.fail(401, "Invalid API key")
            return

        content = request["messages"][0]["content"]
        prompt = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
        record(prompt)
        if not request.get("stream"):
            self.reply_whole(transcript(prompt) if "逐字转写" in prompt else "这是从图片读取的文字。")
            return

        chunks, delay = chunks_for(prompt)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            if "SIMULATE WAIT" in prompt:
                self.wfile.flush()
                time.sleep(FIRST_CHUNK_WAIT)
            for chunk in chunks:
                event = {"choices": [{"delta": {"content": chunk}}]}
                self.wfile.write(f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode())
                self.wfile.flush()
                time.sleep(delay)
            if "请模拟失败" in prompt or "SIMULATE FAILURE" in prompt:
                self.wfile.write(b'data: {"error":{"message":"mock server failure"}}\n\n')
            else:
                self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionError):
            print("client disconnected during stream", flush=True)
        self.close_connection = True

    def reply_whole(self, text: str) -> None:
        body = json.dumps({"choices": [{"message": {"content": text}}]}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def fail(self, status: int, message: str) -> None:
        body = json.dumps({"error": {"message": message}}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"mock API on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
