# WoW Box v0.3.0

标准 Android 包 `com.winlator.llm`，versionCode 19；采用与已有公开版相同的签名。安装包包含日常运行资源，不包含战网安装器、游戏文件、账号数据或 `Lossless.dll`。

## 变化

- 新增 Vulkan 宿主合成路径，并为 D3D11/DXVK 与 D3D12/VKD3D 提供 `d3d11.cachedDynamicResources = vic` 和 `force_host_cached` 默认值。显式设置和运行时覆盖优先于默认值。
- 红魔 NX809J / SM8850 的新安装及符合原版默认条件的既有安装采用 Turnip Gen8 V39；明确选择的其他驱动或图形环境保持优先。普通图形栈和其他设备的行为以实际设备及安装状态为准。
- 红魔测试设备的战网启动增加 `--js-flags=--no-incremental-marking` 作为 CEF renderer 不稳定的临时缓解，已有显式 JS flags 优先。它不保证消除闪退。

## 验证和限制

- 红魔上的**隔离诊断包**经用户确认：V39 使此前战网登录页的彩色条纹消失；V39、Vulkan 宿主合成及两个缓存默认值共同启用时，WoW Forever 1.60.1.70862 的一个 D3D12 场景黑块消失。这是具体设备、场景的观察，不能单独归因某个改动，也不代表 D3D11、其他游戏或长期稳定性通过。
- 用户随后仍报告战网和 WoW 闪退，日志曾记录 CEF renderer 在启用止血参数后重启；游戏的一次访问异常和 Box 退出几乎同时发生，根因尚未确认。CPU 频率波动伴随卡顿的问题也未完成归因。
- 本版沿用的 Android Wine 运行时未包含后续 `RtlWaitOnAddress` 回移补丁；该补丁只有离线候选，未完成受管打包和 Android 验证，不能称作本版修复。
- 在同一红魔上，标准包 0.2 测试副本升级到最终 0.3 APK 后，spec、state、自定义 DXVK 覆盖及测试文件保持一致；9/9 层检查通过，TestD3D 进程实际映射 V39 并在 20 秒绘制 1,206 帧。前一候选还通过驱动缓存生成后的再次启动，并在模拟 V39 资源冲突后校验并回退原驱动；最终 APK 的图形按钮也验证了后台检查和启动。这些是单设备短时测试，不代表正式包的 WoW 世界、长期使用或其他旧版升级均已验收。
- 同一红魔上，最终 APK 的全新安装自动选择并校验 V39，九层通过；一次中性 D3D9 测试在 20 秒绘制 1,151 帧。其他机型和正式包的游戏验证仍未完成；隔离诊断包的结果不代表最终公开 APK 在所有场景均已通过。
- 继承的 imagefs/图形组件以及 V39 驱动的确切对应源码、构建补丁和完整许可来源尚未全部核实；`runtime-sources` 并不包含所有对应源码。详见 [第三方软件说明](https://github.com/FrontMage/WoWBox/blob/v0.3.0/THIRD_PARTY_NOTICES.md)。

## 构建资源

`WoWBox-v0.3.0-runtime-assets.tar.gz` 与 `runtime-assets.json` 固定构建资源；`WoWBox-v0.3.0-runtime-sources.tar.gz` 仅提供已收集的源码。安装、升级和源码构建步骤见 [使用指南](https://github.com/FrontMage/WoWBox/blob/v0.3.0/README.md) 与 [构建说明](https://github.com/FrontMage/WoWBox/blob/v0.3.0/docs/BUILD.md)。
