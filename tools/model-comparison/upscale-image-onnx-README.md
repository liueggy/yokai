---
license: other
tags:
- onnx
- image-super-resolution
- frameshift
---

# FrameShift — Upscale Image models

ONNX models used exclusively by the **Upscale Image** action of
[FrameShift](https://github.com/Gaurox/FrameShift), a local-first Windows media utility.
Models are downloaded on demand and verified against the pinned SHA-256 values below. They are not
bundled in the FrameShift installer or source repository.

## Files

| File | Model | Size | SHA-256 | License |
|---|---|---:|---|---|
| `realesrgan_x4plus_fp16.onnx` | Real-ESRGAN x4plus | 33,748,852 bytes | `37651C96722D0156AAEE27404F31FBB62E93B4AB4AB9E9DB07DA2200500232AC` | BSD-3-Clause |
| `realesrgan_x4plus_anime_6b.onnx` | Real-ESRGAN Anime 6B | 17,939,940 bytes | `1BCCABC8E813AE287057C6DD342458C355E4505ABE36B5E0B2B94807D39D40DF` | BSD-3-Clause |
| `swin2sr_realworld_x4.onnx` | Swin2SR real-world x4 | 53,827,735 bytes | `987D88B356554161CBB8F67B7A8F4162CAD6DC147839C344E3D5142140F25D6F` | Apache-2.0 |

## Runtime contract

- Real-ESRGAN: RGB float32 NCHW input `input`, normalized to `[0, 1]`; float32 x4 output `output`.
- Swin2SR: RGB float32 NCHW input `pixel_values`; float32 x4 output `reconstruction`.
- Swin2SR requires width and height multiples of 8. FrameShift pads tiles and crops the result.
- FrameShift runs ONNX Runtime with DirectML first and falls back to CPU. Native x4 output can be
  downsampled to x2, x3, or an aspect-locked target size.

## Sources and provenance

- **Real-ESRGAN x4plus / Anime 6B:** official project
  <https://github.com/xinntao/Real-ESRGAN>, Copyright © 2021 Xintao Wang. The Anime 6B model was
  exported to ONNX from the official `RealESRGAN_x4plus_anime_6B.pth` weights. See
  `LICENSE-Real-ESRGAN-BSD-3-Clause.txt`.
- **Swin2SR:** <https://github.com/mv-lab/swin2sr>; base model
  <https://huggingface.co/caidas/swin2SR-realworld-sr-x4-64-bsrgan-psnr>; ONNX conversion from
  <https://huggingface.co/onnx-community/swin2SR-realworld-sr-x4-64-bsrgan-psnr-ONNX>. See
  `LICENSE-Swin2SR-Apache-2.0.txt`.

The license files in this directory apply to their respective models and must remain alongside any
redistribution of the model binaries.
