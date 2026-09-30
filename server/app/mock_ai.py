"""Mock AI for end-to-end demo when no real API key available.

This module lets the scrape pipeline exercise the AI code path even when
no OpenAI key is configured. It is enabled by setting NASGAME_MOCK_AI=1.
"""
from __future__ import annotations
from typing import Optional

MOCK_DB = {
    "super mario bros": {
        "title_en": "Super Mario Bros.",
        "title_jp": "スーパーマリオブラザーズ",
        "title_zh": "超级马里奥兄弟",
        "year": "1985",
        "developer": "Nintendo",
        "publisher": "Nintendo",
        "genre": "Platformer",
        "players": "2",
        "rating": 9.2,
        "description_en": "Super Mario Bros. is a 1985 platform game developed and published by Nintendo for the NES. It follows Mario as he fights Bowser's forces to rescue Princess Toadstool.",
        "confidence": 0.99,
    },
    "final fantasy vii": {
        "title_en": "Final Fantasy VII",
        "title_jp": "ファイナルファンタジーVII",
        "title_zh": "最终幻想7",
        "year": "1997",
        "developer": "Square",
        "publisher": "Square",
        "genre": "JRPG",
        "players": "1",
        "rating": 9.3,
        "description_en": "Final Fantasy VII is a 1997 role-playing video game developed by Square for the PlayStation. The story follows Cloud Strife, a mercenary who joins an eco-terrorist group to stop the megacorporation Shinra.",
        "confidence": 0.99,
    },
    "street fighter alpha 3": {
        "title_en": "Street Fighter Alpha 3",
        "title_jp": "ストリートファイターALPHA3",
        "title_zh": "街头霸王 Alpha 3",
        "year": "1998",
        "developer": "Capcom",
        "publisher": "Capcom",
        "genre": "Fighting",
        "players": "2",
        "rating": 8.9,
        "description_en": "Street Fighter Alpha 3 is a 1998 fighting game by Capcom, the third game in the Street Fighter Alpha series.",
        "confidence": 0.97,
    },
    "mario kart wii": {
        "title_en": "Mario Kart Wii",
        "title_jp": "マリオカートWii",
        "title_zh": "马里奥赛车Wii",
        "year": "2008",
        "developer": "Nintendo EAD",
        "publisher": "Nintendo",
        "genre": "Racing",
        "players": "4",
        "rating": 8.7,
        "description_en": "Mario Kart Wii is a 2008 kart racing game developed and published by Nintendo for the Wii. It is the sixth installment in the Mario Kart series.",
        "confidence": 0.98,
    },
    "クロノ・トリガー": {
        "title_en": "Chrono Trigger",
        "title_jp": "クロノ・トリガー",
        "title_zh": "时空之轮",
        "year": "1995",
        "developer": "Square",
        "publisher": "Square",
        "genre": "JRPG",
        "players": "1",
        "rating": 9.5,
        "description_en": "Chrono Trigger is a 1995 role-playing video game developed and published by Square for the Super Nintendo. It is regarded as one of the greatest video games of all time.",
        "confidence": 0.99,
    },
    "仙剑奇侠传": {
        "title_en": "Chinese Paladin",
        "title_jp": "",
        "title_zh": "仙剑奇侠传",
        "year": "1995",
        "developer": "Softstar",
        "publisher": "Softstar",
        "genre": "JRPG",
        "players": "1",
        "rating": 9.1,
        "description_en": "Chinese Paladin (Xianjian Qixia Zhuan) is a 1995 Chinese role-playing video game developed by Softstar. It follows a young martial artist on a journey to rescue a princess.",
        "confidence": 0.99,
    },
    "neogeo": {
        "title_en": "Neo Geo BIOS",
        "title_jp": "",
        "title_zh": "Neo Geo 启动文件",
        "year": "1990",
        "developer": "SNK",
        "publisher": "SNK",
        "genre": "System",
        "players": "0",
        "rating": 0,
        "description_en": "NeoGeo BIOS file - required system firmware for Neo Geo emulation.",
        "confidence": 0.9,
    },
}


class MockAI:
    """Drop-in replacement for AIMatcher for local testing."""

    def __init__(self, *args, **kwargs):
        self.enabled = True

    async def identify(self, filename: str, **kw) -> dict | None:
        stem = filename.lower().split("(")[0].strip()
        for key, info in MOCK_DB.items():
            k = key.lower()
            if k in stem or k in filename.lower():
                return info
        return {
            "title_en": stem or "Unknown Game",
            "title_zh": stem or "未知游戏",
            "year": "",
            "developer": "",
            "publisher": "",
            "genre": "Unknown",
            "players": "",
            "rating": 0,
            "description_en": f"Auto-identified from filename '{filename}'. Low confidence - please edit.",
            "confidence": 0.3,
        }
