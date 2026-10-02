# WoW Box Code Guide

## Scope

这个目录实现 WoW Box 的核心运行时：

- `BoxSpec`
- `BoxInstaller`
- `BoxRuntime`
- `BoxDebugServer`
- `BoxSessionManager`

它对应的产品形态是：

- 单 box
- 单容器
- 单 `BoxSpec`
- payload-first launcher
- GUI 真路径调试

仓库级说明见：

- [docs/WINLATOR_BOX.md](/Users/xinbiguo/Documents/winlator-mod/docs/WINLATOR_BOX.md)

## Class Map

### `BoxSpec`

[BoxSpec.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/box/BoxSpec.java)

唯一真相源，描述：

- `layers`
- `payload`
- `extraMounts`
- `launch`
- `env`
- `debugServer`

当前默认 spec 资产在：

- [app/src/main/assets/box_spec.json](/Users/xinbiguo/Documents/winlator-mod/app/src/main/assets/box_spec.json)

### `BoxInstaller`

[BoxInstaller.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/box/BoxInstaller.java)

负责固定顺序安装：

1. `base-imagefs`
2. `wine-runtime`
3. `prefix-template`
4. `graphics-wrapper-stack`
5. `gpu-driver-stack`
6. `cpu-emu-stack`
7. `payload`

说明：

- `prefix-template` 会在 `wine-runtime` 后补装
- `http/https payload` 先下载再解包
- `file://` payload 走直接挂载或本地归档解包
- 安装完成后自动配置单容器并写状态

### `BoxRuntime`

[BoxRuntime.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/box/BoxRuntime.java)

负责：

- spec/state/token 读写
- 单容器创建与配置
- env 快照合成
- 挂载点解析
- `.exe` 扫描
- session-aware launch
- debug snapshot 生成

关键约束：

- 权威容器名：`WoW Box`
- 权威 container root：`xuser-box`
- payload 默认挂 `D:`
- runtime env override 独立落盘，不直接改 spec

### `BoxDebugServer`

[BoxDebugServer.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/box/BoxDebugServer.java)

提供局域网调试入口：

- `GET /health`
- `GET /automation/preflight`
- `GET /automation/permissions`
- `GET /automation/foreground`
- `GET /automation/layers/verify`
- `POST /automation/layers/graphics-wrapper-stack/install`
- `GET /automation/frame-generation`
- `POST /automation/frame-generation`
- `POST /automation/layers/frame-generation-stack/install`
- `GET /automation/runtime-overlay`
- `POST /automation/runtime-overlay`

`POST /automation/runtime-overlay` accepts `{"clear":true}` to remove the
registered overlay after the original files have been restored.
- `GET /spec`
- `GET /layers`
- `GET /env`
- `PUT /env`
- `GET /executables`
- `GET /sessions`
- `GET /sessions/current`
- `GET /sessions/current/processes`
- `POST /container/restart`
- `POST /container/stop`
- `POST /launch`
- `GET /debug/snapshot`
- `GET /reconcile`
- `GET /mounts`
- `GET /logs`
- `WS /stream`

注意：

- 读写接口统一要求 `x-box-token`
- `/launch` 是 WoW 和其他 GUI 程序调试的标准入口
- 不要再把 direct wine CLI 视为主启动链
### Automation rules

Agent/runner 自动化必须先跑 `/automation/preflight`，再确认 `/automation/foreground`；权限弹窗和 adb `pm grant`、`am start`、`adb forward` 都属于外部 runner 责任，debug server 不静默授权，也不执行 adb。

不要直接 push DLL 到 `files/imagefs` 后把结果当成有效测试。Box layer 安装会覆盖运行时目录，必须通过 `/automation/layers/verify` 检查 source、installedAt 和关键文件 hash。

目标程序必须通过 `/launch` 的 Wine 真路径启动，并通过 `/sessions/current/processes` 判断目标 exe 是否出现或已经退出。测试完成默认调用 `/container/stop`，只有显式要求保留现场时才跳过。

### Install + Runtime Sync

Box 的标准维护模型是一次 fresh install，然后按 manifest 做增量 runtime sync。不要把反复重装 APK 或手工修补 `files/imagefs` 当成常规迭代路径。

fresh install 只负责安装这些稳定层：

1. `base-imagefs`
2. `prefix-template`
3. active Proton `wine-runtime`
4. `graphics-wrapper-stack`
5. `gpu-driver-stack`
6. `cpu-emu-stack`
7. payload mount/extract state

Proton/Wine 小改动默认走增量 sync，不重装 APK。当前 WoW ARM64EC 调试优先支持 ntdll 级同步，例如把 candidate 的 `ntdll.dll` 或相关 runtime 文件同步进已安装的 active Proton runtime。

runner 应先把文件同步到目标路径，再调用 `POST /automation/runtime-overlay` 登记 manifest。debug server 会现场 hash 目标文件；如果 hash 不匹配或路径不在 `/opt/`、`/home/xuser-box/.wine/` 下，就不会写入 state。

每次 runtime sync 都必须写入 box state 或等价 manifest，至少记录：

- base runtime sha
- overlay generation
- target path
- sha256
- syncedAt

`/automation/preflight` 必须同时验证 base layer 和 overlay hash。只有 base layer、payload、runtime overlay 都匹配 manifest 时，runner/agent 才能把该设备状态当成有效测试基线。

直接 push DLL 到 `files/imagefs` 但没有更新 box state/manifest 的结果不能作为有效测试证据。需要临时诊断时也必须在 session artifact 里标成 diagnostic-only，不能把这种状态 promoted 成 baseline。

仓库内的 stdio MCP 包装在：

- [tools/winlator-box-mcp/server.py](/Users/xinbiguo/Documents/winlator-mod/tools/winlator-box-mcp/server.py)

它只包装 HTTP/WebSocket debug API，暴露 `box_preflight`、`box_foreground`、`box_verify_layers`、`box_launch`、`box_wait_for_events`、`box_processes`、`box_stop_container` 等工具。

### `BoxSessionManager`

[BoxSessionManager.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/box/BoxSessionManager.java)

负责 session 生命周期与日志落盘。

每个 session 目录目前包含：

- `meta.json`
- `spec.json`
- `env.json`
- `mounts.json`
- `box.log`
- `debug-meta.json`
- `guest.log`
- `wrapper.log`
- `dxvk.log`
- `fex.log`
- `summary.json`
- `timeline.json`
- `failures.json`
- `hotloops.json`

这套目录是当前 LLM-friendly 调试的权威数据源。

## Paths

### Private app data

[BoxPaths.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/box/BoxPaths.java)

- `files/box/box-spec.json`
- `files/box/box-state.json`
- `files/box/runtime-env-overrides.json`
- `files/box/debug-token.txt`
- `files/box/sessions/<sessionId>/...`

当前 session 日志特意放在 APK 私有目录，避免污染 `Download`。

### Public storage

- `/storage/emulated/0/Download/WinlatorBox/<boxId>/downloads`
- `/storage/emulated/0/Download/WinlatorBox/<boxId>/payload`
- `/storage/emulated/0/Download/WinlatorBox/<boxId>/exports`

## UI Integration

直接关联的 UI：

- [BoxLoadingFragment.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/BoxLoadingFragment.java)
- [BoxLauncherFragment.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/BoxLauncherFragment.java)
- [BoxDebugFragment.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/BoxDebugFragment.java)

以及真实执行入口：

- [XServerDisplayActivity.java](/Users/xinbiguo/Documents/winlator-mod/app/src/main/java/com/winlator/XServerDisplayActivity.java)

`/launch` 应该始终走 `BoxRuntime -> XServerDisplayActivity`，这样：

- XServer
- 音频
- wrapper/DXVK
- 环境变量组装
- session 日志

都与真实用户启动链一致。

## Testing

### Build and install

```bash
scripts/build-apk-install.sh -f --serial <adb-serial>
```

### Health check

```bash
adb -s <serial> shell "run-as com.winlator.llm cat files/box/debug-token.txt"
adb -s <serial> forward tcp:39090 tcp:39090
curl -H "x-box-token: <token>" http://127.0.0.1:39090/health
```

### GUI debug run

```bash
scripts/run_wow_relay_debug.sh \
  --serial <adb-serial> \
  --profile baseline \
  --duration 20 \
  --pull
```

这个脚本会通过 `/launch` 触发 GUI 真路径启动，并把结构化结果整理到：

- `out/gui-debug-sessions/<sessionId>/`

### Current WoW debugging rule

WoW ARM64 调试默认遵守这几个约束：

- 优先 GUI 真路径
- session 日志优先于 adb logcat
- 低扰动采样优先于常开高强度 relay
- 新结论必须能在结构化 session 结果里复现

相关文档：

- [docs/issues/DXVK_WOW_ARM64_NO_FIRST_FRAME_REPORT.md](/Users/xinbiguo/Documents/winlator-mod/docs/issues/DXVK_WOW_ARM64_NO_FIRST_FRAME_REPORT.md)
