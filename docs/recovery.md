# 本地开发恢复说明

最终源码分支：feature/local-super-resolution。

JDK 21、Android SDK 配置完成后，运行 Gradle 对应构建任务。local.properties、构建缓存、私有测试记录与APK均不纳入源码仓库。内置 RealESRGAN ONNX 已在 app/src/main/assets/models 中提交，导入模型仍可从手机选择。

模型转换与下载脚本保留在 tools/model-comparison，权重来源和许可见 docs/model-comparison.md。部分脚本保留原开发环境路径，恢复时需调整输出目录与Python环境。

验证记录：42项增强单元测试通过；优化构建与手机安装已完成。详见 docs/reading-wait-reduction.md、docs/reading-enhancement-optimization.md。完整项目Lint仍有旧问题，不能视为全项目无告警。

本机优化包使用开发签名兼容已安装测试包，并非上游官方发布签名；清除本机环境后需要相同签名密钥才能覆盖安装，或先导出应用备份再迁移。
