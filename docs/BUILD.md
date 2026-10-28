# 构建 WoW Box

需要 JDK 17、Android SDK、Python 3 和 curl。SDK Platform、NDK 和 CMake 版本以项目的 Gradle 配置为准。

```bash
git clone https://github.com/FrontMage/WoWBox.git
cd WoWBox
# 在 local.properties 中设置 sdk.dir，或配置 ANDROID_HOME。
./scripts/build-apk.sh
```

脚本按 `runtime-assets.json` 的版本和地址匿名下载 runtime-assets 包，并验证整包及逐文件 SHA-256，不需要 GitHub 账号或 AI。已有资源且哈希匹配时仅检查，不再下载。预发布阶段的资源包尚未公开时，维护者须先将对应已验证资源准备到本地，再执行构建；不能用旧版清单冒充新版本。

```bash
python3 scripts/fetch-runtime-assets.py --verify
./gradlew :app:testDebugUnitTest
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。公开预览构建使用普通 debug 变体，`UU_PROBE_BUILD`、`AGENT_DRIVE_PROBE_BUILD`、`MINIMAL_ASSETS_BUILD` 和 `NATIVE_ARM_BOX` 全部为 false；构建命令不会安装到设备。

发布 APK 使用维护者的现有 Android debug 签名证书。自行构建会使用自己的签名，不能直接覆盖不同签名的安装版本。密钥不在仓库中。

`runtime-assets.json` 固定构建资源版本与校验值；`managed_assets.json` / `box_spec.json` 固定运行时层及标准候选。公开资源使用可分发字体和 Wine 内置 Windows 可选组件；资源或 Wine 版本变化时须重新验证字体登记与许可，不沿用旧版的通过结果。

公开源码是当前应用的独立快照，旧私有仓库历史、设备配置、测试会话、凭证和游戏前缀不在其中。源代码构建期间生成的缓存、APK 和本地 SDK 配置均被 Git 忽略。

## Wine / FEX 等运行组件源码

Release 另附 `WoWBox-v0.2.0-preview-runtime-sources.tar.gz`，提供本版 Wine/FEX 的对应源码、生成构建文件和源代码来源记录；本仓库保留输入桥、LSFG 改动及辅助程序源码/构建脚本。上游组件版本与许可证见 THIRD_PARTY_NOTICES.md。

运行时来源与固定版本在 Release 说明和资源清单中记录。构建与签名、升级兼容性及设备测试须分别验证；成功构建不能代替游戏运行验收。
