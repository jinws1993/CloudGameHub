"""内置 ROM 元数据库 - 不依赖外部 API, 提供基础元数据。

适用场景:
- 用户没有 ScreenScraper 开发者凭据
- 网络受限环境
- 离线首次扫描

数据来源: 公共知识 + 经典游戏数据, 可手动扩充。
"""
from __future__ import annotations
import re
from typing import Optional

# 经典 ROM 元数据库 (按文件名 -> 元数据)
# 用户可编辑此文件添加更多游戏
BUILTIN_DB: dict[str, dict] = {
    # 中文游戏名 也作为 key
    "塞尔达传说": {
        "title_en": "The Legend of Zelda",
        "title_zh": "塞尔达传说",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1986",
        "genre": "Action-adventure",
        "description_en": "Link's first adventure to rescue Princess Zelda and defeat Ganon.",
    },
    "马里奥": {
        "title_en": "Super Mario Bros.",
        "title_zh": "超级马里奥兄弟",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1985",
        "genre": "Platformer",
        "description_en": "The legendary platformer that defined the genre.",
    },
    "mario": {  # 短匹配, 含 mario 的都能匹配
        "title_en": "Super Mario Bros.",
        "title_zh": "超级马里奥兄弟",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1985",
        "genre": "Platformer",
        "description_en": "The legendary platformer that defined the genre.",
    },
    "mariokart": {
        "title_en": "Mario Kart",
        "title_zh": "马里奥赛车",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1996",
        "genre": "Racing",
        "description_en": "The original Mario Kart with mode-7 graphics.",
    },
    "魂斗罗": {
        "title_en": "Contra",
        "title_zh": "魂斗罗",
        "developer": "Konami",
        "publisher": "Konami",
        "year": "1987",
        "genre": "Run and gun",
        "description_en": "Two soldiers battle through alien forces in this classic run-and-gun shooter.",
    },
    "仙剑": {
        "title_en": "Chinese Paladin",
        "title_zh": "仙剑奇侠传",
        "developer": "Softstar",
        "publisher": "Softstar",
        "year": "1995",
        "genre": "JRPG",
        "description_en": "The classic Chinese JRPG about Li Xiaoyao's adventure.",
    },
    "时空之轮": {
        "title_en": "Chrono Trigger",
        "title_zh": "时空之轮",
        "developer": "Square",
        "publisher": "Square",
        "year": "1995",
        "genre": "JRPG",
        "description_en": "A time-traveling JRPG masterpiece by the dream team of Sakaguchi, Horii, and Toriyama.",
    },
    "最终幻想": {
        "title_en": "Final Fantasy VII",
        "title_zh": "最终幻想 7",
        "developer": "Square",
        "publisher": "Square",
        "year": "1997",
        "genre": "JRPG",
        "description_en": "Cloud Strife joins the eco-terrorist group AVALANCHE in this genre-defining JRPG.",
    },
    "街头霸王": {
        "title_en": "Street Fighter Alpha 3",
        "title_zh": "街头霸王 Alpha 3",
        "developer": "Capcom",
        "publisher": "Capcom",
        "year": "1998",
        "genre": "Fighting",
        "description_en": "An entry in the Street Fighter Alpha sub-series.",
    },
    # Nintendo FC (NES)
    "super mario bros": {
        "title_en": "Super Mario Bros.",
        "title_zh": "超级马里奥兄弟",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1985",
        "genre": "Platformer",
        "description_en": "The legendary platformer that defined the genre. Mario must rescue Princess Toadstool from Bowser.",
    },
    "super mario bros 2": {
        "title_en": "Super Mario Bros. 2",
        "title_zh": "超级马里奥兄弟 2",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1988",
        "genre": "Platformer",
        "description_en": "Mario and friends journey to Subcon to defeat the evil Wart.",
    },
    "super mario bros 3": {
        "title_en": "Super Mario Bros. 3",
        "title_zh": "超级马里奥兄弟 3",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1990",
        "genre": "Platformer",
        "description_en": "The acclaimed sequel featuring the Tanooki suit and world map.",
    },
    "contra": {
        "title_en": "Contra",
        "title_zh": "魂斗罗",
        "developer": "Konami",
        "publisher": "Konami",
        "year": "1987",
        "genre": "Run and gun",
        "description_en": "Two soldiers battle through alien forces in this classic run-and-gun shooter.",
    },
    "zelda": {
        "title_en": "The Legend of Zelda",
        "title_zh": "塞尔达传说",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1986",
        "genre": "Action-adventure",
        "description_en": "Link's first adventure to rescue Princess Zelda and defeat Ganon.",
    },
    "tetris": {
        "title_en": "Tetris",
        "title_zh": "俄罗斯方块",
        "developer": "Alexey Pajitnov",
        "publisher": "Nintendo",
        "year": "1989",
        "genre": "Puzzle",
        "description_en": "The timeless puzzle game of falling blocks.",
    },
    "mario kart": {
        "title_en": "Mario Kart",
        "title_zh": "马里奥赛车",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "year": "1996",
        "genre": "Racing",
        "description_en": "The original Mario Kart with mode-7 graphics.",
    },
    "final fantasy vii": {
        "title_en": "Final Fantasy VII",
        "title_zh": "最终幻想 7",
        "developer": "Square",
        "publisher": "Square",
        "year": "1997",
        "genre": "JRPG",
        "description_en": "Cloud Strife joins the eco-terrorist group AVALANCHE in this genre-defining JRPG.",
    },
    "final fantasy": {
        "title_en": "Final Fantasy",
        "title_zh": "最终幻想",
        "developer": "Square",
        "publisher": "Square",
        "year": "1987",
        "genre": "JRPG",
        "description_en": "The first Final Fantasy game.",
    },
    "chrono trigger": {
        "title_en": "Chrono Trigger",
        "title_zh": "时空之轮",
        "developer": "Square",
        "publisher": "Square",
        "year": "1995",
        "genre": "JRPG",
        "description_en": "A time-traveling JRPG masterpiece by the dream team of Sakaguchi, Horii, and Toriyama.",
    },
    "street fighter": {
        "title_en": "Street Fighter Alpha 3",
        "title_zh": "街头霸王 Alpha 3",
        "developer": "Capcom",
        "publisher": "Capcom",
        "year": "1998",
        "genre": "Fighting",
        "description_en": "An entry in the Street Fighter Alpha sub-series with three selectable fighting styles.",
    },
    "pokemon": {
        "title_en": "Pokémon",
        "title_zh": "口袋妖怪",
        "developer": "Game Freak",
        "publisher": "Nintendo",
        "year": "1996",
        "genre": "RPG",
        "description_en": "The original Pokémon Red/Blue adventures.",
    },
    "xianjian": {
        "title_en": "Chinese Paladin",
        "title_zh": "仙剑奇侠传",
        "developer": "Softstar",
        "publisher": "Softstar",
        "year": "1995",
        "genre": "JRPG",
        "description_en": "The classic Chinese JRPG about Li Xiaoyao's adventure.",
    },
}


def lookup_builtin(filename: str) -> Optional[dict]:
    """在内置数据库里查 ROM 的元数据。返回 None 表示未找到。"""
    if not filename:
        return None
    # 去除扩展名 + 转小写
    stem = re.sub(r"\.[^.]+$", "", filename).lower()
    # 去除 (USA) (Rev 1) [!] 之类的标记
    stem = re.sub(r"\s*[\(\[].*?[\)\]]", "", stem)
    # 下划线、点、破折号都看作分隔符
    stem = re.sub(r"[_\-.]+", " ", stem)
    stem = re.sub(r"\s+", " ", stem).strip()

    # 直接匹配
    for key, val in BUILTIN_DB.items():
        if key in stem:
            return val.copy()

    # 模糊匹配 (去掉数字差异)
    stem_compact = re.sub(r"[^a-z0-9 ]", "", stem)
    for key, val in BUILTIN_DB.items():
        if key in stem_compact:
            return val.copy()

    return None
