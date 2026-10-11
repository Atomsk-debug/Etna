# Performance and benchmarking

## What to expect

On a Mac mini M4 drawing 1920×1080 pixels with the Ultra preset, Etna 0.7.0 ran at **116 to 120 fps** in exclusive
fullscreen, adding about 5 ms per frame to the game's own rendering (Minecraft alone ran at about 300 fps there).

* Use exclusive fullscreen on macOS if you can: without it, the system's compositing makes every frame noticeably
  slower, with or without Etna.
* Lower presets use a smaller shadow map and a shorter shadow distance; Low has no shadows and no anti-aliasing.
* Shadows are the biggest cost. Shadow Distance and Shadow Quality (Lighting & Shadows page) trade the most speed
  for the least look.
* On a Mac, Resolution set to Half draws a quarter of the pixels: much faster, but blurry.

## Measuring your own machine

Both tools are on the **General** page of Options > Etna Shaders...

* **Performance log**: set it to Summary, and every 5 seconds a line goes to `logs/latest.log` with the frame
  rate, the frame time and where it goes, on the CPU and on the GPU (each pass of Etna timed on one frame in
  eight), and the draw calls.
* **Run benchmark**: about 145 seconds, hands off, inside a world. Etna measures itself off, as set, and with each
  costly feature off in turn, then writes a table of what each one costs to `logs/latest.log` and puts every
  setting back as it was. The sun and the weather hold still while it runs, so every step sees the same shadows
  (the rain the server sent comes back when it ends). If the scene still changes (chunks loading, mobs walking
  into view), the run is marked unsteady, and a step that drew no shadow map says so in its row.

For a fair run:

1. Turn VSync and any frame-rate limiter mod off.
2. Stand somewhere typical, wait for the chunks around you to load, then start the benchmark and don't touch the
   mouse or keyboard. A step disturbed by a camera move or a stutter is measured again.
3. Compare runs only in the same window mode: the benchmark's settings line records windowed, fullscreen or
   exclusive fullscreen and the display's refresh rate.
