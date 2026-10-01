# Fox2D

An open source Animation 2D app for Android.

> **Early stage – first releases.**
> Fox2D is brand new and still under heavy development. Expect missing features, rough edges and breaking changes
> between versions. Please save and export often, and report anything that breaks.

---

## Features

### Drawing
- Pen and eraser, drawn by a native **OpenGL ES** canvas (C++ / JNI) for smooth, low-latency strokes
- Pan, zoom and rotate the canvas with touch gestures
- Colour and brush size controls
- Multiple drawing layers inside every drawing; the eraser only cuts through its own layer
- Undo / redo

### Timeline
- Frame-by-frame animation: every drawing is held for as many frames as you like
- **Any number of drawing tracks**, stacked on top of each other and shown together on one canvas
- Move a whole drawing track **left / right in time**, like an audio clip
- Long-press a track and drag it **up / down** to put it in front of or behind the others
- **Audio tracks** with rendered waveforms: import audio, move, split, copy / paste, duplicate and delete clips
- Zoomable, horizontally scrollable timeline
- Playback with the audio clock driving the picture, so sound and image stay in sync
- The **+** button adds a drawing track or an audio track

### Transform
- Move, rotate and resize a whole drawing track
- Movable **pivot** point, separate **W** and **H**, flip horizontal / vertical
- Choose between touching anywhere on screen or touching only the transform box
- Sliders and on-canvas handles; one undo step per gesture

### Export
- Export the animation to a video file (native encoder built on FFmpeg)
- Exports every track, layer, transform and the audio mix exactly as it looks on the canvas
- Choose resolution, codec and quality

---

## Not done yet

Some entries are visible in the app but not working yet:

- Smudge and blur brushes
- Deform and bone tools
- Keyframes and a graph editor
- Onion skin
- Animated (keyframed) transforms – a transform is currently one fixed position / rotation / size per track

---

## Tech

| Part | What |
|---|---|
| App | Kotlin, Jetpack Compose (Material 3) |
| Canvas | C++ with OpenGL ES 3, called through JNI |
| Audio | Native audio handler |
| Video export | C++ with FFmpeg (libav) |
| Package | `fox.foxiru.foxcat.fox2d` |

Main source files:

- `EditorScreen.kt` – editor state, canvas surface, tool rail, dock
- `TimelineEditor.kt` / `TimelineModel.kt` – timeline UI and audio clips / tracks
- `TransformUi.kt` – transform handles and popup
- `NativeCanvas.kt` + `fox_canvas.cpp` – native drawing canvas
- `MovieExporter.kt`, `NativeExporter.kt` + `fox_exporter.cpp` – video export

---

## Building

You need:

- Android Studio with the Android SDK
- Android NDK and CMake
- FFmpeg libraries for Android (used by the video exporter)

Then open the project in Android Studio, let Gradle sync, and run the `app` module on a device or emulator.
OpenGL ES 3.0 is required.

---

## Contributing

Fox2D is open source and in its first days, so help is very welcome: bug reports, ideas, and pull requests.
When you report a bug, please include your device, Android version and the steps to reproduce it.

## License

Fox2D is released under the [MIT License](LICENSE).
