"""Synthetic protocol artwork for UI tests; no downloaded game assets."""
from functools import lru_cache
import math
import struct
import zlib

GLYPHS = {
    "A": [14,17,17,31,17,17,17], "B": [30,17,17,30,17,17,30],
    "D": [30,17,17,17,17,17,30], "E": [31,16,16,30,16,16,31],
    "H": [17,17,17,31,17,17,17], "I": [31,4,4,4,4,4,31],
    "L": [16,16,16,16,16,16,31], "M": [17,27,21,21,17,17,17],
    "N": [17,25,25,21,19,19,17], "O": [14,17,17,17,17,17,14],
    "R": [30,17,17,30,20,18,17], "T": [31,4,4,4,4,4,4],
    "Y": [17,17,10,4,4,4,4], "&": [12,18,20,8,21,18,13],
    " ": [0]*7,
}


@lru_cache(maxsize=16)
def artwork_png(kind, game_id, revision):
    width, height = {"hero": (640, 400), "poster": (240, 360), "logo": (480, 140), "icon": (96, 96)}[kind]
    pixels = bytearray(width*height*4)
    warm = revision == "two"
    for y in range(height):
        for x in range(width):
            xx, yy = x/width, y/height
            if kind == "logo":
                color = (0, 0, 0, 0)
            else:
                color = (int(22+35*yy), int(56+72*yy), int(88+80*yy), 255)
                if (xx-.76)**2 + ((yy-.28)*height/width)**2 < .006:
                    color = (236, 224, 168, 255)
                ridge = .57 + .08*math.sin(xx*17) + .06*math.sin(xx*29)
                if yy > ridge:
                    color = (19, 43, 55, 255)
                if yy > .72:
                    color = (20, 60+int(22*math.sin(y*.7)**2), 81, 255)
                if warm:
                    color = (min(255, color[2]+25), color[1], color[0], color[3])
            offset = (y*width+x)*4
            pixels[offset:offset+4] = bytes(color)
    if kind in ("poster", "logo"):
        lines = ["MOONLIT", "HARBOR"] if game_id == "game-7" else ["ORBIT &", "BEYOND"]
        scale = 7 if kind == "logo" else 4
        top = (height-16*scale)//2
        for row, line in enumerate(lines):
            left = (width-(len(line)*6-1)*scale)//2
            for index, char in enumerate(line):
                for gy, bits in enumerate(GLYPHS[char]):
                    for gx in range(5):
                        if bits & (1 << (4-gx)):
                            for dy in range(scale):
                                for dx in range(scale):
                                    x, y = left+(index*6+gx)*scale+dx, top+(row*9+gy)*scale+dy
                                    offset = (y*width+x)*4
                                    pixels[offset:offset+4] = bytes((246, 240, 215, 255))
    def chunk(tag, data):
        return struct.pack("!I", len(data))+tag+data+struct.pack("!I", zlib.crc32(tag+data))
    rows = b"".join(b"\0"+pixels[y*width*4:(y+1)*width*4] for y in range(height))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!2I5B", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
