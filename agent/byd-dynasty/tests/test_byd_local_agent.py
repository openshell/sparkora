# -*- coding: utf-8 -*-
from __future__ import annotations

import io
import json
import shutil
import struct
import tempfile
import zlib
from pathlib import Path

import pytest
from Crypto.Cipher import AES

import byd_local_agent as agent


def aes_key() -> bytes:
    return agent.derive_aes_key(agent.APP_ID)


def crypto_cfb(iv: bytes, data: bytes) -> bytes:
    return AES.new(aes_key(), AES.MODE_CFB, iv=iv, segment_size=128).encrypt(data)


def make_mmkv_bytes(plaintext: bytes, actual: int) -> tuple[bytes, bytes]:
    iv = b"0123456789abcdef"
    cipher = crypto_cfb(iv, plaintext)
    main = struct.pack("<I", actual) + cipher
    minimum = max(16384, 2 * len(main))
    main += bytes(minimum - len(main))
    crc = bytearray(struct.pack("<III", 0, 1, 1) + iv + struct.pack("<III", actual, len(main), 0))
    struct.pack_into("<I", crc, 0, zlib.crc32(cipher) & 0xFFFFFFFF)
    return main, bytes(crc)


def make_record_bytes(records: list[tuple[str, bytes]]) -> bytes:
    value = b"".join(
        agent.make_record(key.encode().decode(), blob).removeprefix(b"")
        for key, blob in records
    )
    return value


def build_mmkv(drafts: list[dict] | None = None) -> tuple[bytes, bytes]:
    wrapped = agent.wrap_draft_value(drafts or [])
    record = agent.make_record(agent.DRAFT_KEY, wrapped)
    plaintext = b"\0\0\0\0" + record
    return make_mmkv_bytes(plaintext, len(plaintext))


def test_cfb128_matches_pycryptodome_for_small_stream():
    iv = b"0123456789abcdef"
    data = b"sparkora-byd"
    expected = AES.new(aes_key(), AES.MODE_CFB, iv=iv, segment_size=128).encrypt(data)
    assert agent.StreamingCFB128(aes_key(), iv).encrypt(data) == expected


def test_parse_mmkv_reads_platform_drafts():
    main, crc = build_mmkv([])
    drafts, actual = agent.parse_mmkv(main, crc)
    assert drafts == []
    assert actual > 0


def test_make_mmkv_self_consistent():
    main, crc = build_mmkv([])
    # A well-formed existing MMKV should parse after the local append uses the
    # same streaming primitive.
    drafts, _ = agent.parse_mmkv(main, crc)
    assert drafts == []


def test_compile_messages_adds_sibling_after_image():
    result = agent.compile_messages(
        {
            "messages": [
                {"type": "text", "content": "第一行\n第二行"},
                {"type": "img", "imageRef": "BODY-1"},
            ]
        },
        {"BODY-1": "https://example.test/a.jpg"},
    )
    assert result[0]["msg_content"] == "第一行\n第二行"
    assert result[0]["msg_content"] == "第一行\n第二行"
    assert result[1]["msg_type"] == "img"
    assert result[2]["msg_style"]["sibling"] == result[1]["id"]


def test_write_mmkv_with_text_payload(tmp_path: Path):
    mmkv_dir = tmp_path / "mmkv"
    backup_dir = tmp_path / "backup"
    mmkv_dir.mkdir()
    main, crc = build_mmkv([])
    (mmkv_dir / agent.APP_ID).write_bytes(main)
    (mmkv_dir / f"{agent.APP_ID}.crc").write_bytes(crc)
    config = agent.AgentConfig(
        {
            "SPARKORA_BASE_URL": "https://sparkora.test",
            "SPARKORA_AGENT_TOKEN": "token",
            "SPARKORA_AGENT_ID": "agent-test",
            "BYD_DYNASTY_MMKV_DIR": str(mmkv_dir),
            "BYD_DYNASTY_UPLOAD_URL": "https://upload.test",
            "BYD_DYNASTY_UPLOAD_SIGN_KEY": "sign",
            "BYD_DYNASTY_UPLOAD_SESSION_ID": "sid",
            "BYD_DYNASTY_BACKUP_DIR": str(backup_dir),
        }
    )
    payload = {"title": "本地测试", "messages": [{"type": "text", "content": "你好"}]}
    draft = agent.build_draft(payload, agent.compile_messages(payload, {}), config)
    logs: dict = {}
    count = agent.write_mmkv(config, draft, logs)
    drafts, _ = agent.parse_mmkv(config.main_file.read_bytes(), config.crc_file.read_bytes())
    assert count == 1
    assert len(drafts) == 1
    assert drafts[0]["draftId"] == draft["draftId"]
    assert logs["mmkvDraftCountAfter"] == 1
    assert len(list(backup_dir.iterdir())) == 2
