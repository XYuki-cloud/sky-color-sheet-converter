# Third-party notices

This repository does not redistribute model checkpoints, audio/video files,
or external executables. They are installed or supplied by the user at run
time and remain subject to their own terms.

## Tsumugi

- Source: [anime-song/tsumugi](https://github.com/anime-song/tsumugi)
- Repository license: MIT, as provided by the upstream repository
- Pinned source commit: `68534106370860169148f09d168db105dbc17b00`
- Purpose: optional `guitar_v1_5` audio-to-MIDI backend

The Git submodule records the source revision. The checkpoint
`best_model_guitar_v1_5.pth` is not part of this repository; obtain it from
the upstream project under its applicable model terms.

## Sky Music Sheet Maker

- Reference repository: [sky-music/sky-python-music-sheet-maker](https://github.com/sky-music/sky-python-music-sheet-maker)
- Purpose here: format reference for legacy SkyStudio / 画世界 JSON TXT
- Status: not a formal runtime dependency and not imported by the main
  conversion paths

Any local executable or copied reference source remains outside the public
source set. Consult the upstream repository for its license and notices.

## Test MIDI fixtures

The small files under `test_songs/` are test fixtures, not user-provided
music outputs. Their provenance and upstream license references are recorded
in [`desktop-converter/test_songs/README.md`](desktop-converter/test_songs/README.md). The root Apache-2.0 license
does not relicense those files; downstream distributors should preserve the
corresponding notices.

## Runtime dependencies

Python packages listed in the requirements files, ffmpeg, Node.js, and any
optional audio model have their own licenses and distribution conditions.
This project only declares how to call them; it does not relicense or bundle
their binaries or weights. Users are responsible for checking the terms that
apply to their use and distribution.
