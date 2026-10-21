"""Runtime smoke test for MoGa webview (the test v1.5 should have had).

Serves assets/index.html with stubbed /api/providers and /api/llm, loads it in
headless Edge, and verifies the real user flow. Run:
  C:\\Python314\\python.exe moga_smoke.py
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

from playwright.sync_api import sync_playwright

ASSETS = r"D:\projects\moga-app\app\src\main\assets"
PORT = 8971

PROVIDERS = [
    {"id": "bigmodel", "name": "BigModel GLM", "model": "glm-4-flash"},
    {"id": "deepseek", "name": "DeepSeek", "model": "deepseek-chat"},
    {"id": "custom", "name": "Custom / Home Server", "model": "edumo"},
]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # silence
        pass

    def _send(self, code, body, ctype="application/json"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/providers":
            self._send(200, json.dumps(PROVIDERS))
        else:
            with open(ASSETS + r"\index.html", "rb") as f:
                html = f.read()
            self._send(200, html.decode("utf-8"), "text/html")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        print("[api/llm] provider=%s model=%s tools=%d base=%r sys=%r..." % (
            body.get("provider"), body.get("model"), len(body.get("tools") or []),
            body.get("base"), (body.get("messages") or [{}])[0].get("content", "")[:40]))
        self._send(200, json.dumps(
            {"choices": [{"message": {"content": "</think>\n\nThis is a stub tutor reply."}}]}))


def main():
    srv = HTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    failures = []

    def check(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            failures.append(name)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page(viewport={"width": 390, "height": 844})
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)

        page.goto(f"http://127.0.0.1:{PORT}/")
        page.wait_for_timeout(800)

        check("no JS errors on load (v1.5 TDZ bug gone)", not errors)
        if errors:
            print("  errors:", errors[:3])
        check("settings auto-open on first run",
              page.locator("#settings").is_visible())
        check("provider dropdown populated incl. custom",
              page.locator("#prov option").count() >= 3
              and page.locator("#prov option[value=custom]").count() == 1)
        check("system prompt textarea prefilled with agent persona",
              "You are MoGa" in page.locator("#sys").input_value())

        # tutor preset
        page.click("#pTutor")
        check("EduMo preset fills textarea",
              page.locator("#sys").input_value().startswith("You are EduMo"))

        # configure custom provider and save
        page.select_option("#prov", "custom")
        check("custom model placeholder hint",
              "LM Studio" in page.locator("#model").get_attribute("placeholder"))
        page.fill("#base", "http://192.168.1.10:1234/v1")
        page.fill("#model", "edumo")
        page.fill("#key", "")
        page.click("#ok")
        page.wait_for_timeout(200)
        check("save works without API key for custom",
              not page.locator("#settings").is_visible())
        check("localStorage persisted",
              page.evaluate("localStorage.moga_base") == "http://192.168.1.10:1234/v1"
              and page.evaluate("localStorage.moga_prov") == "custom")

        # chat round-trip against stub
        page.fill("#msg", "What is a fraction?")
        page.click("#send")
        page.wait_for_timeout(800)
        chat_text = page.inner_text("#chat")
        check("chat round-trip works", "stub tutor reply" in chat_text)
        check("reasoning tag stripped for custom provider", "</think>" not in chat_text)

        browser.close()
    srv.shutdown()

    print("\n" + ("ALL SMOKE TESTS PASSED" if not failures
                  else f"{len(failures)} FAILURES: {failures}"))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
