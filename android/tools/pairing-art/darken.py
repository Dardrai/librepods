# Re-composite white-background pairing art onto the dark card colour.
# Only white connected to the frame border is background, so white parts of the product stay.
import contextlib, sys, subprocess, numpy as np
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

def darken_video(src, dst):
    w, h = 1050, 354
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", src, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    enc = None
    try:
        enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", "30", "-i", "-",
            "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-crf", "20", "-preset", "slow",
            "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
            "-movflags", "+faststart", "-an", dst], stdin=subprocess.PIPE)
        n = 0
        frame_size = w * h * 3
        while True:
            buf = dec.stdout.read(frame_size)
            if not buf: break
            while len(buf) < frame_size:
                chunk = dec.stdout.read(frame_size - len(buf))
                if not chunk: raise ValueError("Decoded video ends with an incomplete RGB frame")
                buf += chunk
            enc.stdin.write(darken(np.frombuffer(buf, np.uint8).reshape(h, w, 3)).tobytes())
            n += 1
        code = dec.wait()
        if code: raise subprocess.CalledProcessError(code, dec.args)
        if not n: raise ValueError("Decoded video contains no frames")
        enc.stdin.close()
        code = enc.wait()
        if code: raise subprocess.CalledProcessError(code, enc.args)
        return n
    finally:
        for process in (dec, enc):
            if process is not None and process.poll() is None:
                process.kill()
        for stream in (dec.stdout, enc.stdin if enc is not None else None):
            if stream is not None:
                with contextlib.suppress(OSError): stream.close()
        for process in (dec, enc):
            if process is not None: process.wait()

def main():
    mode, src, dst = sys.argv[1:4]
    if mode == "png":
        Image.fromarray(darken(np.array(Image.open(src).convert("RGB")))).save(dst, optimize=True)
    else:
        print("frames", darken_video(src, dst))

if __name__ == "__main__":
    main()
