# Open Issues

- On RedMagic NX809J, a `D:\TestD3D.exe` warm launch was accepted and
  sent to WinHandler but the target process was not observed after resume
  in both the published v0.3.0 and the patched candidate. Cold launches
  succeeded. The cause and behavior for other working directories remain
  unverified; this is not proof that all warm launches fail.

- RedMagic V39 plus system Vulkan host composition cleared black blocks in
  one user-confirmed WoW Forever D3D12 scene, but Battle.net and WoW exits
  and frame pacing complaints remain unresolved. The CEF V8 flag is a
  mitigation only and did not eliminate reported exits. The packaged Wine RtlWaitOnAddress backport has finite diagnostic coverage;
  its effect on the reported Battle.net/WoW exits remains unproven.
  Source: user-confirmed (2026-11-09: report remaining crashes and stutter).

- The standard 0.3.0 candidate passed short RedMagic fresh-install and 0.2
  upgrade D3D9 probes, but other upgrade histories, other devices and final
  standard-package WoW stability still require validation.

- A launch cancelled while background validation is pending is covered by
  unit tests, but the narrow interval after a launch is committed and before
  Android starts the target activity has not been exercised under rapid app
  backgrounding. A blocked background activity could leave stale session
  metadata and needs device-level lifecycle validation.

- On NOVA the earlier `77b5d903...` candidate reached the Titan login screen
  and ran briefly with Battle.net open. The selected trial account had no
  game time, so world entry was not tested. The later `3fcdf431...` font
  candidate has not been tested through this game path on NOVA. Full
  Battle.net installer completion and a seeded stale-DLL-cache test remain
  unverified for the final candidate. Thor testing is deferred; do not
  represent it or broad compatibility as passed.
- NOVA Battle.net Chinese became readable after adding a diagnostic
  `Noto Sans CJK TC` alias to the existing bundled Source Han Sans CN. The
  packaged INF includes this alias; its v0.1 emulator upgrade and Wine first
  start pass, but the rebuilt APK still needs final device visual validation.
  The font uses CN-style glyphs for the
  TC fallback; regional typography has not been verified. Separately, stale
  external-font entries can remove DejaVu registrations and need investigation.
- The standard package does not expose a MapleStory shortcut or RenderDoc UI;
  their end-to-end use is not validated for this preview.
- XR remains disabled until its process lifecycle is synchronized with runtime
  updates and independently tested.
- Some inherited imagefs and graphics libraries lack verified matching source,
  Android patches and license provenance, including FFmpeg/x264, GnuTLS,
  PulseAudio and Mesa. Obtain the corresponding materials or rebuild from
  auditable sources; see `THIRD_PARTY_NOTICES.md` for the known boundaries.
