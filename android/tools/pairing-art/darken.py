# Re-composite white-background pairing art onto the dark card colour.
# Only white connected to the frame border is background, so white parts of the product stay.
import sys, subprocess, numpy as np
from scipy import ndimage
from PIL import Image
BG = np.array([0x1C, 0x1C, 0x1E], np.float32)
LO, HI = 236.0, 252.0   # min channel >= HI -> pure background, <= LO -> product

S = 4  # supersampling for an anti-aliased matte

def darken(rgb, thr=244, erode=1.0):
    f = rgb.astype(np.float32)
    h, w = f.shape[:2]
    m = f.min(axis=2)
    lab, _ = ndimage.label(m >= thr)
    border = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
    bg = np.isin(lab, border[border > 0])
    # Sub-pixel matte: upsample the "how white" field, threshold it, then area-average back down.
    big = ndimage.zoom(m, S, order=3, mode="nearest")
    bigbg = ndimage.zoom(bg.astype(np.float32), S, order=0) > 0.5
    lab2, _ = ndimage.label((big >= thr) & bigbg)
    b2 = np.unique(np.concatenate([lab2[0], lab2[-1], lab2[:, 0], lab2[:, -1]]))
    prod = ~np.isin(lab2, b2[b2 > 0])
    prod = ndimage.binary_erosion(prod, iterations=int(erode * S))  # drop the white-blended rim
    alpha = prod.reshape(h, S, w, S).mean(axis=(1, 3))[..., None]
    core = alpha[..., 0] > 0.999
    _, idx = ndimage.distance_transform_edt(~core, return_indices=True)
    colour = f[idx[0], idx[1]]  # edge colour from the nearest solid product pixel
    out = colour * alpha + BG * (1 - alpha)
    return out.round().astype(np.uint8)

mode, src, dst = sys.argv[1:4]
if mode == "png":
    Image.fromarray(darken(np.array(Image.open(src).convert("RGB")))).save(dst, optimize=True)
else:
    w, h = 1050, 354
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", src, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", "30", "-i", "-",
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-crf", "20", "-preset", "slow",
        "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
        "-movflags", "+faststart", "-an", dst], stdin=subprocess.PIPE)
    n = 0
    while True:
        buf = dec.stdout.read(w * h * 3)
        if len(buf) < w * h * 3: break
        enc.stdin.write(darken(np.frombuffer(buf, np.uint8).reshape(h, w, 3)).tobytes()); n += 1
    enc.stdin.close(); enc.wait(); dec.wait(); print("frames", n)
