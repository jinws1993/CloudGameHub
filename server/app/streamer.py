"""Game streaming service.
Provides:
  - ROM download over HTTP (chunked)
  - WebSocket control channel
  - H.264 stream via FFmpeg (in-container)
  - Save state sync
For production, this is a thin wrapper; actual encoding can be offloaded to GPU
or an external Sunshine/Moonlight setup if available.
"""
from __future__ import annotations
import asyncio
import json
import shutil
import subprocess
from pathlib import Path
from datetime import datetime
from typing import Optional
from loguru import logger

from .config import settings


class StreamSession:
    def __init__(self, session_id: str, user_id: int, game_id: int,
                 rom_path: Path, platform_code: str, mode: str = "stream"):
        self.session_id = session_id
        self.user_id = user_id
        self.game_id = game_id
        self.rom_path = rom_path
        self.platform_code = platform_code
        self.mode = mode
        self.started_at = datetime.utcnow()
        self.ended_at: Optional[datetime] = None
        self.process: Optional[subprocess.Popen] = None
        self.ffmpeg: Optional[subprocess.Popen] = None
        self.control_ws = None
        self.video_ws = None

    async def start_local_emulator(self, emulator_path: str | None = None) -> bool:
        """Start the game via local emulator (server-side execution)."""
        if not self.rom_path.exists():
            return False
        emu_cfg = settings.emulator_map.get(self.platform_code, {})
        cmd = emulator_path or emu_cfg.get("win") or emu_cfg.get("type", "")
        if not cmd:
            logger.warning(f"No emulator for platform {self.platform_code}")
            return False

        full = [cmd, str(self.rom_path)]
        try:
            self.process = subprocess.Popen(full, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return True
        except Exception as e:
            logger.exception(f"Failed to start emulator: {e}")
            return False

    async def start_stream(self, ws_control, ws_video) -> bool:
        """Start the streaming session.
        On the server-side, we launch the emulator headlessly (Xvfb) and pipe
        its framebuffer through FFmpeg to h264 -> over WebSocket to client.
        """
        self.control_ws = ws_control
        self.video_ws = ws_video

        # Start emulator with virtual display
        emu_cfg = settings.emulator_map.get(self.platform_code, {})
        emu_cmd = emu_cfg.get("win") or emu_cfg.get("type", "")
        if not emu_cmd:
            return False

        display = ":99"
        env = {"DISPLAY": display, "XAUTHORITY": "/tmp/.X99-auth"}
        try:
            # Xvfb may not be available in slim image - start it if present
            xvfb = subprocess.Popen(["Xvfb", display, "-screen", "0", "1280x720x24"],
                                    env=env, stderr=subprocess.DEVNULL)
            await asyncio.sleep(0.5)

            self.process = subprocess.Popen(
                [emu_cmd, str(self.rom_path)],
                env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )

            # ffmpeg captures screen -> encodes -> sends over ws
            ffmpeg_cmd = [
                "ffmpeg", "-y",
                "-f", "x11grab", "-video_size", "1280x720", "-framerate", str(settings.stream_fps),
                "-i", f"{display}.0",
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-b:v", f"{settings.stream_max_bitrate}k",
                "-f", "mpegts", "pipe:1",
            ]
            self.ffmpeg = subprocess.Popen(ffmpeg_cmd, env=env, stdout=subprocess.PIPE)
            # stream frames over ws_video
            asyncio.create_task(self._pump_video())
            return True
        except FileNotFoundError as e:
            logger.warning(f"Streaming deps missing: {e}. Falling back to download-only mode.")
            return False
        except Exception as e:
            logger.exception(f"Stream start failed: {e}")
            return False

    async def _pump_video(self):
        """Read ffmpeg output, send chunks to client."""
        if not self.ffmpeg or not self.video_ws:
            return
        try:
            loop = asyncio.get_event_loop()
            while True:
                chunk = await loop.run_in_executor(None, self.ffmpeg.stdout.read, 64 * 1024)
                if not chunk:
                    break
                await self.video_ws.send_bytes(chunk)
        except Exception as e:
            logger.debug(f"Video pump ended: {e}")
        finally:
            await self.stop()

    async def handle_control(self, msg: dict) -> dict:
        """Handle client control msg: {type: 'key'|'joy'|'save'|'load'|'pause', ...}"""
        t = msg.get("type")
        if t == "key" or t == "joy":
            # forward to emulator via xdotool
            key = msg.get("key") or msg.get("button")
            try:
                subprocess.run(["xdotool", "key", "--display", ":99", str(key)],
                               check=False, timeout=2)
            except FileNotFoundError:
                pass
            return {"ok": True}
        if t == "save":
            slot = msg.get("slot", 0)
            try:
                subprocess.run(["xdotool", "key", "--display", ":99", f"ctrl+shift+s"],
                               check=False, timeout=2)
            except FileNotFoundError:
                pass
            return {"ok": True}
        return {"ok": False, "reason": "unknown_type"}

    async def stop(self):
        if self.process and self.process.poll() is None:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except Exception:
                self.process.kill()
        if self.ffmpeg and self.ffmpeg.poll() is None:
            try:
                self.ffmpeg.terminate()
            except Exception:
                self.ffmpeg.kill()
        self.ended_at = datetime.utcnow()


class StreamManager:
    def __init__(self):
        self.sessions: dict[str, StreamSession] = {}

    def create(self, sid: str, user_id: int, game_id: int, rom: Path,
               platform: str, mode: str = "stream") -> StreamSession:
        s = StreamSession(sid, user_id, game_id, rom, platform, mode)
        self.sessions[sid] = s
        return s

    async def stop(self, sid: str):
        s = self.sessions.pop(sid, None)
        if s:
            await s.stop()

    async def cleanup_all(self):
        for sid in list(self.sessions.keys()):
            await self.stop(sid)


stream_mgr = StreamManager()
