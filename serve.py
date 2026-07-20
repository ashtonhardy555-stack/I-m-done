#!/usr/bin/env python3
"""Simple dev server for the web edition."""
import http.server, socketserver, os

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "web"))
PORT = int(os.environ.get("PORT", 8080))
Handler = http.server.SimpleHTTPRequestHandler
with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print(f"Serving web/ on port {PORT}")
    httpd.serve_forever()
