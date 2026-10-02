# WoW Box LSFG-VK Android layer

This directory pins the open-source Android Vulkan layer used by WoW Box.
It does not contain or build `Lossless.dll`.

- Source: `https://github.com/GameNative/lsfg-vk-android.git`
- Commit: `feb7899a6d0f419e27bf47102b6df8ff17e50122`
- License: MIT (copied into the runtime archive from the pinned source)
- Toolchain: Android NDK `27.0.12077973`, API 26, `arm64-v8a`

`wowbox-allowlist-fail-open.patch` makes shader loading lazy, allows only
`Wow.exe`, `WowClassic.exe`, and `TestD3D.exe`, and converts LSFG
initialization/present failures into ordinary Vulkan pass-through. It also
replaces the upstream wall-clock build stamp and strips the ELF build-id so
the managed binary is reproducible across temporary source directories.

`wowbox-route-isolation.patch` additionally:

- accepts only allowlisted DXVK/vkd3d `VkApplicationInfo` routes;
- stores downstream dispatch state per Vulkan instance and device;
- keeps Battle.net, Agent, CEF and unknown engines on direct pass-through;
- observes ordinary successful Presents and selects one real route after
  three contiguous frames;
- defers LSFG/AHardwareBuffer context creation until that route is selected;
- releases a stale/destroyed route so another active swapchain can take over.

`wowbox-present-route-promotion.patch` completes the isolation by leaving
every first swapchain unchanged. After three sustained successful ordinary
Presents identify the real engine/device route, the layer requests one
standard out-of-date recreation and applies LSFG swapchain requirements only
to that selected route. A failed selected-route setup retries the unchanged
swapchain.

Run `scripts/build-lsfg-vk-android.sh` to rebuild the managed asset.
Before the Android build, the script runs host tests for the application and
engine allowlist, sustained-Present selection/failover, and per-instance,
per-device, queue, swapchain and command-buffer dispatch ownership. The pinned
build is reproducible and the script rejects any unexpected result:

- `liblsfg-vk-layer.so` SHA-256:
  `8c31f59a8be33b2da87013ca3076ddb2bd2e2819fa25b131d67b9ae6d385d25e`
- managed archive SHA-256:
  `bda4ecddfd5af21f786d51a642ee0196c56879088725feff9407a88e33e865f8`
