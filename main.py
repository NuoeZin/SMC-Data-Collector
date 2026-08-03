#!/usr/bin/env python3
"""Simmc GDP Android/Kivy application."""

from __future__ import annotations

import threading
from pathlib import Path

import simmc_gdp
from kivy.app import App
from kivy.clock import Clock
from kivy.core.text import LabelBase
from kivy.lang import Builder
from kivy.properties import BooleanProperty, StringProperty
from kivy.uix.boxlayout import BoxLayout
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
    height: dp(42)
    Label:
        text: root.option_text
        font_name: app.font_name
        halign: "left"
        valign: "middle"
        text_size: self.size
    CheckBox:
        active: root.option_active
        size_hint_x: None
        width: dp(48)
        on_active: root.option_active = self.active

BoxLayout:
    orientation: "vertical"
    padding: dp(14)
    spacing: dp(9)

    Label:
        text: "Simmc 信息生成器"
        font_name: app.font_name
        font_size: "22sp"
        bold: True
        size_hint_y: None
        height: dp(44)

    ScrollView:
        do_scroll_x: False
        BoxLayout:
            orientation: "vertical"
            size_hint_y: None
            height: self.minimum_height
            spacing: dp(2)

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
                on_text: app.output_format = self.text

            Label:
                text: app.status
                font_name: app.font_name
                halign: "left"
                valign: "top"
                text_size: self.width, None
                size_hint_y: None
                height: max(dp(150), self.texture_size[1] + dp(18))

    ProgressBar:
        max: 1
        value: 0 if app.busy else 1
        size_hint_y: None
        height: dp(5)

    BoxLayout:
        size_hint_y: None
        height: dp(48)
        spacing: dp(8)
        Button:
            text: "开始生成"
            font_name: app.font_name
            disabled: app.busy
            on_release: app.start_generation()
        Button:
            text: "分享结果"
            font_name: app.font_name
            disabled: app.busy or not app.has_results
            on_release: app.share_results()
"""


class OptionRow(BoxLayout):
    option_text = StringProperty("")
    option_active = BooleanProperty(True)


class SimmcGDPApp(App):
    font_name = StringProperty(APP_FONT)
    status = StringProperty("请选择内容后点击“开始生成”。")
    output_format = StringProperty("HTML")
    busy = BooleanProperty(False)
    has_results = BooleanProperty(False)

    gdp_selected = BooleanProperty(True)
    territories_selected = BooleanProperty(True)
    chunks_selected = BooleanProperty(True)
    capitals_selected = BooleanProperty(True)
    gateways_selected = BooleanProperty(True)
    population_selected = BooleanProperty(True)

    def build(self):
        self.title = "Simmc 信息生成器"
        self.generated_files: list[Path] = []
        return Builder.load_string(KV)

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
            joined = sum(land.nation is not None for land in lands)
            details = [
                f"生成完成：{len(converted)} 个 {output_format.upper()} 文件。",
                f"共解析 {len(lands)} 块领地，{joined} 块属于国家。",
                f"保存位置：{output_dir}",
            ]
            if skipped:
                details.append(f"跳过 {skipped} 个字段不完整的标记。")
            details.extend(f"警告：{warning}" for warning in warnings)
            self._finish("\n".join(details), converted)
        except Exception as exc:
            self._finish(f"生成失败：{exc}")

    def share_results(self) -> None:
        """Open Android's share sheet with textual output content."""
        if not self.generated_files:
            return
        text_parts = []
        for path in self.generated_files:
            if path.suffix.lower() in {".txt", ".html"}:
                try:
                    text_parts.append(f"【{path.name}】\n{path.read_text(encoding='utf-8-sig')}")
                except OSError:
                    continue
        if not text_parts:
            self.status = "XLSX 文件已生成；请选择 TXT 或 HTML 格式后可直接分享文本。"
            return
        share_text = "\n\n".join(text_parts)
        if platform != "android":
            self.status = "分享功能仅在 Android 上可用。\n" + self.status
            return
        try:
            from jnius import autoclass

            Intent = autoclass("android.content.Intent")
            String = autoclass("java.lang.String")
            intent = Intent()
            intent.setAction(Intent.ACTION_SEND)
            intent.setType("text/plain")
            intent.putExtra(Intent.EXTRA_SUBJECT, String("Simmc 信息生成结果"))
            intent.putExtra(Intent.EXTRA_TEXT, String(share_text))
            chooser = Intent.createChooser(intent, String("分享结果"))
            autoclass("org.kivy.android.PythonActivity").mActivity.startActivity(chooser)
        except Exception as exc:
            self.status = f"无法打开系统分享：{exc}"


if __name__ == "__main__":
    SimmcGDPApp().run()
