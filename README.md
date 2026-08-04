# SMC信息生成器（原生 Android）

使用 Kotlin、Jetpack Compose 和 Material 3 编写的原生 Android 应用。应用从
`map.simmc.cn` 获取地图标记，生成国家 GDP、领地、区块、首都坐标、交通节点和
人口排行，并将 TXT、HTML 或 XLSX 文件保存到 `Download/SMap_file`。

## Windows 本地开发与编译

推荐安装 Android Studio，打开本目录后等待 Gradle Sync 完成。项目要求：

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 35

快速增量编译：

```powershell
.\gradlew.bat assembleDebug
```

最方便的方法是直接双击 `build_android_windows.bat`。脚本会复用 Gradle 后台进程和
已下载依赖；第一次较慢，未清理缓存时后续编译通常只需十几秒。APK 输出位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

本项目的编译不需要 WSL、Docker、Linux或CPU虚拟化。只有使用 Android模拟器时
才需要在 BIOS 中开启虚拟化；连接实体手机调试不需要模拟器。

## GitHub Actions 云编译

打开仓库 `Actions`，选择 `Build Native Android APK`，点击 `Run workflow`。
完成后在 Artifacts 下载 `SMCInfoGenerator-Native-APK`。

## 功能

- Jetpack Compose Material 3原生界面
- 默认亮色，可在右上角设置中切换暗色
- 设置中显示关于信息
- 首次启动显示存储说明，Android 9及以下请求系统存储权限
- Android 10及以上通过 MediaStore写入公共下载目录
- “定位文件”打开 `Download/SMap_file`
- 支持 TXT、HTML、XLSX

本目录是用于 GitHub 仓库的纯净原生 Android 源码，不包含旧 Python/Kivy/Buildozer
文件、Windows EXE、安装包、APK、编译缓存或诊断日志。
