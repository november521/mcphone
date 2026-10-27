"""Loopback-only fixture for http-json.vue; no credentials or external API calls."""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 <= length <= 65536:
            self.send_error(413)
            return
        raw = self.rfile.read(length)
        try:
            question = json.loads(raw.decode('utf-8')).get('question', '')
        except (ValueError, AttributeError):
            self.send_error(400)
            return
        if self.path == '/slow':
            time.sleep(12)
        elif self.path == '/delay':
            time.sleep(4)
        status = 500 if self.path == '/error' else 200
        answer = '这是一条本地接口演示回复。\n你发送的问题是：' + str(question)
        answer += '\n\n请求由游戏客户端直接发送，没有经过 Minecraft 服务端。'
        if self.path == '/large':
            answer = '超限' * 40000
        body = json.dumps({'answer': answer}, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_args):
        pass


if __name__ == '__main__':
    print('MCPhone demo: http://127.0.0.1:18765/answer (Ctrl+C to stop)', flush=True)
    with ThreadingHTTPServer(('127.0.0.1', 18765), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
