# FzClient

Sources live in `FzClient-project/` (mod = Forge 1.8.9, launcher = Electron, server = Cloudflare Worker, website).
`docs/` at the repo root is the live GitHub Pages copy of `FzClient-project/website/`.

## Releasing a new version (vX.Y.Z)
Pushing the tag runs `.github/workflows/release.yml`, which builds everything with the tag's version and
publishes the 5 auto-update files. The version is injected by CI, but these places are written by hand and
must be updated in the same commit before tagging:

1. `FzClient-project/launcher/package.json` → `"version"`
2. `FzClient-project/mod/gradle.properties` → `version`
3. `FzClient-project/launcher/renderer/index.html` → `<div class="news-h">FzClient X.Y.Z</div>` and its
   `<li>` list (what's new; add English for new lines in `renderer/i18n.js`)
4. `docs/index.html` **and** `FzClient-project/website/index.html` → `Latest <b>vX.Y.Z</b>`
5. Mod-side text the user sees (menu, settings, `assets/fzclient/lang/en.txt`) if the change adds or renames
   features. The in-game "FzClient X.Y.Z" line comes from `assets/fzclient/version.txt` (filled from gradle.properties) automatically.

Before tagging, grep for the previous version number (`git grep -n "X.Y.(Z-1)"`) to catch anything new.
The installer name is fixed: `FzClient-Launcher-Setup.exe` (never put a version in download links).
