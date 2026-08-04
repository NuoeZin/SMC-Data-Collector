#!/usr/bin/env python3
"""Simmc GDP Android/Kivy application."""

from __future__ import annotations

import threading
from pathlib import Path

import simmc_gdp
from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.core.text import LabelBase
from kivy.lang import Builder
from kivy.properties import BooleanProperty, ListProperty, StringProperty
from kivy.storage.jsonstore import JsonStore
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.popup import Popup
from kivy.utils import platform


def register_chinese_font() -> str:
    """Use an available CJK font on Android, Windows, or Linux."""
    candidates = (
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansSC-Regular.otf",
        "C:/Windows/Fonts/msyh.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    )
    for candidate in candidates:
        if Path(candidate).is_file():
            LabelBase.register(name="AppFont", fn_regular=candidate)
            return "AppFont"
    return "Roboto"


APP_FONT = register_chinese_font()

KV = r"""
#:import dp kivy.metrics.dp

<OptionRow>:
    size_hint_y: None
    height: dp(46)
    padding: dp(10), 0
    canvas.before:
        Color:
            rgba: app.card_color
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(10)]
    Label:
        text: root.option_text
        font_name: app.font_name
        color: app.text_color
        halign: "left"
        valign: "middle"
        text_size: self.size
    CheckBox:
        active: root.option_active
        size_hint_x: None
        width: dp(48)
        on_active: root.option_active = self.active

<SpinnerOption>:
    font_name: app.font_name

<SettingsPopup>:
    title: "设置"
    title_font: app.font_name
    size_hint: (0.9, 0.74)
    separator_color: app.primary_color
    background: ""
    background_color: app.surface_color
    BoxLayout:
        orientation: "vertical"
        padding: dp(18)
        spacing: dp(14)
        canvas.before:
            Color:
                rgba: app.surface_color
            Rectangle:
                pos: self.pos
                size: self.size

        Label:
            text: "外观"
            font_name: app.font_name
            bold: True
            color: app.text_color
            halign: "left"
            text_size: self.size
            size_hint_y: None
            height: dp(34)
        BoxLayout:
            size_hint_y: None
            height: dp(48)
            Label:
                text: "暗色模式"
                font_name: app.font_name
                color: app.text_color
                halign: "left"
                text_size: self.size
            Switch:
                active: app.is_dark
                on_active: app.set_dark_mode(self.active)

        Widget:
            size_hint_y: None
            height: dp(1)
            canvas:
                Color:
                    rgba: app.divider_color
                Rectangle:
                    pos: self.pos
                    size: self.size

        Label:
            text: "关于"
            font_name: app.font_name
            bold: True
            color: app.text_color
            halign: "left"
            text_size: self.size
            size_hint_y: None
            height: dp(34)
        ScrollView:
            do_scroll_x: False
            Label:
                text: app.about_text
                font_name: app.font_name
                color: app.secondary_text_color
                halign: "left"
                valign: "top"
                text_size: self.width, None
                size_hint_y: None
                height: max(self.texture_size[1] + dp(12), dp(100))
        Button:
            text: "完成"
            font_name: app.font_name
            background_normal: ""
            background_color: app.primary_color
            size_hint_y: None
            height: dp(46)
            on_release: root.dismiss()

<StoragePermissionPopup>:
    title: "存储权限"
    title_font: app.font_name
    size_hint: (0.88, 0.46)
    auto_dismiss: False
    separator_color: app.primary_color
    background: ""
    background_color: app.surface_color
    BoxLayout:
        orientation: "vertical"
        padding: dp(18)
        spacing: dp(14)
        Label:
            text: "SMC信息生成器需要保存生成的报表。\n\n文件将写入：\n内部存储/Download/SMap_file\n\nAndroid 10及以上由系统分区存储安全管理；旧版系统会继续显示系统权限请求。"
            font_name: app.font_name
            color: app.text_color
            halign: "left"
            valign: "middle"
            text_size: self.size
        Button:
            text: "允许并继续"
            font_name: app.font_name
            background_normal: ""
            background_color: app.primary_color
            size_hint_y: None
            height: dp(46)
            on_release: app.confirm_storage_permission(root)

BoxLayout:
    orientation: "vertical"
    padding: dp(16)
    spacing: dp(12)
    canvas.before:
        Color:
            rgba: app.bg_color
        Rectangle:
            pos: self.pos
            size: self.size

    BoxLayout:
        size_hint_y: None
        height: dp(62)
        BoxLayout:
            orientation: "vertical"
            Label:
                text: "SMC信息生成器"
                font_name: app.font_name
                color: app.primary_color
                font_size: "23sp"
                bold: True
                halign: "left"
                text_size: self.size
            Label:
                text: "地图数据排行与报表"
                font_name: app.font_name
                color: app.secondary_text_color
                font_size: "13sp"
                halign: "left"
                text_size: self.size
        Button:
            text: "设置"
            font_name: app.font_name
            color: app.primary_color
            background_normal: ""
            background_color: (0, 0, 0, 0)
            size_hint_x: None
            width: dp(64)
            on_release: app.open_settings()

    ScrollView:
        do_scroll_x: False
        BoxLayout:
            orientation: "vertical"
            size_hint_y: None
            height: self.minimum_height
            spacing: dp(2)
            padding: 0, dp(4)

            OptionRow:
                option_text: "国家首都排行 + 国家总体排行"
                option_active: app.gdp_selected
                on_option_active: app.gdp_selected = self.option_active
            OptionRow:
                option_text: "全部领地坐标及区块"
                option_active: app.territories_selected
                on_option_active: app.territories_selected = self.option_active
            OptionRow:
                option_text: "国家所有领地区块排行"
                option_active: app.chunks_selected
                on_option_active: app.chunks_selected = self.option_active
            OptionRow:
                option_text: "国家首都坐标表"
                option_active: app.capitals_selected
                on_option_active: app.capitals_selected = self.option_active
            OptionRow:
                option_text: "港口和驿站坐标及归属"
                option_active: app.gateways_selected
                on_option_active: app.gateways_selected = self.option_active
            OptionRow:
                option_text: "国家与独立领地人口排行"
                option_active: app.population_selected
                on_option_active: app.population_selected = self.option_active

            Label:
                text: "保存格式"
                font_name: app.font_name
                color: app.secondary_text_color
                halign: "left"
                text_size: self.size
                size_hint_y: None
                height: dp(34)
            Spinner:
                text: app.output_format
                values: ("HTML", "TXT", "XLSX")
                font_name: app.font_name
                size_hint_y: None
                height: dp(44)
                background_color: app.primary_color
                on_text: app.output_format = self.text

            Label:
                text: app.status
                font_name: app.font_name
                color: app.secondary_text_color
                halign: "left"
                valign: "top"
                text_size: self.width, None
                size_hint_y: None
                height: max(dp(150), self.texture_size[1] + dp(18))

    BoxLayout:
        size_hint_y: None
        height: dp(48)
        spacing: dp(8)
        Button:
            text: "开始生成"
            font_name: app.font_name
            background_color: app.primary_color
            disabled: app.busy
            on_release: app.start_generation()
        Button:
            text: "定位文件"
            font_name: app.font_name
            background_color: app.success_color
            disabled: app.busy or not app.has_results
            on_release: app.locate_files()
"""


class OptionRow(BoxLayout):
    option_text = StringProperty("")
    option_active = BooleanProperty(True)


class SettingsPopup(Popup):
    pass


class StoragePermissionPopup(Popup):
    pass


class SimmcGDPApp(App):
    font_name = StringProperty(APP_FONT)
    status = StringProperty("请选择内容后点击“开始生成”。")
    output_format = StringProperty("HTML")
    busy = BooleanProperty(False)
    has_results = BooleanProperty(False)
    is_dark = BooleanProperty(False)
    about_text = StringProperty("SMC信息生成器\n版本 1.0.0\n\n用于生成 Simmc 地图数据排行与报表。")

    bg_color = ListProperty([0.055, 0.075, 0.11, 1])
    surface_color = ListProperty([0.085, 0.11, 0.16, 1])
    card_color = ListProperty([0.12, 0.16, 0.23, 1])
    text_color = ListProperty([0.9, 0.94, 1, 1])
    secondary_text_color = ListProperty([0.62, 0.7, 0.8, 1])
    divider_color = ListProperty([0.25, 0.3, 0.38, 1])
    primary_color = ListProperty([0.12, 0.56, 0.9, 1])
    success_color = ListProperty([0.16, 0.62, 0.4, 1])

    gdp_selected = BooleanProperty(True)
    territories_selected = BooleanProperty(True)
    chunks_selected = BooleanProperty(True)
    capitals_selected = BooleanProperty(True)
    gateways_selected = BooleanProperty(True)
    population_selected = BooleanProperty(True)

    def build(self):
        self.title = "SMC信息生成器"
        self.settings_store = JsonStore(str(Path(self.user_data_dir) / "settings.json"))
        saved_dark = self.settings_store.get("appearance").get("dark", False) if self.settings_store.exists("appearance") else False
        self.set_dark_mode(bool(saved_dark), save=False)
        self._load_about_text()
        self.generated_files: list[Path] = []
        self.public_location = "Download/SMap_file"
        root = Builder.load_string(KV)
        if not self.settings_store.exists("storage_notice"):
            Clock.schedule_once(lambda _dt: StoragePermissionPopup().open(), 0.5)
        return root

    def _load_about_text(self) -> None:
        about_path = Path(__file__).resolve().parent / "somesin.txt"
        try:
            custom = about_path.read_text(encoding="utf-8-sig").strip()
        except OSError:
            custom = ""
        base = "SMC信息生成器\n版本 1.0.0\n\n用于生成 Simmc 地图数据排行与报表。"
        self.about_text = base + (("\n\n" + custom) if custom else "")

    def set_dark_mode(self, enabled: bool, save: bool = True) -> None:
        self.is_dark = enabled
        if enabled:
            self.bg_color = [0.055, 0.075, 0.11, 1]
            self.surface_color = [0.085, 0.11, 0.16, 1]
            self.card_color = [0.12, 0.16, 0.23, 1]
            self.text_color = [0.9, 0.94, 1, 1]
            self.secondary_text_color = [0.62, 0.7, 0.8, 1]
            self.divider_color = [0.25, 0.3, 0.38, 1]
            self.primary_color = [0.18, 0.62, 0.95, 1]
        else:
            self.bg_color = [0.96, 0.97, 0.985, 1]
            self.surface_color = [1, 1, 1, 1]
            self.card_color = [1, 1, 1, 1]
            self.text_color = [0.1, 0.13, 0.18, 1]
            self.secondary_text_color = [0.36, 0.41, 0.48, 1]
            self.divider_color = [0.82, 0.84, 0.88, 1]
            self.primary_color = [0.04, 0.42, 0.76, 1]
        Window.clearcolor = self.bg_color
        if save and hasattr(self, "settings_store"):
            self.settings_store.put("appearance", dark=enabled)

    def open_settings(self) -> None:
        SettingsPopup().open()

    def confirm_storage_permission(self, popup: Popup) -> None:
        self.settings_store.put("storage_notice", accepted=True)
        popup.dismiss()
        self._request_storage_permission()

    @property
    def output_dir(self) -> Path:
        return Path(self.user_data_dir) / "output"

    def start_generation(self) -> None:
        selected = (
            self.gdp_selected,
            self.territories_selected,
            self.chunks_selected,
            self.capitals_selected,
            self.gateways_selected,
            self.population_selected,
        )
        if not any(selected):
            self.status = "请至少选择一种输出内容。"
            return
        self.busy = True
        self.has_results = False
        self.status = "正在下载地图数据……"
        threading.Thread(
            target=self._generate,
            args=(selected, self.output_format.lower()),
            daemon=True,
        ).start()

    def _set_status(self, message: str) -> None:
        Clock.schedule_once(lambda _dt: setattr(self, "status", message))

    def _finish(self, message: str, files: list[Path] | None = None) -> None:
        def update(_dt) -> None:
            self.busy = False
            self.status = message
            if files is not None:
                self.generated_files = files
                self.has_results = bool(files)

        Clock.schedule_once(update)

    def _generate(self, selected: tuple[bool, ...], output_format: str) -> None:
        try:
            data = simmc_gdp.download_json(simmc_gdp.DEFAULT_URL)
            self._set_status("下载完成，正在解析并生成文件……")
            lands, skipped = simmc_gdp.parse_lands(data)
            capitals, nations, warnings = simmc_gdp.build_rankings(lands)
            output_dir = self.output_dir
            output_dir.mkdir(parents=True, exist_ok=True)
            generated: list[Path] = []

            if selected[0]:
                simmc_gdp.write_text_rankings(output_dir, capitals, nations)
                simmc_gdp.write_world_land_gdp_ranking(output_dir, lands)
                generated += [output_dir / name for name in (
                    "国家首都排行.txt", "国家总体排行.txt", "全世界领地GDP排行.txt"
                )]
            if selected[1]:
                simmc_gdp.write_territory_details(output_dir, lands)
                generated.append(output_dir / "全部领地坐标及区块.txt")
            if selected[2]:
                simmc_gdp.write_nation_chunk_ranking(output_dir, lands)
                generated.append(output_dir / "国家所有领地区块排行.txt")
            if selected[3]:
                simmc_gdp.write_capital_coordinates(output_dir, lands)
                generated.append(output_dir / "国家首都坐标表.txt")
            if selected[4]:
                simmc_gdp.write_gateway_details(output_dir, data, lands)
                generated.append(output_dir / "港口和驿站坐标.txt")
            if selected[5]:
                simmc_gdp.write_population_files(output_dir, lands)
                generated.append(output_dir / "国家及独立领地玩家总数排行.txt")

            converted = simmc_gdp.convert_text_outputs(generated, output_format)
            published = self._publish_to_downloads(converted)
            joined = sum(land.nation is not None for land in lands)
            details = [
                f"生成完成：{len(converted)} 个 {output_format.upper()} 文件。",
                f"共解析 {len(lands)} 块领地，{joined} 块属于国家。",
                f"保存位置：{published}",
            ]
            if skipped:
                details.append(f"跳过 {skipped} 个字段不完整的标记。")
            details.extend(f"警告：{warning}" for warning in warnings)
            self._finish("\n".join(details), converted)
        except Exception as exc:
            self._finish(f"生成失败：{exc}")

    def _request_storage_permission(self) -> None:
        """Request legacy storage access only where Android still uses it."""
        if platform != "android":
            return
        try:
            from android.permissions import Permission, request_permissions
            from jnius import autoclass

            if autoclass("android.os.Build$VERSION").SDK_INT <= 28:
                request_permissions([Permission.WRITE_EXTERNAL_STORAGE])
        except Exception:
            pass

    def _publish_to_downloads(self, paths: list[Path]) -> str:
        """Copy results into the user-visible Downloads/SimmcGDP folder."""
        if platform != "android":
            return str(self.output_dir)
        from jnius import autoclass

        sdk = autoclass("android.os.Build$VERSION").SDK_INT
        Environment = autoclass("android.os.Environment")
        if sdk <= 28:
            downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            target_dir = Path(str(downloads.getAbsolutePath())) / "SMap_file"
            target_dir.mkdir(parents=True, exist_ok=True)
            for path in paths:
                (target_dir / path.name).write_bytes(path.read_bytes())
            return str(target_dir)

        MediaStore = autoclass("android.provider.MediaStore")
        Downloads = autoclass("android.provider.MediaStore$Downloads")
        MediaColumns = autoclass("android.provider.MediaStore$MediaColumns")
        ContentValues = autoclass("android.content.ContentValues")
        activity = autoclass("org.kivy.android.PythonActivity").mActivity
        resolver = activity.getContentResolver()
        collection = Downloads.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        mime_types = {
            ".txt": "text/plain",
            ".html": "text/html",
            ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        }
        for path in paths:
            values = ContentValues()
            values.put(MediaColumns.DISPLAY_NAME, path.name)
            values.put(MediaColumns.MIME_TYPE, mime_types.get(path.suffix, "application/octet-stream"))
            values.put(MediaColumns.RELATIVE_PATH, "Download/SMap_file")
            values.put(MediaColumns.IS_PENDING, 1)
            uri = resolver.insert(collection, values)
            if uri is None:
                raise OSError(f"无法在下载目录创建 {path.name}")
            stream = resolver.openOutputStream(uri)
            try:
                stream.write(bytearray(path.read_bytes()))
                stream.flush()
            finally:
                stream.close()
            ready = ContentValues()
            ready.put(MediaColumns.IS_PENDING, 0)
            resolver.update(uri, ready, None, None)
        return "内部存储/Download/SMap_file"

    def locate_files(self) -> None:
        """Open Android's system file manager at the result directory."""
        if platform != "android":
            self.status = f"文件位置：{self.output_dir}"
            return
        try:
            from jnius import autoclass

            Intent = autoclass("android.content.Intent")
            Uri = autoclass("android.net.Uri")
            DocumentsContract = autoclass("android.provider.DocumentsContract")
            activity = autoclass("org.kivy.android.PythonActivity").mActivity
            folder_uri = Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2FSMap_file"
            )
            intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(folder_uri, "vnd.android.document/directory")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try:
                activity.startActivity(intent)
            except Exception:
                picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder_uri)
                activity.startActivity(picker)
        except Exception as exc:
            self.status = f"无法打开文件管理器：{exc}\n文件位于 Download/SMap_file"


if __name__ == "__main__":
    SimmcGDPApp().run()
