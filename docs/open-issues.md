# Open Issues

- On NOVA the final candidate reached the Titan login screen and ran briefly
  with Battle.net open. The selected trial account had no game time, so world
  entry was not tested. Full Battle.net installer completion and a seeded
  stale-DLL-cache test remain unverified for this candidate. Thor testing is
  deferred; do not represent it or broad compatibility as passed.
- The standard package does not expose a MapleStory shortcut or RenderDoc UI;
  their end-to-end use is not validated for this preview.
- XR remains disabled until its process lifecycle is synchronized with runtime
  updates and independently tested.
- Some inherited imagefs and graphics libraries lack verified matching source,
  Android patches and license provenance, including FFmpeg/x264, GnuTLS,
  PulseAudio and Mesa. Obtain the corresponding materials or rebuild from
  auditable sources before publishing these runtime assets; see
  `THIRD_PARTY_NOTICES.md` for the known boundaries.
