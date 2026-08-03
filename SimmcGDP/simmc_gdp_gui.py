#!/usr/bin/env python3
"""Simmc GDP 排名生成器的 Tkinter 图形界面。"""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

import simmc_gdp
from PIL import Image, ImageTk


BASE_DIR = (
    Path(sys.executable).resolve().parent
    if getattr(sys, "frozen", False)
    else Path(__file__).resolve().parent
)
RESOURCE_DIR = Path(getattr(sys, "_MEIPASS", BASE_DIR)) / "assets"
ABOUT_BASE_DIR = Path(getattr(sys, "_MEIPASS", BASE_DIR))
OUTPUT_DIR = BASE_DIR / "output"


def center_window(window: tk.Misc, width: int, height: int) -> None:
    screen_width = window.winfo_screenwidth()
    screen_height = window.winfo_screenheight()
    x = max(0, (screen_width - width) // 2)
    y = max(0, (screen_height - height) // 2)
    window.geometry(f"{width}x{height}+{x}+{y}")


class App:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("Simmc 信息生成器")
        center_window(self.root, 760, 480)
        self.root.resizable(False, False)
        try:
            self.root.iconbitmap(default=str(RESOURCE_DIR / "simmc.ico"))
        except tk.TclError:
            pass
        self.messages: queue.Queue[tuple[str, object]] = queue.Queue()

        self.gdp_var = tk.BooleanVar(value=True)
        self.territories_var = tk.BooleanVar(value=True)
        self.chunks_var = tk.BooleanVar(value=True)
        self.capitals_var = tk.BooleanVar(value=True)
        self.gateways_var = tk.BooleanVar(value=True)
        self.population_var = tk.BooleanVar(value=True)
        self.format_var = tk.StringVar(value="HTML")

        self._build_ui()
        self.root.after(100, self._poll_messages)

    def _build_ui(self) -> None:
        outer = ttk.Frame(self.root, padding=18)
        outer.pack(fill="both", expand=True)

        content = ttk.Frame(outer)
        content.pack(fill="both", expand=True)

        left = ttk.Frame(content, width=285)
        left.pack(side="left", fill="y", padx=(0, 14))
        left.pack_propagate(False)
        right = ttk.Frame(content)
        right.pack(side="right", fill="both", expand=True)

        choices = ttk.LabelFrame(left, text="选择输出内容", padding=12)
        choices.pack(fill="x")
        ttk.Checkbutton(
            choices,
            text="国家首都排行 + 国家总体排行",
            variable=self.gdp_var,
        ).pack(anchor="w", pady=3)
        ttk.Checkbutton(
            choices,
            text="全部领地坐标及区块",
            variable=self.territories_var,
        ).pack(anchor="w", pady=3)
        ttk.Checkbutton(
            choices,
            text="国家所有领地区块排行",
            variable=self.chunks_var,
        ).pack(anchor="w", pady=3)
        ttk.Checkbutton(
            choices,
            text="国家首都坐标表",
            variable=self.capitals_var,
        ).pack(anchor="w", pady=3)
        ttk.Checkbutton(
            choices,
            text="港口和驿站坐标及归属",
            variable=self.gateways_var,
        ).pack(anchor="w", pady=3)
        ttk.Checkbutton(
            choices,
            text="国家与独立领地人口排行",
            variable=self.population_var,
        ).pack(anchor="w", pady=3)
        format_row = ttk.Frame(choices)
        format_row.pack(fill="x", pady=(8, 0))
        ttk.Label(format_row, text="保存格式：").pack(side="left")
        ttk.Combobox(
            format_row, textvariable=self.format_var, values=("TXT", "XLSX", "HTML"),
            state="readonly", width=9,
        ).pack(side="left")

        buttons = ttk.LabelFrame(left, text="操作", padding=12)
        buttons.pack(fill="x", pady=(14, 0))
        self.generate_button = ttk.Button(buttons, text="开始生成", command=self.start_generation)
        self.generate_button.pack(fill="x", pady=(0, 7))
        ttk.Button(buttons, text="全选", command=self.select_all).pack(fill="x", pady=(0, 7))
        ttk.Button(buttons, text="打开输出目录", command=self.open_output).pack(fill="x", pady=(0, 7))
        ttk.Button(buttons, text="关于", command=self.show_about).pack(fill="x", pady=(0, 7))
        ttk.Button(buttons, text="退出", command=self.root.destroy).pack(fill="x")

        self.progress = ttk.Progressbar(left, mode="indeterminate")
        self.progress.pack(fill="x", pady=(14, 0))

        log_frame = ttk.LabelFrame(right, text="运行日志", padding=8)
        log_frame.pack(fill="both", expand=True)
        self.log = tk.Text(log_frame, height=12, wrap="word", state="disabled")
        scrollbar = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=scrollbar.set)
        self.log.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

    def select_all(self) -> None:
        self.gdp_var.set(True)
        self.territories_var.set(True)
        self.chunks_var.set(True)
        self.capitals_var.set(True)
        self.gateways_var.set(True)
        self.population_var.set(True)

    def append_log(self, text: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def start_generation(self) -> None:
        selected = (
            self.gdp_var.get(), self.territories_var.get(), self.chunks_var.get(),
            self.capitals_var.get(), self.gateways_var.get(), self.population_var.get(),
        )
        output_format = self.format_var.get().lower()
        if not any(selected):
            messagebox.showwarning("没有选择", "请至少选择一种输出内容。")
            return
        self.generate_button.configure(state="disabled")
        self.progress.start(12)
        self.append_log("开始获取最新地图数据……")
        threading.Thread(target=self._generate, args=(selected, output_format), daemon=True).start()

    def _generate(self, selected: tuple[bool, bool, bool, bool, bool, bool], output_format: str) -> None:
        try:
            data = simmc_gdp.download_json(simmc_gdp.DEFAULT_URL)
            self.messages.put(("log", "地图数据下载完成，正在解析领地……"))
            lands, skipped = simmc_gdp.parse_lands(data)
            capitals, nations, warnings = simmc_gdp.build_rankings(lands)
            OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
            generated: list[Path] = []

            if selected[0]:
                simmc_gdp.write_text_rankings(OUTPUT_DIR, capitals, nations)
                simmc_gdp.write_world_land_gdp_ranking(OUTPUT_DIR, lands)
                generated += [OUTPUT_DIR / name for name in ("国家首都排行.txt", "国家总体排行.txt", "全世界领地GDP排行.txt")]
                self.messages.put(("log", "已生成：三份 GDP 排行（含全世界领地排行）"))
            if selected[1]:
                simmc_gdp.write_territory_details(OUTPUT_DIR, lands)
                generated.append(OUTPUT_DIR / "全部领地坐标及区块.txt")
                self.messages.put(("log", "已生成：全部领地坐标及区块"))
            if selected[2]:
                simmc_gdp.write_nation_chunk_ranking(OUTPUT_DIR, lands)
                generated.append(OUTPUT_DIR / "国家所有领地区块排行.txt")
                self.messages.put(("log", "已生成：国家所有领地区块排行"))
            if selected[3]:
                simmc_gdp.write_capital_coordinates(OUTPUT_DIR, lands)
                generated.append(OUTPUT_DIR / "国家首都坐标表.txt")
                self.messages.put(("log", "已生成：国家首都坐标表"))
            if selected[4]:
                gateway_count = simmc_gdp.write_gateway_details(OUTPUT_DIR, data, lands)
                generated.append(OUTPUT_DIR / "港口和驿站坐标.txt")
                self.messages.put(("log", f"已生成：{gateway_count} 个港口和驿站的坐标及归属"))
            if selected[5]:
                simmc_gdp.write_population_files(OUTPUT_DIR, lands)
                generated.append(OUTPUT_DIR / "国家及独立领地玩家总数排行.txt")
                self.messages.put(("log", "已生成：国家及独立领地人口排行"))

            converted = simmc_gdp.convert_text_outputs(generated, output_format)
            self.messages.put(("log", f"已保存 {len(converted)} 个 {output_format.upper()} 文件"))
            hidden_players = sum(max(0, land.player_count - len(land.players)) for land in lands)
            truncated_lands = sum(land.player_count > len(land.players) for land in lands)
            if truncated_lands:
                self.messages.put(("log", f"注意：地图有 {truncated_lands} 块领地的成员名单被截断，{hidden_players} 个成员ID未由网页公开。"))

            joined = sum(land.nation is not None for land in lands)
            summary = (
                f"完成：共解析 {len(lands)} 块领地，其中 {joined} 块属于国家，"
                f"{len(lands) - joined} 块未加入国家。"
            )
            if skipped:
                summary += f" 跳过 {skipped} 个字段不完整的标记。"
            for warning in warnings:
                self.messages.put(("log", "警告：" + warning))
            self.messages.put(("done", summary))
        except Exception as exc:  # GUI 须将所有后台异常反馈给用户
            self.messages.put(("error", str(exc)))

    def _poll_messages(self) -> None:
        try:
            while True:
                kind, value = self.messages.get_nowait()
                if kind == "log":
                    self.append_log(str(value))
                elif kind == "done":
                    self._finish("生成完成")
                    self.append_log(str(value))
                    messagebox.showinfo("完成", f"{value}\n\n文件已保存到：\n{OUTPUT_DIR}")
                elif kind == "error":
                    self._finish("运行失败")
                    self.append_log("错误：" + str(value))
                    messagebox.showerror("生成失败", str(value))
        except queue.Empty:
            pass
        self.root.after(100, self._poll_messages)

    def _finish(self, status: str) -> None:
        self.progress.stop()
        self.generate_button.configure(state="normal")

    def open_output(self) -> None:
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        try:
            if os.name == "nt":
                os.startfile(OUTPUT_DIR)  # type: ignore[attr-defined]
            else:
                subprocess.Popen(["xdg-open", str(OUTPUT_DIR)])
        except OSError as exc:
            messagebox.showerror("无法打开目录", str(exc))

    def show_about(self) -> None:
        dialog = tk.Toplevel(self.root)
        dialog.title("关于")
        center_window(dialog, 640, 330)
        dialog.resizable(False, False)
        dialog.transient(self.root)
        try:
            dialog.iconbitmap(default=str(RESOURCE_DIR / "simmc.ico"))
        except tk.TclError:
            pass

        frame = ttk.Frame(dialog, padding=16)
        frame.pack(fill="both", expand=True)
        body = ttk.Frame(frame)
        body.pack(fill="both", expand=True)
        text_panel = ttk.Frame(body, width=260)
        text_panel.pack(side="left", fill="both", expand=True, padx=(0, 12))
        text_panel.pack_propagate(False)
        image_panel = ttk.Frame(body, width=320)
        image_panel.pack(side="right", fill="y")
        image_panel.pack_propagate(False)
        text_path = ABOUT_BASE_DIR / "somesin.txt"
        try:
            about_text = text_path.read_text(encoding="utf-8-sig").strip()
        except OSError as exc:
            about_text = f"无法读取 somesin.txt：{exc}"
        ttk.Label(
            text_panel, text=about_text, justify="left", anchor="nw", wraplength=245
        ).pack(fill="both", expand=True)

        image_paths = []
        image_dir = ABOUT_BASE_DIR / "somesin"
        if image_dir.is_dir():
            image_paths = sorted(
                path for path in image_dir.iterdir()
                if path.suffix.lower() in {".gif", ".png", ".ppm", ".pgm"}
            )
        dialog.about_images = []  # type: ignore[attr-defined]
        dialog.about_delays = []  # type: ignore[attr-defined]
        for image_path in image_paths:
            try:
                with Image.open(image_path) as source:
                    frame_count = getattr(source, "n_frames", 1)
                    for index in range(frame_count):
                        source.seek(index)
                        # Pillow 在 seek 时应用 GIF disposal，并生成完整合成帧。
                        composited_frame = source.convert("RGBA").copy()
                        composited_frame.thumbnail((320, 240), Image.Resampling.LANCZOS)
                        dialog.about_images.append(ImageTk.PhotoImage(composited_frame))  # type: ignore[attr-defined]
                        dialog.about_delays.append(max(20, int(source.info.get("duration", 100))))  # type: ignore[attr-defined]
            except (OSError, ValueError):
                continue
        if dialog.about_images:  # type: ignore[attr-defined]
            image_label = ttk.Label(image_panel, image=dialog.about_images[0])  # type: ignore[attr-defined]
            image_label.pack(anchor="n", pady=2)

            def animate(frame: int = 0) -> None:
                if not dialog.winfo_exists():
                    return
                frames = dialog.about_images  # type: ignore[attr-defined]
                image_label.configure(image=frames[frame % len(frames)])
                delays = dialog.about_delays  # type: ignore[attr-defined]
                dialog.after(delays[frame % len(delays)], animate, frame + 1)

            animate()
        ttk.Button(frame, text="关闭", command=dialog.destroy).pack(side="bottom", pady=(10, 0))


def main() -> None:
    root = tk.Tk()
    try:
        ttk.Style().theme_use("vista")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
