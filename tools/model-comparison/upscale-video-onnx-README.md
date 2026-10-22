---
license: bsd-3-clause
tags:
- onnx
- video-super-resolution
- frameshift
---

# FrameShift — Upscale Video models

ONNX models used exclusively by the **Upscale Video** action of
[FrameShift](https://github.com/Gaurox/FrameShift), a local-first Windows media utility.
Models are downloaded on demand and verified against the pinned SHA-256 values below. They are not
bundled in the FrameShift installer or source repository.

## Files

| File | Model | Size | SHA-256 | License |
|---|---|---:|---|---|
| `realesr_general_x4v3.onnx` | Real-ESRGAN General v3 | 4,867,430 bytes | `DBB0561758E0727C76B7BF6B539A988D3B3050D51F01DCF60C842DE6E6ADD349` | BSD-3-Clause |
| `realesr_animevideov3.onnx` | Real-ESRGAN AnimeVideo v3 | 2,493,430 bytes | `6CB9454787F6B0948CB1C25BE0C7DA797AD83EC2696FC005CA4C967217B9CD77` | BSD-3-Clause |
| `realesr_animevideov3_x2.onnx` | Real-ESRGAN AnimeVideo v3 (FrameShift x2 execution variant) | 2,493,683 bytes | `B3C1B93492C7BE8CA2C7B2EEADCA311BF1ADC709C296AA3AD14ABFA7890E4A44` | BSD-3-Clause |
| `realesr_animevideov3_x3.onnx` | Real-ESRGAN AnimeVideo v3 (FrameShift x3 execution variant) | 2,493,683 bytes | `D3268B927AD1AEA8DBE24790CB6044714FFE3CA7D70C3A1D204063CDBA994B92` | BSD-3-Clause |
| `realesrgan_x4plus_fp16.onnx` | Real-ESRGAN x4plus (quality) | 33,748,852 bytes | `37651C96722D0156AAEE27404F31FBB62E93B4AB4AB9E9DB07DA2200500232AC` | BSD-3-Clause |

## Runtime contract

- RGB float32 NCHW input `input`, normalized to `[0, 1]`.
- Float32 NCHW output `output`; General v3 and AnimeVideo v3 x4 are native x4, while the
  FrameShift Anime x2/x3 variants return x2/x3 directly from the ONNX graph.
- Dynamic height and width. FrameShift uses overlapping adaptive tiles and reuses one ONNX session
  for every frame, with DirectML first and CPU fallback.
- FrameShift keeps one visible **AnimeVideo v3** picker entry and automatically routes x2/x3
  requests to `realesr_animevideov3_x2.onnx` / `realesr_animevideov3_x3.onnx`; x4 keeps
  `realesr_animevideov3.onnx`.
- Processing is frame-by-frame; temporally difficult or grainy material can retain some flicker.

## Sources, exports, and provenance

All three models originate from the official
[Real-ESRGAN project](https://github.com/xinntao/Real-ESRGAN), Copyright © 2021 Xintao Wang.

- `realesr_general_x4v3.onnx`: exported from official `realesr-general-x4v3.pth` release weights,
  SRVGGNetCompact with 32 convolution blocks, opset 17, dynamic spatial axes.
- `realesr_animevideov3.onnx`: exported from official `realesr-animevideov3.pth` release weights,
  SRVGGNetCompact with 16 convolution blocks, opset 17, dynamic spatial axes.
- `realesr_animevideov3_x2.onnx` / `realesr_animevideov3_x3.onnx`: derived from the same official
  AnimeVideo v3 ONNX export by appending a final in-graph x0.5 / x0.75 linear resize so FrameShift
  can skip the previous CPU-side downscale step for anime x2/x3 requests.
- `realesrgan_x4plus_fp16.onnx`: Real-ESRGAN x4plus quality model with float32 I/O.

The exports were checked with ONNX validation, CPU inference, DirectML inference, exact x4 output
dimensions, finite outputs, and CPU/DirectML numerical comparison.

All files are distributed under the Real-ESRGAN BSD-3-Clause terms. See
`LICENSE-Real-ESRGAN-BSD-3-Clause.txt`; it must remain alongside any redistribution of these model
binaries.
