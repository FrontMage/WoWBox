# Open Issues

- Final device game and short stability validation remains incomplete on NOVA;
  Thor is deferred by the user. Do not represent emulator results as device
  gameplay or broad compatibility, or treat deferred coverage as passed.
- The standard package does not expose a MapleStory shortcut or RenderDoc UI;
  their end-to-end use is not validated for this preview.
- XR remains disabled until its process lifecycle is synchronized with runtime
  updates and independently tested.
- Some inherited imagefs and graphics libraries lack verified matching source,
  Android patches and license provenance, including FFmpeg/x264, GnuTLS,
  PulseAudio and Mesa. Obtain the corresponding materials or rebuild from
  auditable sources before publishing these runtime assets; see
  `THIRD_PARTY_NOTICES.md` for the known boundaries.
