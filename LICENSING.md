# Licensing and upstream notices

The root `LICENSE` preserves the original MIT notice for inherited Winlator
application code. It does not assert that the integrated WoW Box release or
its third-party components are solely MIT-licensed.

The Vulkan host compositor includes code adapted from
[GameNative commit `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`](https://github.com/utkarshdalal/GameNative/tree/205cbf8ddc06f855a1b85ba57c78c4be9a6930aa).
That upstream repository's license at that commit is GPL-3.0; its original
license text is in `app/src/main/assets/licenses/GameNative-GPL-3.0.txt` and
is also distributed in the APK. The adapted source and an explanation of
the changes are identified in `THIRD_PARTY_NOTICES.md`.

Other components retain their own licenses and notices. The inherited
imagefs and V39 driver have documented source-provenance gaps; see
`THIRD_PARTY_NOTICES.md` for their scope rather than assuming that the
runtime-source archive includes every corresponding source tree.
