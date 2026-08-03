#!/usr/bin/env python3
"""从 Simmc squaremap 生成首都 GDP 与国家 GDP 降序榜。"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

import requests


DEFAULT_URL = "https://map.simmc.cn/tiles/minecraft_overworld/markers.json"
LAND_LAYER_ID = "lands_world"

NAME_RE = re.compile(r"font-size:\s*200%;.*?>(.*?)</span>", re.S)
BALANCE_RE = re.compile(r"余额:\s*\$([\d,]+(?:\.\d+)?)")
CHUNKS_RE = re.compile(r"区块:\s*(\d+)")
PLAYERS_RE = re.compile(r"玩家\(\d+\):\s*(.*?)</li>", re.S)
NATION_RE = re.compile(r"这块地属于国家\s*(.*?):</strong>", re.S)
CAPITAL_RE = re.compile(r"<li>首都:(.*?)</li>", re.S)
TAG_RE = re.compile(r"<[^>]+>")


@dataclass(frozen=True)
class Land:
    name: str
    balance: Decimal
    chunks: int
    x: int
    z: int
    nation: str | None
    capital: str | None
    players: tuple[str, ...]
    player_count: int
    polygons: tuple[tuple[tuple[float, float], ...], ...]


def clean(value: str) -> str:
    return " ".join(TAG_RE.sub("", html.unescape(value)).split())


def download_json(url: str, retries: int = 4) -> list[dict]:
    headers = {"User-Agent": "simmc-gdp-ranking/1.0"}
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            response = requests.get(url, headers=headers, timeout=(15, 120))
            response.raise_for_status()
            return response.json()
        except (requests.RequestException, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt + 1 < retries:
                time.sleep(2**attempt)
    raise RuntimeError(f"下载地图数据失败（已重试 {retries} 次）：{last_error}")


def load_json(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def marker_center(marker: dict) -> tuple[int, int]:
    points: list[dict] = []

    def collect(value: object) -> None:
        if isinstance(value, dict) and "x" in value and "z" in value:
            points.append(value)
        elif isinstance(value, list):
            for item in value:
                collect(item)

    collect(marker.get("points", []))
    if not points:
        point = marker.get("point") or marker
        if isinstance(point, dict) and "x" in point and "z" in point:
            points.append(point)
    if not points:
        return 0, 0
    return (
        round((min(p["x"] for p in points) + max(p["x"] for p in points)) / 2),
        round((min(p["z"] for p in points) + max(p["z"] for p in points)) / 2),
    )


def marker_polygons(marker: dict) -> tuple[tuple[tuple[float, float], ...], ...]:
    polygons: list[tuple[tuple[float, float], ...]] = []

    def collect(value: object) -> None:
        if isinstance(value, list) and value and all(
            isinstance(item, dict) and "x" in item and "z" in item for item in value
        ):
            polygons.append(tuple((float(item["x"]), float(item["z"])) for item in value))
        elif isinstance(value, list):
            for item in value:
                collect(item)

    collect(marker.get("points", []))
    return tuple(polygons)


def parse_lands(data: list[dict]) -> tuple[list[Land], int]:
    layer = next((item for item in data if item.get("id") == LAND_LAYER_ID), None)
    if layer is None:
        raise ValueError(f"找不到地图图层：{LAND_LAYER_ID}")

    lands: list[Land] = []
    skipped = 0
    for marker in layer.get("markers", []):
        popup = html.unescape(marker.get("popup") or marker.get("tooltip") or "")
        name_match = NAME_RE.search(popup)
        balance_match = BALANCE_RE.search(popup)
        chunks_match = CHUNKS_RE.search(popup)
        nation_match = NATION_RE.search(popup)
        capital_match = CAPITAL_RE.search(popup)
        players_match = PLAYERS_RE.search(popup)
        if name_match is None or balance_match is None or chunks_match is None:
            skipped += 1
            continue
        try:
            balance = Decimal(balance_match.group(1).replace(",", ""))
        except InvalidOperation:
            skipped += 1
            continue
        x, z = marker_center(marker)
        raw_players = players_match.group(1).split(",") if players_match else []
        players = tuple(
            value for player in raw_players
            if (value := clean(player)) and value not in {"...", "…"}
        )
        player_count_match = re.search(r"\((\d+)\):", players_match.group(0)) if players_match else None
        lands.append(
            Land(
                name=clean(name_match.group(1)),
                balance=balance,
                chunks=int(chunks_match.group(1)),
                x=x,
                z=z,
                nation=clean(nation_match.group(1)) if nation_match else None,
                capital=clean(capital_match.group(1)) if capital_match else None,
                players=players,
                player_count=int(player_count_match.group(1)) if player_count_match else len(players),
                polygons=marker_polygons(marker),
            )
        )
    return lands, skipped


def build_rankings(lands: list[Land]) -> tuple[list[dict], list[dict], list[str]]:
    grouped: dict[str, list[Land]] = defaultdict(list)
    for land in lands:
        if land.nation:
            grouped[land.nation].append(land)

    capitals: list[dict] = []
    nations: list[dict] = []
    warnings: list[str] = []
    for nation, members in grouped.items():
        capital_names = {land.capital for land in members if land.capital}
        if not capital_names:
            warnings.append(f"{nation} 没有首都字段，首都名称记为未知")
            capital_names = {"未知"}
        if len(capital_names) != 1:
            warnings.append(f"{nation} 出现多个首都字段：{', '.join(sorted(capital_names))}")
        capital = sorted(capital_names)[0]
        capital_lands = [land for land in members if land.name == capital]
        if not capital_lands:
            warnings.append(f"{nation} 的首都 {capital} 未在领地图层中找到，首都 GDP 记为 0")
        capital_gdp = sum((land.balance for land in capital_lands), Decimal("0"))
        capital_chunks = sum(land.chunks for land in capital_lands)
        nation_gdp = sum((land.balance for land in members), Decimal("0"))
        richest_balance = max(land.balance for land in members)
        richest_territories = sorted(
            {land.name for land in members if land.balance == richest_balance}
        )
        capitals.append({"nation": nation, "capital": capital, "gdp": capital_gdp, "chunks": capital_chunks})
        nations.append(
            {
                "nation": nation,
                "capital": capital,
                "gdp": nation_gdp,
                "territories": len(members),
                "richest_territory": "、".join(richest_territories),
            }
        )

    capitals.sort(key=lambda row: row["gdp"], reverse=True)
    nations.sort(key=lambda row: row["gdp"], reverse=True)
    return capitals, nations, warnings


def money(value: Decimal) -> str:
    return f"{value:,.2f}"


OUTPUT_HEADER = "该排名是从上到下降序排序，排行数字越小，代表排名越高"


def tab_row(*values: object) -> str:
    return "\t".join(str(value) for value in values)


def convert_text_outputs(paths: list[Path], output_format: str) -> list[Path]:
    for path in paths:
        for suffix in (".txt", ".xlsx", ".html"):
            candidate = path.with_suffix(suffix)
            if suffix != f".{output_format}" and candidate.exists() and candidate != path:
                candidate.unlink()
    if output_format == "txt":
        return paths
    converted: list[Path] = []
    for path in paths:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
        rows: list[list[str]] = []
        for line in lines:
            if not line:
                rows.append([])
            else:
                rows.append(line.split("\t"))

        target = path.with_suffix(f".{output_format}")
        if output_format == "xlsx":
            from openpyxl import Workbook
            from openpyxl.styles import Alignment, Font

            workbook = Workbook()
            sheet = workbook.active
            sheet.title = "数据"
            for row in rows:
                sheet.append(row)
            for row in sheet.iter_rows():
                for cell in row:
                    cell.alignment = Alignment(vertical="top", wrap_text=True)
            if rows:
                for cell in sheet[1]:
                    cell.font = Font(bold=True)
            for column in sheet.columns:
                length = max((len(str(cell.value or "")) for cell in column), default=10)
                sheet.column_dimensions[column[0].column_letter].width = min(max(length + 2, 12), 60)
            workbook.save(target)
        elif output_format == "html":
            title = html.escape(path.stem)
            table_rows = []
            for row in rows:
                if not row:
                    table_rows.append('<tr class="blank"><td></td></tr>')
                else:
                    cells = "".join(f"<td>{html.escape(value)}</td>" for value in row)
                    table_rows.append(f"<tr>{cells}</tr>")
            document = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>{title}</title>
<style>body{{font-family:"Microsoft YaHei",sans-serif;margin:24px}}table{{border-collapse:collapse;width:100%}}td{{border:1px solid #bbb;padding:6px 8px;vertical-align:top;white-space:normal}}tr:first-child td{{font-weight:bold;background:#eee}}.blank td{{border:0;height:8px}}</style>
</head><body><h1>{title}</h1><table>{''.join(table_rows)}</table></body></html>"""
            target.write_text(document, encoding="utf-8")
        else:
            raise ValueError(f"不支持的输出格式：{output_format}")
        path.unlink()
        converted.append(target)
    return converted


def write_text_rankings(output_dir: Path, capitals: list[dict], nations: list[dict]) -> None:
    capital_lines = [OUTPUT_HEADER, "", tab_row("国家名称", "国家首都", "首都区块", "首都存款", "排名次")]
    capital_lines += [
        tab_row(row["nation"], row["capital"], row["chunks"], money(row["gdp"]), f"第{rank}名")
        for rank, row in enumerate(capitals, 1)
    ]
    (output_dir / "国家首都排行.txt").write_text(
        "\n".join(capital_lines) + "\n", encoding="utf-8-sig"
    )

    nation_lines = [OUTPUT_HEADER, "", tab_row("国家名", "首都名", "国家最富有领土", "总存款", "排名次")]
    nation_lines += [
        tab_row(row["nation"], row["capital"], row["richest_territory"], money(row["gdp"]), f"第{rank}名")
        for rank, row in enumerate(nations, 1)
    ]
    (output_dir / "国家总体排行.txt").write_text(
        "\n".join(nation_lines) + "\n", encoding="utf-8-sig"
    )


def write_territory_details(output_dir: Path, lands: list[Land]) -> None:
    lines = [
        "坐标为领地范围中心点，区块数取自地图领地资料",
        "",
        tab_row("国家", "领地类型", "领地名称", "中心坐标", "所占区块", "存款"),
    ]
    ordered = sorted(
        lands,
        key=lambda land: (
            land.nation is None,
            land.nation or "",
            land.name != land.capital,
            land.name,
        ),
    )
    for land in ordered:
        nation = land.nation or "未加入国家"
        kind = "首都" if land.nation and land.name == land.capital else (
            "附属领地" if land.nation else "独立领地"
        )
        lines.append(tab_row(nation, kind, land.name, f"X={land.x}, Z={land.z}", land.chunks, money(land.balance)))
    (output_dir / "全部领地坐标及区块.txt").write_text(
        "\n".join(lines) + "\n", encoding="utf-8-sig"
    )


def write_nation_chunk_ranking(output_dir: Path, lands: list[Land]) -> None:
    grouped: dict[str, list[Land]] = defaultdict(list)
    for land in lands:
        if land.nation:
            grouped[land.nation].append(land)
    rows = sorted(
        ((nation, members[0].capital or "未知", sum(x.chunks for x in members), len(members))
         for nation, members in grouped.items()),
        key=lambda row: row[2],
        reverse=True,
    )
    lines = [
        "该排名是从上到下降序排序，排行数字越小，代表排名越高",
        "",
        tab_row("国家名", "首都名", "领地数量", "总区块数", "排名次"),
    ]
    lines += [
        tab_row(nation, capital, count, chunks, f"第{rank}名")
        for rank, (nation, capital, chunks, count) in enumerate(rows, 1)
    ]
    (output_dir / "国家所有领地区块排行.txt").write_text(
        "\n".join(lines) + "\n", encoding="utf-8-sig"
    )


def write_capital_coordinates(output_dir: Path, lands: list[Land]) -> None:
    grouped: dict[str, list[Land]] = defaultdict(list)
    for land in lands:
        if land.nation:
            grouped[land.nation].append(land)
    lines = ["坐标为首都领地范围中心点", "", tab_row("国家名", "首都名", "中心坐标", "首都区块数")]
    for nation, members in sorted(grouped.items()):
        capital = members[0].capital or "未知"
        capital_lands = [land for land in members if land.name == capital]
        if capital_lands:
            coordinates = "、".join(
                f"X={land.x}, Z={land.z}" for land in sorted(capital_lands, key=lambda x: (x.x, x.z))
            )
            lines.append(tab_row(nation, capital, coordinates, sum(x.chunks for x in capital_lands)))
        else:
            lines.append(tab_row(nation, capital, "地图领地图层中未找到", 0))
    (output_dir / "国家首都坐标表.txt").write_text(
        "\n".join(lines) + "\n", encoding="utf-8-sig"
    )


def point_in_polygon(x: float, z: float, polygon: tuple[tuple[float, float], ...]) -> bool:
    inside = False
    j = len(polygon) - 1
    for i, (xi, zi) in enumerate(polygon):
        xj, zj = polygon[j]
        if (zi > z) != (zj > z):
            crossing_x = (xj - xi) * (z - zi) / (zj - zi) + xi
            if x < crossing_x:
                inside = not inside
        j = i
    return inside


def land_area_hint(land: Land) -> float:
    points = [point for polygon in land.polygons for point in polygon]
    if not points:
        return float("inf")
    return (max(p[0] for p in points) - min(p[0] for p in points)) * (
        max(p[1] for p in points) - min(p[1] for p in points)
    )


def find_land_at(x: float, z: float, lands: list[Land]) -> Land | None:
    matches = [
        land for land in lands
        if any(point_in_polygon(x, z, polygon) for polygon in land.polygons)
    ]
    return min(matches, key=land_area_hint) if matches else None


def write_gateway_details(output_dir: Path, data: list[dict], lands: list[Land]) -> int:
    layer = next((item for item in data if item.get("id") == "transport_gateways"), None)
    if layer is None:
        raise ValueError("找不到地图图层：transport_gateways")
    rows = []
    for marker in layer.get("markers", []):
        point = marker.get("point", {})
        if "x" not in point or "z" not in point:
            continue
        # squaremap 悬浮时显示 tooltip；popup 是点击后的传送点描述。
        name = clean(marker.get("tooltip") or marker.get("popup") or "未命名")
        x, z = int(point["x"]), int(point["z"])
        land = find_land_at(x, z, lands)
        rows.append((land.nation if land and land.nation else "未加入国家", land.name if land else "未匹配领地", name, x, z))
    rows.sort(key=lambda row: (row[0] == "未加入国家", row[0], row[1], row[2]))
    lines = [
        "归属通过港口/驿站坐标落入领地多边形进行匹配",
        "",
        tab_row("所属国家", "所属领地", "名称", "坐标点"),
    ]
    lines += [tab_row(nation, land, name, f"X={x}, Z={z}") for nation, land, name, x, z in rows]
    (output_dir / "港口和驿站坐标.txt").write_text("\n".join(lines) + "\n", encoding="utf-8-sig")
    return len(rows)


def write_population_files(output_dir: Path, lands: list[Land]) -> None:
    for suffix in (".txt", ".xlsx", ".html"):
        obsolete = output_dir / f"国家及领地玩家成员列表{suffix}"
        if obsolete.exists():
            obsolete.unlink()
    grouped: dict[str, set[str]] = defaultdict(set)
    for land in lands:
        if land.nation:
            grouped[land.nation].update(land.players)
    rows = [("国家", nation, len(players)) for nation, players in grouped.items()]
    rows += [("独立领地", land.name, len(set(land.players))) for land in lands if not land.nation]
    rows.sort(key=lambda row: row[2], reverse=True)
    rank_lines = [OUTPUT_HEADER, "", tab_row("类型", "国家或独立领地名称", "玩家总数", "排名次")]
    rank_lines += [tab_row(kind, name, count, f"第{rank}名") for rank, (kind, name, count) in enumerate(rows, 1)]
    (output_dir / "国家及独立领地玩家总数排行.txt").write_text("\n".join(rank_lines) + "\n", encoding="utf-8-sig")


def write_world_land_gdp_ranking(output_dir: Path, lands: list[Land]) -> None:
    rows = sorted(lands, key=lambda land: land.balance, reverse=True)
    lines = [OUTPUT_HEADER, "", tab_row("领地名称", "所属国家", "领地类型", "领地存款", "排名次")]
    for rank, land in enumerate(rows, 1):
        kind = "首都" if land.nation and land.name == land.capital else ("附属领地" if land.nation else "独立领地")
        lines.append(tab_row(land.name, land.nation or "未加入国家", kind, money(land.balance), f"第{rank}名"))
    (output_dir / "全世界领地GDP排行.txt").write_text("\n".join(lines) + "\n", encoding="utf-8-sig")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=DEFAULT_URL, help="markers.json 地址")
    parser.add_argument("--input", type=Path, help="使用本地 markers.json，不联网")
    parser.add_argument("--output-dir", type=Path, default=Path("output"), help="输出目录")
    parser.add_argument("--format", choices=("txt", "xlsx", "html"), default="html", help="保存格式")
    parser.add_argument(
        "--task", choices=("gdp", "territories", "chunks", "capitals", "gateways", "population", "all"), default="all",
        help="生成内容：GDP、领地明细、区块排行、首都坐标或全部",
    )
    args = parser.parse_args()

    try:
        data = load_json(args.input) if args.input else download_json(args.url)
        lands, skipped = parse_lands(data)
        capitals, nations, warnings = build_rankings(lands)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        generated: list[Path] = []
        if args.task in ("gdp", "all"):
            write_text_rankings(args.output_dir, capitals, nations)
            write_world_land_gdp_ranking(args.output_dir, lands)
            generated += [args.output_dir / name for name in ("国家首都排行.txt", "国家总体排行.txt", "全世界领地GDP排行.txt")]
        if args.task in ("territories", "all"):
            write_territory_details(args.output_dir, lands)
            generated.append(args.output_dir / "全部领地坐标及区块.txt")
        if args.task in ("chunks", "all"):
            write_nation_chunk_ranking(args.output_dir, lands)
            generated.append(args.output_dir / "国家所有领地区块排行.txt")
        if args.task in ("capitals", "all"):
            write_capital_coordinates(args.output_dir, lands)
            generated.append(args.output_dir / "国家首都坐标表.txt")
        if args.task in ("gateways", "all"):
            write_gateway_details(args.output_dir, data, lands)
            generated.append(args.output_dir / "港口和驿站坐标.txt")
        if args.task in ("population", "all"):
            write_population_files(args.output_dir, lands)
            generated.append(args.output_dir / "国家及独立领地玩家总数排行.txt")
        convert_text_outputs(generated, args.format)
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1

    joined = sum(land.nation is not None for land in lands)
    print(f"完成：解析 {len(lands)} 块领地（国家领地 {joined}，未加入国家 {len(lands)-joined}）。")
    if skipped:
        print(f"警告：跳过 {skipped} 个字段不完整的国家领地标记。", file=sys.stderr)
    for warning in warnings:
        print(f"警告：{warning}", file=sys.stderr)
    print(f"输出目录：{args.output_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
