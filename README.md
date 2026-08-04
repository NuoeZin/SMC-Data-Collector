# Simmc 国家 GDP 排名生成器

脚本从 `map.simmc.cn` 的 squaremap 标记接口读取领地资料：领地“余额”作为该领地 GDP；首都 GDP 是首都领地余额；国家 GDP 是首都及其全部附属领土余额之和。

```powershell
python -m pip install -r requirements.txt
python simmc_gdp.py
```

推荐直接运行 `dist/SimmcGDP.exe`。这是无控制台窗口的独立程序，不要求电脑另外安装 Python。界面可勾选输出类型、查看运行日志并打开输出目录。

`assets/simmc.ico` 来自 `map.simmc.cn/favicon.ico`，构建时同时用作程序图标并内嵌到单文件 EXE 中。

`assets/version_info.txt` 定义 Windows EXE 详细信息：作者 Norin，版本 1.0。

关于窗口的文字来自 `somesin.txt`，右侧图片来自 `somesin/`，两者会一并内嵌进单文件 EXE；GIF 会自动播放。

需要重新构建 EXE 时，双击 `一键打包.bat`。脚本会检查 Python 和 PyInstaller，必要时自动安装 PyInstaller，然后根据 `SimmcGDP.spec` 输出 `dist/SimmcGDP.exe`。

开发或调试时也可以在命令行执行：

```powershell
python simmc_gdp.py --task all
```

源码运行时结果写入项目的 `output/`；EXE 运行时结果写入 `dist/output/`。默认保存为 HTML，GUI 也可切换为 TXT 或 XLSX；这些均为运行生成目录，不属于源码：

- `国家首都排行.txt`：国家首都存款降序榜；
- `国家总体排行.txt`：国家总存款降序榜，包含各国最富有领土。
- `全世界领地GDP排行.txt`：全部国家领地和独立领地的存款降序榜；
- `全部领地坐标及区块.txt`：首都、附属领地及未加入国家领地的中心坐标、区块数和存款；
- `国家所有领地区块排行.txt`：各国全部领地的区块合计降序榜。
- `国家首都坐标表.txt`：各国首都名称、中心坐标和首都区块数。
- `港口和驿站坐标.txt`：港口/驿站的坐标以及空间匹配得到的所属国家和领地；
- `国家及独立领地玩家总数排行.txt`：国家人口及未入国独立领地人口降序榜。

领地通常是不规则多边形，因此脚本以领地外接范围的中心作为输出坐标。

TXT 使用 UTF-8 BOM。需要复现某次下载结果时，可保存地图的 `markers.json` 后执行：

```powershell
python simmc_gdp.py --input markers.json
```

## Android APK

Android 版入口为 `main.py`，采用 Kivy 界面；原有 Windows/Tkinter 版本不受影响。
由于 Buildozer 仅支持 Linux，请在 WSL2 Ubuntu 或 Linux 中进入项目目录后执行：

```bash
sudo apt update
sudo apt install -y python3-pip python3-venv git zip unzip openjdk-17-jdk \
  autoconf libtool pkg-config zlib1g-dev libncurses5-dev \
  libncursesw5-dev libtinfo6 cmake libffi-dev libssl-dev

python3 -m venv .venv
source .venv/bin/activate
bash 安卓打包.sh
```

首次构建会下载 Android SDK/NDK，所需时间较长。生成的调试 APK 位于 `bin/`。
安装到已通过 USB 连接并启用调试的手机：

```bash
buildozer android deploy run
```

应用需要联网读取地图数据。结果会发布到系统 `Download/SMap_file` 目录，界面底部
的“定位文件”按钮会调用文件管理器打开该位置。Android 9 及以下首次启动时请求
存储权限；Android 10 及以上使用系统 MediaStore 分区存储，不需要传统存储权限。

### GitHub Actions 云编译（无需 WSL）

1. 在 GitHub 新建仓库并把本项目全部文件上传，须保留 `.github` 隐藏目录。
2. 打开仓库的 `Actions` 页面，首次使用时按提示启用 Actions。
3. 左侧选择 `Build Android APK`，点击 `Run workflow`，再次点击绿色按钮确认。
4. 等待 `Build debug APK` 任务完成。首次构建通常需要较长时间。
5. 打开完成的任务，在页面底部 `Artifacts` 区域下载
   `SimmcGDP-Android-APK`；解压 ZIP 后即可得到 APK。

云编译入口配置在 `.github/workflows/build-android-apk.yml`，只能手动触发，
不会在每次上传代码时自动运行。

### 从 Windows 一键打包

安装并初始化 WSL2 Ubuntu 后，可以直接双击 `build_android_windows.bat`（推荐，
文件名为纯英文）或 `安卓APK打包_Windows.bat`。脚本会在
Ubuntu 中安装依赖、创建隔离环境、执行 Buildozer，并在成功后打开 Windows 的
`bin` 目录。首次运行需要输入 Ubuntu 用户密码并下载 Android SDK/NDK。
