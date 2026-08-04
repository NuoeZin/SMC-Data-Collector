[app]
title = SMC信息生成器
package.name = simmcgdp
package.domain = cn.simmc
source.dir = .
source.include_exts = py,txt,png,jpg,jpeg,gif,json
source.exclude_dirs = .git,.venv,venv,build,dist,__pycache__
version = 1.0.0
requirements = python3,kivy==2.3.1,requests,certifi,pillow,openpyxl
orientation = portrait
fullscreen = 0
icon.filename = assets/simmc.png
android.presplash_color = #FFFFFF

android.permissions = INTERNET, WRITE_EXTERNAL_STORAGE
android.api = 35
android.minapi = 24
android.ndk = 27c
android.archs = arm64-v8a
android.accept_sdk_license = True

[buildozer]
log_level = 2
warn_on_root = 1
