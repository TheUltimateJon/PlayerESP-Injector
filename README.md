# PlayerESP Injector (Minecraft 1.8.9)

Independent Player ESP injector for named, Forge/SRG, and official-obfuscated 1.8.9 runtimes.

Features:

- 2D, 3D, both, or disabled box modes
- Through-wall translucent chams volume
- Optional modified nametag and distance
- Health bar above or beside the player
- Target HUD for the player under the crosshair
- Persistent JSON configuration
- Separate menu and enable hotkeys

Build the payload first, then embed it into the native library:

```powershell
.\gradlew.bat -p ".\payload" clean payloadJars
cmake -S ".\native" -B ".\native\build" -G Ninja -DJAVA_HOME="<path to your JDK 8>"
cmake --build ".\native\build" --config Release
```

Runtime files:

- `loader.exe`
- `Entry.dll`
- `playeresp_library.dll`



Wrote by deepsuck, don't blame me
