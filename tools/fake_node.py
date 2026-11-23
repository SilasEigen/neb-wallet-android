"""Test tool, not shipped: a pretend node for the wallet of a test phone.

It gives one coin of 5 NEB that does not exist to whatever lock asks, at a fixed height, and takes every
transaction it is sent. A payment signed against it has the right form and is refused by a real node, so the
whole path between two phones can be tried without any coin.

    python fake_node.py [PORT] [GENESIS_HEX]
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8450
GENESIS = sys.argv[2] if len(sys.argv) > 2 else "da2f5af7bb4c5e2bcc8d558b2f4f01d4c88557a943e6cff9cd035638fe9439ae"
COIN = {"txid": "04" * 32, "index": 0, "amount": 500_000_000, "locktype": 1, "height": 1, "coinbase": False}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        method, params = request["method"], request.get("params") or []
        print(method, str(params)[:70], flush=True)
        result = {
            "getblockcount": 500,
            "getblockhash": GENESIS,
            "getutxos": [COIN] if len(params) < 3 else [],
            "sendrawtransaction": "00" * 32,
        }.get(method)
        body = json.dumps({"result": result, "error": None if result is not None else {"code": -32601, "message": "no"}, "id": 1}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
