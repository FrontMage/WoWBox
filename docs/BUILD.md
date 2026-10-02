# 构建 WoW Box

需要 JDK 17、Android SDK、Python 3 和 curl。Gradle 使用 Android Gradle Plugin 7.4.2，SDK Platform 30、NDK 22.1.7171670、CMake 3.22.1。

```bash
git clone https://github.com/FrontMage/WoWBox.git
cd WoWBox
# 在 local.properties 中设置 sdk.dir，或配置 ANDROID_HOME。
./scripts/build-apk.sh
```

脚本从本仓库 v0.1.0 Release 匿名下载 runtime-assets 包并验证整包及逐文件 SHA-256，不需要 GitHub 账号或 AI。已有资源时仅检查，不再下载。

```bash
python3 scripts/fetch-runtime-assets.py --verify
./gradlew :app:testDebugUnitTest
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。公开预览构建使用普通 debug 变体，`UU_PROBE_BUILD`、`AGENT_DRIVE_PROBE_BUILD`、`MINIMAL_ASSETS_BUILD` 全部为 false；不会安装到设备。

发布 APK 使用维护者的现有 Android debug 签名证书。自行构建会使用自己的签名，不能直接覆盖不同签名的安装版本。密钥不在仓库中。

`runtime-assets.json` 固定构建资源版本与校验值；`managed_assets.json` / `box_spec.json` 固定运行时九层及标准候选。资源包已包含自由字体、正确的 GDI/DirectWrite 登记，Windows 可选组件使用 Wine 内置实现。

公开源码是当前应用的独立快照，旧私有仓库历史、设备配置、测试会话、凭证和游戏前缀不在其中。源代码构建期间生成的缓存、APK 和本地 SDK 配置均被 Git 忽略。

## Wine / FEX 等运行组件源码

Release 另附 `WoWBox-v0.1.0-runtime-sources.tar.gz`，提供本版 Wine/FEX 的对应源码、生成构建文件和源代码来源记录；本仓库保留输入桥、LSFG 改动及辅助程序源码/构建脚本。上游组件版本与许可证见 THIRD_PARTY_NOTICES.md。

APK 中的 Wine/FEX/DX/Vulkan DLL 核心仍来自既有共享基线。公开分发只调整字体资源与字体配置，并使用 Wine 内置可选 Windows 组件；没有把后续设备诊断 Wine/FEX 修复并入默认包。
