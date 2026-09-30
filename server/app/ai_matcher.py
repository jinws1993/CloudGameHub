"""AI-powered game identification and metadata enhancement."""
from __future__ import annotations
import json
import re
from typing import Optional
from loguru import logger

try:
    from openai import AsyncOpenAI
    HAS_OPENAI = True
except Exception:
    HAS_OPENAI = False


SYSTEM_PROMPT = """You are an expert retro game database assistant.
Given a messy ROM filename, you identify the real game and return structured metadata.
Always respond with a single JSON object (no markdown, no explanation):

{
  "title_en": "Official English title",
  "title_jp": "Original Japanese title (or empty)",
  "title_zh": "Official/commonly-used Chinese title",
  "year": "1996",
  "developer": "Square",
  "publisher": "Square",
  "genre": "RPG",
  "players": "1",
  "description_en": "2-3 sentences in English describing the game",
  "description_zh": "Same description translated to Simplified Chinese (or empty if uncertain)",
  "platform": "PS1",
  "confidence": 0.95
}

Rules:
- If uncertain, lower the confidence score.
- Don't invent details. Use empty string if you don't know.
- For Chinese titles, prefer the common-used name (e.g. "最终幻想7" not "太空战士7").
- platform must be one of: FC, SFC, N64, GB, GBC, GBA, NDS, 3DS, MD, SATURN, DC, PS1, PS2, PSP, WII, GC, PCE, NEOGEO, MAME, ARCADE, J2ME, DOS, WIN, FLASH, HTML
"""


class AIMatcher:
    def __init__(self, base_url: str, api_key: str, model: str = "gpt-4o-mini"):
        # Allow local mock for development
        import os as _os
        if _os.environ.get("NASGAME_MOCK_AI") == "1":
            from . import mock_ai
            self._mock = mock_ai.MockAI()
            self.client = None
            self.model = model
            self.enabled = True
            return
        self.enabled = bool(api_key) and HAS_OPENAI
        if not self.enabled:
            self.client = None
            self.model = model  # 始终设值, 避免属性访问错误
            logger.info("AI matcher disabled (no key or openai not installed)")
            return
        self.client = AsyncOpenAI(base_url=base_url, api_key=api_key)
        self.model = model

    async def identify(self, filename: str, platform_hint: str = "",
                       extra_hint: str = "") -> dict | None:
        """Identify a game from filename. Returns dict or None on failure."""
        if not self.enabled:
            return None

        # Mock path
        if hasattr(self, "_mock"):
            return await self._mock.identify(filename)

        user_msg = f"ROM filename: {filename}\n"
        if platform_hint:
            user_msg += f"Platform hint: {platform_hint}\n"
        if extra_hint:
            user_msg += f"Additional hint: {extra_hint}\n"

        try:
            resp = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_msg},
                ],
                temperature=0.2,
                response_format={"type": "json_object"},
            )
            content = resp.choices[0].message.content
            # Reasoning models (MiniMax-M2, DeepSeek-R1, o1 等) 会先输出
            # <think>...</think> 思考块, 里面可能含未闭合的 JSON 片段.
            # 剥离 <think> 块后再 parse.
            import re as _re
            content_clean = _re.sub(r"<think>.*?</think>", "", content, flags=_re.DOTALL).strip()
            if not content_clean:
                # 整个响应都是 thinking, 视为 AI 返回空
                logger.warning(f"AI returned only <think> block for '{filename}'")
                return None
            try:
                data = json.loads(content_clean)
            except json.JSONDecodeError as e:
                # 可能是 markdown 代码块围起来的 JSON
                m = _re.search(r"```(?:json)?\s*(\{.*?\})\s*```", content_clean, flags=_re.DOTALL)
                if m:
                    data = json.loads(m.group(1))
                else:
                    logger.warning(f"AI returned non-JSON for '{filename}': {content_clean[:200]}")
                    return None
            # 容错: 模型有时会用 title/releaseYear 等别名, 映射到规范字段
            field_aliases = {
                "title": "title_en",
                "name": "title_en",
                "releaseYear": "year",
                "release_year": "year",
                "released": "year",
                "desc": "description_en",
                "description": "description_en",
                "genre": "genre",
            }
            for alias, canon in field_aliases.items():
                if alias in data and canon not in data:
                    data[canon] = data[alias]
            # 确保关键字段存在
            for f in ("title_en", "title_zh", "year", "developer", "publisher",
                      "genre", "players", "description_en", "description_zh"):
                data.setdefault(f, "")
            return data
        except Exception as e:
            logger.warning(f"AI identify failed for '{filename}': {e}")
            return None

    async def enhance_description(self, title_en: str, desc_en: str) -> str | None:
        """Use AI to clean up / enhance an English description."""
        if not self.enabled or not desc_en:
            return None
        try:
            resp = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system",
                     "content": "You are a game encyclopedia editor. "
                                "Polish and enhance the game description. "
                                "Keep facts accurate, fix grammar, expand if too brief. "
                                "Output only the improved text."},
                    {"role": "user", "content": f"Game: {title_en}\n\n{desc_en[:3000]}"},
                ],
                temperature=0.3,
            )
            return resp.choices[0].message.content.strip()
        except Exception as e:
            logger.warning(f"AI enhance description failed: {e}")
            return None
