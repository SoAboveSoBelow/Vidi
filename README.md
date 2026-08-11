<div align="center">

<a href="https://aniyomi.org">
    <img src="./.github/assets/logo.png" alt="Animiru logo" title="Animiru logo" width="80"/>
</a>

# Vidi [App](#)
Vidi is a fork of [Animiru](https://github.com/quickdesh/Animiru)

### Full-featured video player
Discover and watch anime, donghua, series, and more – easier than ever on your Android device.

[![Discord server](https://img.shields.io/discord/1009125884491468861.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/yDuHDMwxhv)
[![GitHub downloads](https://img.shields.io/github/downloads/SoAboveSoBelow/Vidi/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/SoAboveSoBelow/Vidi/releases/latest)

[![License: Apache-2.0](https://img.shields.io/github/license/SoAboveSoBelow/Vidi?labelColor=27303D&color=0877d2)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/aniyomi?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/aniyomi/)

## Download

[![Animiru](https://img.shields.io/github/v/release/SoAboveSoBelow/Vidi?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/SoAboveSoBelow/Vidi/releases/latest)

*Requires Android 8.0 or higher.*

If you're unsure which APK to grab, go with the plain `Animiru-vX.X.X.X.apk` (universal) build. The per-architecture builds (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) are smaller downloads for people who know their device's CPU architecture.

## Features

<div align="left">

Base features:
* Watch videos
* Local watching of downloaded content
* A configurable player built on mpv-android with multiple options and settings
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [Shikimori](https://shikimori.io), and [Bangumi](https://bgm.tv/)
* Categories to organize your library
* Light and dark themes
* Create backups locally to watch offline or to your desired cloud service

**Added in this fork:**
* **Background playback** — a foreground service keeps audio playing when the app is backgrounded, with full notification controls (play/pause, stop, reopen) and automatic screen-off triggering
* **Configurable PIP button** — set the first PIP button to skip 10s, previous episode, or background play
* **Episode shuffle** — persisted per-anime, consistent between the episode list and the player
* **Episode search** on the anime detail screen
* **Episode view modes** — Simplified / Preview / Minimal, controlling thumbnail previews and metadata visibility
* **Watch progress thumbnail overlay** in Minimal view
* **Clear anime** — reset an anime's episode list without touching downloads
* **Progressive local sync** — episode lists appear immediately while thumbnails generate in the background, instead of the whole refresh waiting on every thumbnail
* **Organized thumbnails** — generated thumbnails live in a dedicated `.thumbnails` subfolder instead of cluttering the anime's root directory
* **Configurable resume-position memory** — choose how many recently-viewed episodes keep a temporary resume position, so accidental next/previous clicks don't lose your place
* Plus much more...

</div>

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

Before reporting a new issue, take a look at the [FAQ](https://aniyomi.org/docs/faq/general) and the already opened [issues](https://github.com/SoAboveSoBelow/Vidi/issues); if you got any questions, join our [Discord server](https://discord.gg/yDuHDMwxhv).

This is a fork of [quickdesh/Animiru](https://github.com/quickdesh/Animiru), which is itself a fork of [Aniyomi](https://github.com/aniyomiorg/aniyomi). See the upstream [changelog](https://github.com/quickdesh/Animiru/blob/animiru-new-main/CHANGELOG.md) for base Animiru release history.

### Repositories

[![Secozzi/mpv-android - GitHub](https://github-stats-extended.vercel.app/api/pin?username=Secozzi&repo=mpv-android&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/Secozzi/mpv-android/)
[![jmir1/ffmpeg-kit - GitHub](https://github-stats-extended.vercel.app/api/pin?username=jmir1&repo=ffmpeg-kit&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/jmir1/ffmpeg-kit/)

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/SoAboveSoBelow/Vidi/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=quickdesh/Animiru" alt="Animiru app contributors" title="Animiru app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2024 Aniyomi Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
