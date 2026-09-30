"""Translation helpers. Uses deep-translator (free, no key) or OpenAI if configured."""
from __future__ import annotations
import asyncio
from typing import Optional
from loguru import logger

try:
    from deep_translator import GoogleTranslator
    HAS_GOOGLE = True
except Exception:
    HAS_GOOGLE = False

try:
    from openai import AsyncOpenAI
    HAS_OPENAI = True
except Exception:
    HAS_OPENAI = False


async def detect_language(text: str) -> str:
    """Detect language code. Simple heuristic; can be improved."""
    if not text or len(text) < 4:
        return "en"
    # Japanese hiragana/katakana
    ja_chars = sum(1 for c in text if '\u3040' <= c <= '\u30ff')
    cn_chars = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    if ja_chars > 2:
        return "ja"
    if cn_chars > 4 and ja_chars == 0:
        return "zh"
    return "en"


async def translate(text: str, target: str = "zh-CN",
                    ai_client: Optional["AsyncOpenAI"] = None,
                    ai_model: str = "gpt-4o-mini") -> str:
    """Translate text to target language.
    Priority: AI (if client provided) -> Google -> original.
    """
    if not text or not text.strip():
        return text

    src = await detect_language(text)
    if src == target.split("-")[0]:
        return text

    # Try AI first (better for game descriptions)
    if ai_client is not None:
        try:
            resp = await ai_client.chat.completions.create(
                model=ai_model,
                messages=[
                    {"role": "system",
                     "content": "You are a professional game localization translator. "
                                "Translate accurately, keep game-specific terms, "
                                "preserve tone. Output ONLY the translated text."},
                    {"role": "user",
                     "content": f"Translate to Simplified Chinese:\n\n{text[:4000]}"}
                ],
                temperature=0.2,
                max_tokens=2000,
            )
            content = resp.choices[0].message.content
            # Reasoning models (MiniMax-M2, DeepSeek-R1, o1) 会先输出
            # <think>...</think> 思考块. 剥离后再取最后纯翻译内容.
            import re as _re
            content_clean = _re.sub(r"<think>.*?</think>", "", content, flags=_re.DOTALL).strip()
            # 进一步清理: 如果剩的是思考型文本 (含 \n, 长度>5倍预期, 字母为主),
            # 试取最后一段 (通常模型会在思考块之后输出最终翻译)
            if content_clean and len(content_clean) > len(text) * 4:
                # 取最后 200 字符, 作为潜在纯翻译部分
                tail = content_clean[-200:].strip()
                # 计算中文字符比例
                cn_chars = sum(1 for c in tail if '\u4e00' <= c <= '\u9fff')
                if cn_chars > 5 and cn_chars / len(tail) > 0.3:
                    # 看起来是中文翻译, 截取该段
                    content_clean = tail
            if not content_clean:
                # 整个响应都是 thinking, fallback 到 google
                logger.warning(f"AI translate returned only <think> for '{text[:40]}'")
                raise ValueError("AI translate returned empty after stripping think")
            return content_clean
        except Exception as e:
            logger.warning(f"AI translate failed, fallback to google: {e}")

    if HAS_GOOGLE:
        try:
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                None,
                lambda: GoogleTranslator(source="auto", target=target.split("-")[0]).translate(text[:4500]),
            )
            return result or text
        except Exception as e:
            logger.warning(f"Google translate failed: {e}")

    return text
