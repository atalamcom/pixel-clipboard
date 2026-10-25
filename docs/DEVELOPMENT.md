# Development

Pixel Clipboard uses Java Swing and the JDK only. There are no Maven, Gradle, or runtime library dependencies.

## Main classes

- `PixelClipboard`: application lifecycle, single-instance file lock, window gestures, asynchronous save queue.
- `ClipboardPanel`: pixel-themed UI, task interaction, page layout, title editing, Settings.
- `AppState`: task/page/title model and compatible save-file conversion.
- `StateStore`: UTF-8 reads, atomic saves, backup recovery, preservation of damaged files.
- `Json`: strict JSON codec used by the state store.
- `PixelFonts`: bundled Silkscreen font and a fallback for multilingual text.
- `AppInstallation`: finds the matching native installer or opens the project's release page.

## Commands

Run commands from the repository root. `JAVA_HOME` must point to a JDK 21 or newer. A local JDK under `.tooling/jdk/jdk-*` is also detected.

```bat
build.cmd jar
test.cmd headless
test.cmd
build.cmd
package-installer.cmd
```

`test.cmd` compiles both test entry points, exercises data migration and recovery, dispatches task shortcut and click actions, renders multiple window sizes, then opens the actual application using isolated `APPDATA`. Tests never use your personal checklist.

Close the application before regenerating its `dist/PixelClipboard` folder. Increment `VERSION` for a new release; the value is also included inside the JAR as `version.txt`.

Use `build.cmd release` and `package-installer.cmd release` to package into `dist/releases/<version>/` while keeping an older running portable copy intact. `run.cmd` prefers that versioned output when present.

## Installer

The packaging command uses JDK `jpackage` and WiX Toolset 3.14.1. Download the portable WiX binaries from the [official release](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm) and extract them to `.tooling/wix`, or add them to `PATH`.

The EXE installer is configured for the current user, with a stable upgrade identifier and a shortcut selection dialog. It registers the app for Windows uninstallation and offers Start menu and desktop shortcuts. Its application directory includes the Java runtime. User save files remain outside the installation directory.

The Settings button `WINDOWS SETUP` looks for `PixelClipboard-Setup-<version>.exe` in the parent of the portable app directory. If it is absent, the button opens GitHub Releases. Tests inspect discovery without launching an installer.

## Local-only files

`.gitignore` excludes `.tooling`, `build`, `dist`, `legacy`, shortcuts, editor state, logs, and checklist saves. Do not add generated runtime files or personal state to commits.

## Credits

Project owner and maintainer: [atalamcom](https://github.com/atalamcom).

Development assistance: ChatGPT by OpenAI. This credit describes the development process; it is not a separate GitHub account or repository collaborator.

Inspiration: the clipboard idea from the BiblioCraft Minecraft mod.
