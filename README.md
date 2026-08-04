# SMC信息生成器 （Android）

使用 Kotlin、Jetpack Compose 和 Material 3 编写的原生 Android 应用。应用从
`map.simmc.cn` 获取地图标记，生成国家 GDP、领地、区块、首都坐标、交通节点和
人口排行，并将 TXT、HTML 或 XLSX 文件保存到 `Download/SMap_file`。

## 编译

推荐安装 Android Studio，打开本目录后等待 Gradle Sync 完成。项目要求：

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 35


## 功能

- Jetpack Compose Material 3原生界面
- 默认亮色，可在右上角设置中切换暗色
- 设置中显示关于信息
- 首次启动显示存储说明，Android 9及以下请求系统存储权限
- Android 10及以上通过 MediaStore写入公共下载目录
- “定位文件”打开 `Download/SMap_file`
- 支持 TXT、HTML、XLSX

