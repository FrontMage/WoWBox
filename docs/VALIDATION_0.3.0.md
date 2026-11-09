# WoW Box 0.3.0 validation snapshot

- Standard package `com.winlator.llm`, version code 19; candidate APK SHA-256 `4c76edcdbd20178f6d86a94f5a40efcfb22e84d332ab7852d59763c1fd58e622`.
- On a RedMagic NX809J / SM8850, a deliberately isolated 0.2 test install was upgraded to this exact APK. Four checked files (spec, state, runtime environment overrides and a private test file) retained their hashes. Preflight was ready, all nine managed layers matched and the runtime overlay was inactive.
- A GUI-path neutral D3D9 probe loaded the V39 driver in the actual process and rendered 1,206 frames in 20,015 ms. The exact APK's launcher button showed a responsive validation message and started its test target; the test did not install Battle.net or start a user game.
- A fresh installation of the same APK on the same RedMagic selected and hash-verified V39, passed 9/9 layers with overlay off, and rendered 1,151 frames in 20,010 ms in a second neutral D3D9 probe. It remained ready and all layers still matched after the probe.
- A prior candidate with the same GPU logic passed two 20-second cold-start probes and a simulated V39 conflict; it verified and loaded the original driver as fallback. These tests do not establish every failure-recovery or concurrent-input case.
- The independent unit suite passed 322 tests, with one skipped; 17 Python tests passed. The runtime archive and all 72 listed inputs were hash-verified against the APK.
- The user confirmed that an isolated V39/Vulkan diagnostic cleared black blocks in one WoW Forever D3D12 scene, then reported Battle.net and WoW exits and CPU stutter. That diagnostic had a different application identity. Other devices, D3D11 and long-duration game stability remain unverified.
