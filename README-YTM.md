# playerYTM

The YouTube Music edition of [playerTerm](README.md), with the same ten band equalizer.

Browse your YouTube Music playlists, play one at a time, and shape the sound
while it runs. yt-dlp finds the audio, ffmpeg decodes it; everything after
that is plain Java — the filter bank, the spectrum analyser and the
interface. No third party libraries.

It shares playerTerm's equalizer, spectrum analyser and terminal code; only
the library and playback differ. It is built as `target/playerYTM.jar`, next to `target/playerTerm.jar`.

## Requirements

- Java 21+
- ffmpeg and yt-dlp on `PATH` (set `PLAYERTERM_FFMPEG` / `PLAYERTERM_YTDLP` to point elsewhere)
- a terminal that speaks 256 colours and UTF-8, at least 62x18

## Run it

```bash
./playerYTM                     # ~/Downloads/ytm_playlists
./playerYTM ~/somewhere/else    # or any folder of exports you name
```

## Your playlists

playerYTM reads a folder of playlist exports, each a JSON list like

```json
[
  {
    "id": "bffe38d7-fc7b-4c42-bcf0-b77b6a70a93b",
    "title": "Open Your Heart or Die Trying",
    "url": "https://music.youtube.com/playlist?list=OLAK5uy_…",
    "updatedDate": 1790459370551
  }
]
```

Only the **most recently written** `.txt` or `.json` file counts. Each entry
becomes a folder in the library, named by its `title`, holding the tracks at
its `url` — a playlist, an album or a single video. The folder is watched, so
dropping a new export in replaces the library while the player runs: playlists
still listed keep their place, new ones and ones whose `url` or `updatedDate`
changed are listed again, and a file still being written is left alone until
it is complete.

The folder is resolved in this order, first hit wins:

| | |
|---|---|
| a folder given on the command line | `./playerYTM /path/to/exports` |
| `$PLAYERTERM_PLAYLISTS` | |
| `~/Downloads/ytm_playlists` | the default |

## Keys

| Library | |
|---|---|
| `↑` `↓` `j` `k` | move |
| `space` | fold a playlist open or shut — or pause, when a track is selected |
| `→` `←` (`l` `h`) | expand / collapse; `←` on a track jumps to its playlist |
| `Enter` | play — a playlist plays its tracks, a track plays its playlist from there |
| `n` `b` | next / previous track (`b` restarts the track after 3 seconds in) |
| `,` `.` | seek 5 seconds back / forward |
| `p` `s` | pause or resume, stop |
| `PgUp` `PgDn` `g` `G` | page, top, bottom |

| Equalizer (`tab` switches panels and rereads the newest export) | |
|---|---|
| `←` `→` or `0`-`9` | select a band — the active slider turns white |
| `↑` `↓` | band gain, ±1 dB up to ±12 |
| `[` `]` | cycle presets |
| `e` | bypass the filter bank |
| `r` | back to flat |
| `<` `>` | preamp |

| Anywhere | |
|---|---|
| `+` `-` | volume |
| `m` | mute |
| `?` | key help |
| `q` | quit |

| Mouse | |
|---|---|
| click a playlist | folds it open or shut; one that failed to list is retried |
| click a track | selects it; click again to play |
| click Now Playing | pauses or resumes |
| click the progress bar | seeks there; the wheel nudges 5 seconds |
| wheel | scrolls the library, or adjusts the band or volume under the pointer |
| drag an equalizer slider | the gain follows the pointer, even outside its column |

Mouse tracking means the terminal stops handling plain drag-to-select; hold
shift to select text as usual, or start with `--no-mouse` to leave the mouse
alone entirely.

## Command line

```
playerYTM [folder]              read exports from that folder
playerYTM -p, --playlists <dir> same thing, named
playerYTM --no-mouse            leave the mouse to the terminal
playerYTM -h, --help            show help
```

Volume, equalizer curve and the last track played are kept in
`~/.config/playerytm/config.properties` (honouring `XDG_CONFIG_HOME`).

These are kept apart from playerTerm's, so the two players never share a volume
or equalizer curve.

## How it works

```
yt-dlp ──stream URL──▶ ffmpeg ──PCM s16le──▶ float ──▶ 10x biquad peaking filters (per channel)
                                   │
                                   ├──▶ preamp, volume, soft limiter ──▶ sound card
                                   └──▶ 2048 point FFT ──▶ spectrum bars
```

Each equalizer band is an RBJ peaking biquad at roughly one octave of
bandwidth, recalculated when a slider moves, so changes are audible
immediately without interrupting playback. A soft knee limiter after the gain
stage keeps heavy boosts from clipping.

**Listing never touches the videos.** Every playlist in the export is listed
by `yt-dlp --flat-playlist` on background threads as soon as it appears, which
takes a second or two each; a spinner stands in for the track count until
then. Pressing Enter on a playlist that is still listing plays it the moment
its tracks arrive.

**Playing a playlist** hands its tracks to one session thread. For each track
yt-dlp resolves the best audio stream (the header shows LOADING meanwhile),
and the next track's stream is looked up while the current one plays, so the
change between tracks is quick. Resolved streams are reused until YouTube's
expiry; one refused early is resolved again once. ffmpeg reads the stream
with reconnects enabled, then the session equalises and writes it. Pause stops
the output line so the sound cuts immediately. Seeking restarts ffmpeg with
`-ss` ahead of the input, so a jump costs one ranged request rather than
downloading everything skipped.

The interface is drawn straight to the terminal: a double buffered cell grid
diffed per row, 256 colour output, raw mode and SGR mouse reporting via
`stty`, and no curses.

## Layout

```
┌ header ─────────────────────────────────────────┐
│ playlists         │ now playing + progress      │  top half
│                   │ spectrum                    │
├───────────────────┴─────────────────────────────┤
│ equalizer                                       │  bottom half
├─────────────────────────────────────────────────┤
│ volume · playlist export · messages             │
└ keys ───────────────────────────────────────────┘
```
