#!/usr/bin/env python3
"""雀魂模板批量切割+识别 —— PPG-AN00 横屏 2800×1264
用法:
  切图: python3 crop_tiles.py --crop <截图> [输出目录]
  识别: python3 crop_tiles.py --match <截图>
"""
from PIL import Image
import cv2, numpy as np, os, sys, glob

# ═══ 固定坐标 ═══
HAND_X_OFFSET = 300
FIRST_TILE_X = 236
TILE_SPACING = 111
TILE_SLOT_W = 108
BORDER_LR = 5
FACE_Y1 = 1106
FACE_Y2 = 1249
FACE_W = TILE_SLOT_W - 2 * BORDER_LR  # 98
FACE_H = FACE_Y2 - FACE_Y1            # 143

# 摸牌
DRAWN_SLOT_X = 2014
MAIN_HAND_RIGHT = HAND_X_OFFSET + FIRST_TILE_X + 12 * TILE_SPACING + TILE_SLOT_W  # 1976

TEMPLATE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "new_templates")
ID2NAME = {0:"一万",1:"二万",2:"三万",3:"四万",4:"五万",5:"六万",6:"七万",7:"八万",8:"九万",
           9:"一筒",10:"二筒",11:"三筒",12:"四筒",13:"五筒",14:"六筒",15:"七筒",16:"八筒",17:"九筒",
           18:"一索",19:"二索",20:"三索",21:"四索",22:"五索",23:"六索",24:"七索",25:"八索",26:"九索",
           27:"東",28:"南",29:"西",30:"北",31:"白",32:"発",33:"中"}
NAME2ID = {v:k for k,v in ID2NAME.items()}

def load_templates():
    templates = {}
    for f in sorted(glob.glob(os.path.join(TEMPLATE_DIR, '*.png'))):
        name = os.path.basename(f).replace('.png', '')
        templates[name] = cv2.imread(f, cv2.IMREAD_GRAYSCALE)
    return templates

def match_tile(tile_gray, templates):
    best_name, best_score = None, -1.0
    for tname, tpl in templates.items():
        tpl_r = cv2.resize(tpl, (tile_gray.shape[1], tile_gray.shape[0]))
        score = cv2.matchTemplate(tile_gray, tpl_r, cv2.TM_CCOEFF_NORMED)[0][0]
        if score > best_score:
            best_score = score; best_name = tname
    return best_name, best_score

def crop_main(screenshot_path, outdir, n_tiles=13):
    im = Image.open(screenshot_path)
    w, h = im.size
    if (w, h) != (2800, 1264):
        print(f"⚠ 尺寸 {w}x{h} ≠ 2800x1264")
    os.makedirs(outdir, exist_ok=True)
    for i in range(n_tiles):
        slot_left = HAND_X_OFFSET + FIRST_TILE_X + i * TILE_SPACING
        face_left = slot_left + BORDER_LR
        face_right = slot_left + TILE_SLOT_W - BORDER_LR
        crop = im.crop((face_left, FACE_Y1, face_right, FACE_Y2))
        path = os.path.join(outdir, f"tile_{i:02d}.png")
        crop.save(path, 'PNG')
        print(f"  [{i:02d}] {crop.size[0]}x{crop.size[1]} → {path}")
    # Also crop drawn tile
    face_left = DRAWN_SLOT_X + BORDER_LR
    face_right = DRAWN_SLOT_X + TILE_SLOT_W - BORDER_LR
    crop = im.crop((face_left, FACE_Y1, face_right, FACE_Y2))
    path = os.path.join(outdir, "tile_drawn.png")
    crop.save(path, 'PNG')
    print(f"  [摸] {crop.size[0]}x{crop.size[1]} → {path}")
    print(f"\n切 {n_tiles}+1 张完成")

def match_screenshot(screenshot_path):
    screenshot = cv2.imread(screenshot_path)
    templates = load_templates()
    if not templates:
        print("✗ 未找到模板, 请先收集 34 张模板到 new_templates/")
        return

    print("主手牌 (13张):")
    results = []
    for i in range(13):
        slot_left = HAND_X_OFFSET + FIRST_TILE_X + i * TILE_SPACING
        face_left = slot_left + BORDER_LR
        face_right = slot_left + TILE_SLOT_W - BORDER_LR
        tile_gray = cv2.cvtColor(screenshot[FACE_Y1:FACE_Y2, face_left:face_right], cv2.COLOR_BGR2GRAY)
        name, score = match_tile(tile_gray, templates)
        results.append((name, score))
        print(f"  [{i:02d}] {name} ({score:.3f})")

    # 摸牌
    face_left = DRAWN_SLOT_X + BORDER_LR
    face_right = DRAWN_SLOT_X + TILE_SLOT_W - BORDER_LR
    tile_gray = cv2.cvtColor(screenshot[FACE_Y1:FACE_Y2, face_left:face_right], cv2.COLOR_BGR2GRAY)
    name, score = match_tile(tile_gray, templates)
    results.append((name, score))
    print(f"  [摸] {name} ({score:.3f})")

    print(f"\n手牌: {' '.join([r[0] for r in results])}")
    print(f"分数: {min(r[1] for r in results):.3f} ~ {max(r[1] for r in results):.3f}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python3 crop_tiles.py --crop <截图> [输出目录]")
        print("      python3 crop_tiles.py --match <截图>")
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "--crop":
        crop_main(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "./tiles_out")
    elif cmd == "--match":
        match_screenshot(sys.argv[2])
    else:
        print(f"未知命令: {cmd}")
