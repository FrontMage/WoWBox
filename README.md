<p align="center"><img src="branding/wow-box-mark.svg" width="128" alt="WoW Box" /></p>

# WoW Box

基于 [Winlator](https://github.com/brunodev85/winlator) 的 Android 应用，为 Battle.net 和 World of Warcraft 提供预先配置的 Wine/FEX 运行环境。

**日常使用不需要 AI 助手、Codex、ADB 或电脑。** 下载 APK 后，按照下面的 Android 界面操作即可。

[下载 v0.1.0 预览版](https://github.com/FrontMage/WoWBox/releases/tag/v0.1.0) · [构建与运行资源](docs/BUILD.md) · [第三方软件和源码](THIRD_PARTY_NOTICES.md)

## 设备与版本

- APK 包名为 `com.winlator.llm`，面向 ARM64 Android；最低安装 API 为 Android 8.0。
- 内置图形栈面向 Qualcomm Adreno。现有开发版本的验证不能推定所有设备、游戏版本都兼容；Mali、Xclipse 和较新的 Adreno 8xx 没有本公开版的通用支持承诺。
- RP5 的 D3D12 GPU hang，以及长时间稳定性仍有未解决问题。公开版采用共享配置，不包含 Thor、RedMagic、UU 的设备实验配置。
- 本预览版完成独立构建、签名和资源检查。公开版使用自由字体和 Wine 内置组件，尚未完成这一配置的新设备游戏验收。
- 首次安装会展开运行资源，预留至少 5 GB 可用空间，并另外准备游戏客户端及更新所需空间。

## 1. 安装并等待资源初始化

1. 从上面的 Release 下载 **WoWBox-v0.1.0-arm64.apk**。
2. 在 Android 文件管理器打开 APK，按系统提示允许该来源安装应用。
3. 启动 **WoW Box**，授予存储权限。
4. 等待 **Box Install** 完成并进入首页。应用会自动创建运行环境、安装 Wine/FEX 和图形资源，无需另建容器或复制 DLL。
5. 安装失败时检查权限和空间，再点击 **Retry**。

APK 已包含日常运行资源；Release 里的 runtime-assets 压缩包供源码构建使用，普通用户不必另下载。

## 2. 放置官方 Battle.net 安装器

WoW Box 不包含 Battle.net 安装器或游戏文件。

1. 点击首页 **Get Battle.net**，在 Android 浏览器打开暴雪官方下载页。
2. 下载官方 **Windows** 安装器。
3. 在文件管理器建立 `内部存储/Download/FrontMage-BNet` 文件夹。
4. 将安装器放进去，并精确命名为 **Battle.net-Setup.exe**。

完整路径应是：

```text
/storage/emulated/0/Download/FrontMage-BNet/Battle.net-Setup.exe
```

不要保留下载文件名中的 `(1)` 后缀，也不要出现 `.exe.exe`。该目录映射为 Windows 的 `D:`，安装器对应 `D:\Battle.net-Setup.exe`。

5. 回到 WoW Box 首页，出现 **Install Battle.net** 后点击它。
6. 按安装向导完成安装，保留 Battle.net 默认安装目录。
7. 安装结束后，按 Android 返回键打开运行菜单，选择 **Exit** 返回首页。

## 3. 登录与浏览器返回

1. 点击首页 **Battle.net** 启动客户端，按提示选择地区并登录。
2. 若打开 Android 浏览器进行网页认证，在浏览器完成登录或验证。
3. 通过 Android 最近任务回到 WoW Box。
4. 画面中央出现 **▶** 时，点击它恢复 Windows 会话；也可以按键盘 Enter 或手柄 A / Start。
5. 等待 Battle.net 完成登录。

切到后台时 Windows 会话会暂停；返回后的 ▶ 是手工恢复入口。

网页没有打开时，检查首页菜单 **Settings → XServer → Open web links in Android browser**，确认该项启用、Android 已安装浏览器，并保存设置后重启会话。

## 4. 安装和启动 WoW

1. 在 Battle.net 内选择 WoW 及所需版本，按客户端提示下载和更新。
2. 安装位置可以选择 `D:\World of Warcraft`，方便在 Android 文件管理器访问。对应目录是：

   ```text
   内部存储/Download/FrontMage-BNet/World of Warcraft
   ```

3. 已有游戏目录时，使用 Battle.net 的查找已有游戏入口，选择对应 Windows 路径。
4. 更新或扫描完成后，在 Battle.net 内点击 **Play / 进入游戏**。
5. 此后的日常入口是 **WoW Box → Battle.net → 进入游戏**。

首页负责安装和打开 Battle.net；游戏版本、更新、登录和启动由 Battle.net 管理。本版不包含纯 ARM64 WoW Beta 的新 copied-syscall 补丁。

## 5. 触屏、手柄和键鼠

运行中按 Android 返回键，或从左边缘向内滑动，打开运行菜单。

| 操作 | 默认触屏手势 |
| --- | --- |
| 左键单击 | 单指短点 |
| 右键拖动 / 转动视角 | 单指移动，松开结束 |
| 左键拖动 | 双指一起移动，松开结束 |
| 滚轮 / 镜头缩放 | 双指捏合或展开，松开后发送滚轮动作 |
| 右键单击 | 按住实体手柄 LB，再单指短点 |

**Touchpad Help** 显示当前模式的说明。

- **软键盘**：运行菜单 → **Keyboard**，使用 Android 输入法。
- **屏幕按钮**：运行菜单 → **Input Controls**，选择 **Virtual Gamepad**，勾选 **Show Touchscreen Controls** 并确认。
- **实体手柄**：先在 Android 用蓝牙或 USB 连接。输入桥支持 XInput；游戏端的操作布局仍需在 WoW 或兼容插件中配置。
- **自定义按键**：首页菜单 → **Input Controls**，创建配置后使用 **Controls Editor** / **External Controllers** 编辑，再在运行菜单选择该配置。
- **外接键鼠**：连接 Android 后，左右键、滚轮和键盘按键可直接输入运行界面。

触屏手势和性能 HUD 的开关位于首页 **Settings → XServer**。WoW 插件请自行取得兼容版本，退出会话后放入对应游戏分支的 `Interface/AddOns/`。

## 6. 可选帧生成

帧生成默认关闭，普通使用无需启用。这里的 AI Frame Generation 是可选图形功能，使用应用不需要 AI 助手。

需要尝试时，从自己合法拥有的 Lossless Scaling 安装中取得兼容 `Lossless.dll`：

1. 打开 **Settings → AI Frame Generation (LSFG)**。
2. 点击 **Import licensed Lossless.dll**，选取该文件。
3. 页面显示 **Ready** 后，启用 **Enable AI frame generation**。
4. 保存、退出并重新启动会话。默认启用参数为 2x、Flow scale 0.80、Performance mode 开启。

DLL 不随 APK 分发。导入校验可能不接受不同版本；出现启动或画面问题时关闭开关并重启会话。

## 7. 退出、恢复和更新

- 先退出游戏，再在运行菜单选择 **Exit**。
- Battle.net 无响应时使用 **Exit → 首页 Battle.net** 重新打开。
- 后续公开版更新应在退出会话后安装同包名、同签名的 APK。
- 安装不同签名的自行构建包前先备份数据；不要通过普通更新操作卸载应用或清除应用数据。
- 从旧内部开发包迁移时，运行资源身份不同可能触发数据保护检查。不要强制重建已有前缀；先备份再处理迁移。
- 游戏放在 `D:` 时，可在退出会话后用 Android 文件管理器备份整个目录，尤其是 `WTF` 和 `Interface/AddOns`。

## 8. 导出故障信息

首页 **Debug → Export Snapshot** 导出配置摘要至：

```text
内部存储/Download/WinlatorBox/frontmage-bnet-arm64/exports/snapshot-时间.json
```

报告设备型号、Android / APK / 游戏版本、复现步骤和该摘要。它不是完整 Wine 日志。

如需日志，在 **Settings** 启用 **Enable Wine debug** 并选择日志配置，保存后重启；运行菜单 **Logs** 显示已启用的输出，复现结束后关闭详细日志。

标准 APK 已预选图形驱动，首页没有通用驱动切换菜单。请报告黑屏、条纹或 GPU 错误的设备与版本，不要混用其他设备的 DLL 或驱动包。

## 来源与许可证

应用基于 BrunoSX 的 Winlator；本仓库保留其 MIT 许可证。Wine、FEX、Mesa、DXVK、vkd3d-proton、PRoot、OpenXR、LSFG、字体等组件各自保留原许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

公开包使用 Wine 字体、Source Han Sans CN 和 DejaVu；不分发 Microsoft 字体、原生 Microsoft 可选组件、Battle.net 安装器、游戏数据或 `Lossless.dll`。
