#!/usr/bin/env python3
"""Build original 11px workshop icons and a reproducible Minecraft resource pack (stdlib only)."""
from pathlib import Path
import binascii
import json
import struct
import shutil
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / 'resourcepacks/fpbcraft-waypoint-icons'
PALETTE = {'Y': 'F6CA65', 'O': 'D58C42', 'T': '65CAB8', 'G': '8FBD72', 'B': '80B8D8',
           'I': 'F0E9D4', 'S': 'A7BAC5', 'R': 'DA7867', 'P': 'B697DB', 'D': '243540'}
# Nine-pixel silhouettes; the tenth/eleventh pixels leave room for the shared dark contour.
ICONS = {
 'workshop': ['...SS....','...SS....','..SSSS...','.SSSSSS..','SSSSSSSS.','..TOOT...','..TOOT...','..TOOT...','..TTTT...'],
 'factory': ['..SS.....','..SS.....','..SS..SS.','..SS..SS.','TTTTTTTT.','TITITITT.','TTTTTTTT.','TITITITT.','TTTTTTTT.'],
 'cog': ['...YYY...','.YYYYYYY.','.YY...YY.','YY..I..YY','YY.III.YY','YY..I..YY','.YY...YY.','.YYYYYYY.','...YYY...'],
 'train': ['...SSS...','..SSSSS..','..BIBIB..','..BBBBB..','TTBBBBBT.','TTTYTTTT.','.SSSSSSS.','..S...S..','.SS...SS.'],
 'station': ['....Y....','...YYY...','..YYYYY..','.YYYYYYY.','TTTTTTTTT','T..III..T','T..III..T','T..III..T','SSSSSSSSS'],
 'railway': ['.S.....S.','OSOOOOOSO','.S.....S.','.S.....S.','OSOOOOOSO','.S.....S.','.S.....S.','OSOOOOOSO','.S.....S.'],
 'airship': ['..TTTTT..','.TTIITTT.','TTIIIITTT','TTIIIITTT','.TTTTTTT.','..S...S..','..S...S..','..OOOOO..','...OOO...'],
 'ship': ['....S....','....SI...','....SII..','....SIII.','....SIIII','....S....','OOOOOOOOO','.OOOOOOO.','..BBBBB..'],
 'harbour': ['...SSS...','...S.S...','...SSS...','....S....','.S..S..S.','.S..S..S.','.SSSSSSS.','..SSSSS..','....S....'],
 'warehouse': ['....O....','...OOO...','..OOOOO..','.OOOOOOO.','TTTTTTTTT','TSSSSSSST','TSOOOOSST','TSOOOOSST','TTTTTTTTT'],
 'mine': ['..SSSSS..','.SSOOOSS.','SSOOOOOSS','SSTT..TSS','SS.T..TSS','SS..T.TSS','SS...TTSS','SS....TSS','SS....TSS'],
 'quarry': ['SSSSSSSSS','S.......S','SSSSSS..S','.....S..S','..SSSS..S','..S.....S','..SSSSSSS','........S','SSSSSSSSS'],
 'smelter': ['.SSSSSSS.','.SIIIIIS.','.SSSSSSS.','.S.....S.','.SSSSSSS.','.S..R..S.','.S.RYR.S.','.SRYYYRS.','.SSSSSSS.'],
 'power': ['.....YY..','....YY...','...YY....','..YYYYYY.','.....YY..','....YY...','...YY....','..YY.....','.YY......'],
 'watermill': ['...SSS...','.S.TTT.S.','.TT.B.TT.','ST.BBB.TS','STBBYBBTS','ST.BBB.TS','.TT.B.TT.','.S.TTT.S.','...SSS...'],
 'windmill': ['....I....','....I....','....I....','IIIII....','...OYIIII','...OO....','..OOOO...','..OTTO...','.OOTTOO..'],
 'bridge': ['SSSSSSSSS','SSSSSSSSS','S..SSS..S','S..SSS..S','S..SSS..S','B..BBB..B','BBBBBBBBB','..BBBBB..','BBBBBBBBB'],
 'market': ['RRIIIRRII','RRIIIRRII','RRIIIRRII','.S.....S.','.S.....S.','.SYGYGYS.','.SOOOOOS.','.SSSSSSS.','.S.....S.'],
 'colony': ['....Y....','...YYY...','..YYYYY..','..TITIT..','..TTTTT..','.O.TTT.O.','OOOTTTTOO','TITTTTTIT','TTTTTTTTT'],
 'construction': ['....Y....','..YYYYY..','.YYYYYYY.','.YYIYIYY.','YYYYYYYYY','.........','..OO.OO..','..OO.OO..','..SS.SS..'],
 'supply': ['TTTTTTTTT','TYYYYYYYT','TYIIYIIYT','TYIIYIIYT','TYYYYYYYT','TYIIYIIYT','TYIIYIIYT','TYYYYYYYT','TTTTTTTTT'],
 'portal': ['..PPPPP..','.PP...PP.','PP.BBB.PP','PP.BIB.PP','PP.BBB.PP','PP.BIB.PP','PP.BBB.PP','.PP...PP.','..PPPPP..'],
 'farm': ['...G.G...','..GG.GG..','...GYG...','..GGYGG..','...GYG...','..GGYGG..','....Y....','....Y....','..OOOOO..'],
 'forest': ['....G....','...GGG...','..GGGGG..','...GGG...','..GGGGG..','.GGGGGGG.','GGGGGGGGG','...OOO...','...OOO...'],
}


def png(path, width, height, pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)
    raw = b''.join(b'\0' + bytes(sum((list(px) for px in pixels[y * width:(y + 1) * width]), [])) for y in range(height))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def pixels(rows):
    assert len(rows) == 9 and all(len(row) == 9 for row in rows)
    data = [(0, 0, 0, 0)] * 121
    for y, row in enumerate(rows, 1):
        for x, value in enumerate(row, 1):
            if value == '.':
                continue
            color = tuple(bytes.fromhex(PALETTE[value])) + (255,)
            for dy in [-1, 0, 1]:
                for dx in [-1, 0, 1]:
                    at = (y + dy) * 11 + x + dx
                    if data[at][3] == 0:
                        data[at] = (24, 39, 49, 255)
            data[y * 11 + x] = color
    return data


def main():
    PACK.mkdir(parents=True, exist_ok=True)
    (PACK / 'pack.mcmeta').write_text(json.dumps({'pack': {'description': 'FPBCraft · Workshop waypoint icons', 'pack_format': 34, 'supported_formats': [34, 1000], 'min_format': 34, 'max_format': 1000}}, indent=2) + '\n')
    (PACK / 'LICENSE.txt').write_text('Original FPBCraft workshop waypoint pixel art. Dedicated to the public domain under CC0 1.0.\nhttps://creativecommons.org/publicdomain/zero/1.0/\n')
    (PACK / 'README.txt').write_text('These icons are bundled in the FPBCraft Diorama core JAR for clients and servers. No separate pack activation or export is needed.\nThe standalone ZIP can also be enabled as a normal resource pack. Custom replacements can be exported with /diorama-export-web-appearance.\n')
    names = {}
    resolved = {}
    cells = []
    for name, rows in ICONS.items():
        art = pixels(rows)
        resolved[f'fpbcraft:{name}'] = [int.from_bytes(bytes((a, r, g, b)), 'big', signed=True) for r, g, b, a in art]
        png(PACK / f'assets/fpbcraft/textures/waypoint_icons/{name}.png', 11, 11, art)
        names[f'diorama.waypoint.icon.fpbcraft.{name}'] = name.replace('_', ' ').title()
        cells.append(f'<figure><img src="../../resourcepacks/fpbcraft-waypoint-icons/assets/fpbcraft/textures/waypoint_icons/{name}.png" alt=""><figcaption>{name.title()}</figcaption></figure>')
    lang = PACK / 'assets/fpbcraft/lang/en_us.json'
    lang.parent.mkdir(parents=True, exist_ok=True)
    lang.write_text(json.dumps(names, indent=2) + '\n')
    # Original cog at larger integer scale makes the pack recognisable in Minecraft's list.
    art = pixels(ICONS['cog'])
    png(PACK / 'pack.png', 132, 132, [art[(y // 12) * 11 + x // 12] for y in range(132) for x in range(132)])
    out = ROOT / 'docs/waypoint-icons/fpbcraft-waypoint-icons.zip'
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(PACK.rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, path.read_bytes())
    resources = ROOT / 'src/main/resources'
    shutil.copytree(PACK / 'assets/fpbcraft', resources / 'assets/fpbcraft', dirs_exist_ok=True)
    (resources / 'assets/fpbcraft/waypoint-icons.json').write_text(json.dumps(resolved, separators=(',', ':')) + '\n')
    shutil.copy2(PACK / 'LICENSE.txt', resources / 'assets/fpbcraft/WAYPOINT_ICONS_LICENSE.txt')
    bundled_pack = resources / 'diorama/packs/fpbcraft-waypoint-icons.zip'
    bundled_pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(out, bundled_pack)
    (out.parent / 'fpbcraft-preview.html').write_text('<!doctype html><meta charset="utf-8"><title>FPBCraft waypoint icons</title><style>body{background:#111a22;color:#f0e9d4;font:16px system-ui;margin:36px}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:16px}figure{margin:0;padding:20px;text-align:center;background:#1c2b35;border-radius:8px}img{width:66px;height:66px;image-rendering:pixelated}figcaption{margin-top:12px}</style><h1>Workshop waypoint icons</h1><p>24 original 11 × 11 pixel icons · CC0</p><main>' + ''.join(cells) + '</main>')
    print(f'Built {len(ICONS)} icons: {out}')


if __name__ == '__main__':
    main()
