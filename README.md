# PlayerESP Injector（Minecraft 1.8.9）

独立的 Player ESP 注入器，支持 named、Forge/SRG 和 official-obfuscated 1.8.9 运行环境。

## 功能

- 2D Box、3D Box、玩家模型 Outline 或关闭 Box
- 穿墙 Chams
- 自定义颜色或玩家 Nametag/队伍颜色
- Nametag、背景、距离、生命条和生命值文字
- 装备、手持物品和 Target HUD
- NPC 过滤、最大渲染距离
- 独立的菜单键和启用热键
- JSON 配置自动保存

## 构建环境

- Windows 10/11 x64
- JDK 8
- CMake 3.16+
- Ninja
- Gradle（也可以使用你工作目录里已有的 `gradlew.bat`）
- MinGW-w64 或其他 Windows x64 C++17 工具链

仓库不依赖相邻的其他项目。先构建 Java Payload，再构建原生文件：

```powershell
cd D:\Workspace\mdk\playeresp-injector
gradle -p .\payload clean payloadJars

cmake -S .\native -B .\native\build-ninja `
  -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  "-DJAVA_HOME:PATH=C:/Users/HP/AppData/Local/Programs/Eclipse Adoptium/jdk-8.0.502.7-hotspot"

cmake --build .\native\build-ninja
```

`JAVA_HOME` 在 CMake 参数中应使用正斜杠。看到 `Configuring done` 和 `Generating done` 后再运行构建命令。

## 构建产物

```text
native\build-ninja\loader.exe
native\build-ninja\Entry.dll
native\build-ninja\playeresp_library.dll
```

三个文件必须放在同一目录。启动 Minecraft 1.8.9 后运行 `loader.exe`，注入成功后在游戏内按 `\` 打开菜单。

配置文件：`.minecraft\Toolbox\playeresp_config.js`
