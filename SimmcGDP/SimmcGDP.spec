# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['simmc_gdp_gui.py'],
    pathex=[],
    binaries=[],
    datas=[('assets\\simmc.ico', 'assets'), ('somesin.txt', '.'), ('somesin', 'somesin')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='SimmcGDP',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['assets\\simmc.ico'],
    version='assets\\version_info.txt',
)
