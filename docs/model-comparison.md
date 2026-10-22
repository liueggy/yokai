# 手机模型对比

2026-10-21：以下模型安装在测试版 `eu.kanade.tachiyomi.debugYokai` 的应用存储中，未打入 APK，也未修改正式版。

| 模型 | 权重大小 | 说明 |
|---|---:|---|
| 内置 Real-ESRGAN animevideov3 | 2.49 MB | 动漫轻量基准 |
| RealESRGAN-General-x4v3 | 4.87 MB | 通用图像轻量模型，用于比较纹理和噪点处理 |
| RealESRGAN-Anime-6B | 17.94 MB | 动漫图像模型，用于比较漫画线条和细节，速度需实测 |

三款均以 4x 原生输出缩至阅读器的 2x 输出。预览测试显示从模型加载/处理开始到预览生成的耗时，不包含源图读取；包含等待工作线程、解码、模型冷启动、推理及结果处理。局部预览为中心裁剪，特殊格式可能先采样，不代表整页耗时或完整分辨率效果。每次切换模型会重新加载；同一模型重复测试可比较热运行。

新增模型来源为 FrameShift 维护者导出的 ONNX；其说明指向官方 Real-ESRGAN 权重。许可 BSD-3-Clause，版权 Xintao Wang (2021)。下载按固定仓库修订锁定，并核对发布的 SHA-256；许可文件随模型保存在手机模型目录。

- [官方 Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)
- [图像模型来源和许可](https://huggingface.co/Gaurox/frameshift-models/blob/b4f8fa610edff25e059959fb478bd3879e26f7a7/upscale-image-onnx/README.md)
- [通用轻量模型来源和许可](https://huggingface.co/Gaurox/frameshift-models/blob/b4f8fa610edff25e059959fb478bd3879e26f7a7/upscale-video-onnx/README.md)

## 校验记录

电脑端验证 ONNX 模型结构、float32 RGB NCHW 输入、动态高宽、16×16 / 24×32 / 128×128 三种输入、正确 4x 输出以及有限数值；不是直接将浮点半精度模型冒充兼容格式。手机端结果另记。

- RealESRGAN-General-x4v3: SHA-256 `dbb0561758e0727c76b7bf6b539a988d3b3050d51f01dcf60c842de6e6add349`, 4867430 bytes. [固定下载源](https://huggingface.co/Gaurox/frameshift-models/resolve/b4f8fa610edff25e059959fb478bd3879e26f7a7/upscale-video-onnx/realesr_general_x4v3.onnx)
- RealESRGAN-Anime-6B: SHA-256 `1bccabc8e813ae287057c6dd342458c355e4505abe36b5e0b2b94807d39d40df`, 17939940 bytes. [固定下载源](https://huggingface.co/Gaurox/frameshift-models/resolve/b4f8fa610edff25e059959fb478bd3879e26f7a7/upscale-image-onnx/realesrgan_x4plus_anime_6b.onnx)

## 本机实测

Android 16，PGT-AN10，手机 CPU ONNX Runtime，当前章节同一页的中心局部；新选模型后的单次测试（包含模型加载等处理），非统计基准，也不能推算完整长条耗时：

| 模型 | 预览结果 | 面板显示耗时 |
|---|---|---:|
| RealESRGAN-Anime-6B | 成功生成，可切换原图/增强 | 8.53 秒 |
| RealESRGAN-General-x4v3 | 成功生成，可切换原图/增强 | 1.89 秒 |

证据：`build/device-check/anime6b-timing.png`、`general-timing.png`、`model-list.png`。手机目前选择通用轻量模型，保留内置模型供用户切换。

带计时 UI 的应用通过 33 项增强测试，assembleStandardDebug、apksigner 和 zipalign 检查通过。日志 `build/device-check/model-timing-build.log`。新增模型没有重新分发进应用资产，属于设备上的可选导入；两款模型在手机端均已实际运行预览。

## 2026-10-21 — waifu2x 和简短名称

列表名称已精简为：动漫轻量（内置 animevideov3）、通用轻量（General v3）、动漫精细（Anime 6B）、waifu2x。

新增 waifu2x 使用官方 `upconv_7/art/noise1_scale2.0x_model.json`，一级降噪＋原生 2x。固定源码修订 `cc385f97a9debfe611316aabfd5d8bb30ba2dbeb`，MIT，Copyright (C) 2015 nagadomi。来源：[官方权重](https://github.com/nagadomi/waifu2x/blob/cc385f97a9debfe611316aabfd5d8bb30ba2dbeb/models/upconv_7/art/noise1_scale2.0x_model.json)。转换保留原权重、LeakyReLU 0.1 和官方边缘夹取补边语义（7 像素 replicate padding），输出裁到 0..1。

导出 ONNX 为 2,214,086 bytes，SHA-256 `ab758dfa2e5a05ab55c7d3961acd2146a49f0938300d790c2b3a65af2b2d7fef`。电脑端通过 ONNX checker，以及 1×1、16×16、24×32、128×128、256×256 输入推理，输出尺寸均为 2x；与转换用 PyTorch 网络最大绝对误差不超过 2.93e-6。这是导出数值一致性检查，不代表已与原 Torch7 执行器做逐像素对照。

模型、元信息和 MIT 许可已通过 ADB 传入测试版手机模型目录，手机端 SHA-256 核对一致。此前两款导入模型仅修改显示名称，权重及选择标识不变。新版 APK 已成功安装并启动；33 项增强测试及构建通过，日志 `build/device-check/waifu-shortnames-build.log`。继续最后的 UI 检查时设备断开，waifu2x 手机推理、四项名称实际显示仍待复测。

## waifu2x 实机复测完成

重新连接手机后，已确认模型列表显示“动漫轻量、通用轻量、动漫精细、waifu2x”。选择 waifu2x 后当前章节的局部预览成功生成，单次面板耗时 1.08 秒（包含加载和局部处理）。原图/增强切换已操作验证。证据 `build/device-check/waifu-device-result.png`、`waifu-device-result.xml`、`waifu-device-original.png`。此前“待复测”状态至此被本次成功结果更新；未据此宣称所有格式/整页/全部阅读模式已验收。
