"""A local stand-in for an OpenAI-compatible API, for end-to-end checks without real keys.

Usage: python scripts/mock_llm.py [port]
Then, with a device attached: adb reverse tcp:<port> tcp:<port>
In the app, choose the OpenAI-compatible provider with base URL http://127.0.0.1:<port>/v1
"""
from __future__ import annotations

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FAST_DELAY = 0.05
SLOW_DELAY = 1.0


def chunks_for(prompt: str) -> tuple[list[str], float]:
    if "请模拟失败" in prompt or "SIMULATE FAILURE" in prompt:
        return ["这是", "部分", "译文"], FAST_DELAY
    if "SIMULATE SLOW" in prompt:
        return [f"第{number}段慢速译文。\n\n" for number in range(1, 40)], SLOW_DELAY
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
    marks = ("待翻译文本：", "待查内容：", "待回译文本：")
    position = max(prompt.rfind(mark) + len(mark) if mark in prompt else -1 for mark in marks)
    source = prompt[position:].strip() if position >= 0 else "<image request>"
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
            self.reply_whole("这是从图片读取的文字。")
            return

        chunks, delay = chunks_for(prompt)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
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
