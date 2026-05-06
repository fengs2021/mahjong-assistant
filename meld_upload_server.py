#!/usr/bin/env python3
"""副露模板上传接收服务"""
import os
import hashlib
from pathlib import Path
from flask import Flask, request, jsonify

MELD_DIR = Path("/root/mahjong_assistant_android/app/src/main/assets/meld_tiles")
TOKEN = "mahjong-upload-2024"

app = Flask(__name__)

@app.route("/meld/upload", methods=["POST"])
def upload_meld():
    token = request.headers.get("X-Auth-Token", "")
    if token != TOKEN:
        return jsonify({"code": 403, "msg": "invalid token"}), 403

    file = request.files.get("file")
    label = request.form.get("label", "未知").strip()
    direction = request.form.get("direction", "竖").strip()

    if not file or file.filename == "":
        return jsonify({"code": 400, "msg": "no file"}), 400
    if label in ("未识别", "未知", ""):
        return jsonify({"code": 400, "msg": "invalid label"}), 400

    fname = f"{label}_{direction}.png"
    path = MELD_DIR / fname

    # 处理重名
    counter = 2
    while path.exists():
        # md5 对比，相同不重复存
        existing = path.read_bytes()
        incoming = file.read()
        file.seek(0)
        if hashlib.md5(existing).hexdigest() == hashlib.md5(incoming).hexdigest():
            return jsonify({"code": 0, "msg": "duplicate skipped", "file": fname})
        fname = f"{label}_{direction}{counter}.png"
        path = MELD_DIR / fname
        counter += 1

    file.save(str(path))
    return jsonify({"code": 0, "msg": "ok", "file": fname})

@app.route("/health")
def health():
    return jsonify({"status": "ok", "templates": len(list(MELD_DIR.glob("*.png")))})

if __name__ == "__main__":
    MELD_DIR.mkdir(parents=True, exist_ok=True)
    app.run(host="0.0.0.0", port=4999, debug=False)
