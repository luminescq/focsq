#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Порт provenance-проверок нативных бинарников на Python (Linux/Forgejo CI).

Повторяет логику scripts/native_client_provenance.ps1 и
scripts/server_asset_provenance.ps1 (Windows PowerShell): те же списки
входных файлов, та же канонизация путей (прямые слэши, сортировка
ordinal), поэтому buildInputSha256 совпадает с манифестом, записанным
на Windows. Write+Verify в CI самосогласованы: манифест генерируется
из только что собранных бинарников и тут же проверяется.
"""
import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

CLIENT_SCHEMA = "csqtt.native-client-provenance.v3"
CLIENT_MARKER = "CSQTT_RUST_NATIVE_PRODUCTION_V1"
CLIENT_PRODUCER = "rust-client/build_so.bat"
CLIENT_TRANSPORT = "rust-turn-sans-io-rfc8656-channeldata"
CLIENT_TRANSPORT_MARKER = "CSQTT_RUST_TURN_SANS_IO_V1"
CLIENT_NATIVE_CORE_ABI = 1

SERVER_SCHEMA = "csqtt.server-asset-provenance.v2"
SERVER_MARKER = "CSQTT_RUST_SERVER_PRODUCTION_V1"
SERVER_PRODUCER = "rust-server/build_linux.bat"


def sha256_file(path: Path) -> str:
    hasher = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            hasher.update(chunk)
    return hasher.hexdigest().upper()


def assert_regular_file(path: Path, label: str) -> None:
    if not path.is_file():
        raise SystemExit(f"{label} not found: {path}")
    if path.stat().st_size <= 0:
        raise SystemExit(f"{label} is empty: {path}")


def check_elf(path: Path, elf_class: int, elf_machine: int, label: str) -> None:
    with open(path, "rb") as stream:
        header = stream.read(20)
    if len(header) < 20:
        raise SystemExit(f"Invalid ELF header for {label}: {path}")
    if header[0:4] != b"\x7fELF":
        raise SystemExit(f"Production artifact is not ELF for {label}: {path}")
    if header[4] != elf_class or header[5] != 1:
        raise SystemExit(f"ELF class or endianness mismatch for {label}: {path}")
    elf_type = header[16] | (header[17] << 8)
    machine = header[18] | (header[19] << 8)
    if elf_type != 3:
        raise SystemExit(f"Production artifact is not an Android PIE/ET_DYN for {label}: {path}")
    if machine != elf_machine:
        raise SystemExit(f"ELF machine mismatch for {label}: expected {elf_machine}, got {machine}")


def contains_bytes(path: Path, pattern: bytes) -> bool:
    # Целиком: PowerShell-версия корректно находит паттерн на границе
    # 64-Кбайт буферов, поэтому байтовый поиск тоже должен видеть его.
    with open(path, "rb") as stream:
        return pattern in stream.read()


def cargo_package(cargo_toml: Path) -> tuple[str, str]:
    assert_regular_file(cargo_toml, "Cargo.toml")
    name = version = None
    inside_package = False
    pattern = re.compile(r'^(name|version)\s*=\s*"([^"]+)"', re.IGNORECASE)
    for line in cargo_toml.read_text(encoding="utf-8").splitlines():
        trimmed = line.strip()
        if trimmed == "[package]":
            inside_package = True
            continue
        if inside_package and trimmed.startswith("["):
            break
        if inside_package:
            match = pattern.match(trimmed)
            if match:
                if match.group(1) == "name":
                    name = match.group(2)
                else:
                    version = match.group(2)
    if not name or not version:
        raise SystemExit(f"Unable to read Rust package identity from {cargo_toml}")
    return name, version


def build_input_hash(root: Path, entries: list[tuple[str, str]]) -> str:
    pairs: set[tuple[str, str]] = set()
    for kind, rel in entries:
        path = root / rel
        if kind == "file":
            assert_regular_file(path, f"Native build input {rel}")
            pairs.add((rel, sha256_file(path)))
        elif kind == "rs-top":
            if not path.is_dir():
                raise SystemExit(f"Native build input not found: {rel}")
            for file in sorted(p for p in path.iterdir() if p.is_file() and p.suffix == ".rs"):
                pairs.add((f"{rel}/{file.name}", sha256_file(file)))
        elif kind == "recursive":
            if not path.is_dir():
                raise SystemExit(f"Native build input not found: {rel}")
            for file in path.rglob("*"):
                if file.is_file():
                    pairs.add((file.relative_to(root).as_posix(), sha256_file(file)))
        else:
            raise SystemExit(f"Unknown input kind: {kind}")
    builder = "".join(f"{rel}:{sha}\n" for rel, sha in sorted(pairs))
    return hashlib.sha256(builder.encode("utf-8")).hexdigest().upper()


CLIENT_ABIS = {
    "arm64-v8a": {
        "target": "aarch64-linux-android",
        "relative": "app/src/main/jniLibs/arm64-v8a/libclient.so",
        "elf_class": 2,
        "elf_machine": 183,
    },
    "armeabi-v7a": {
        "target": "armv7-linux-androideabi",
        "relative": "app/src/main/jniLibs/armeabi-v7a/libclient.so",
        "elf_class": 1,
        "elf_machine": 40,
    },
    "x86_64": {
        "target": "x86_64-linux-android",
        "relative": "app/src/main/jniLibs/x86_64/libclient.so",
        "elf_class": 2,
        "elf_machine": 62,
    },
}

CLIENT_CARGO_DIRS = {
    "arm64-v8a": "build/rust-client-android-arm64/aarch64-linux-android/release/client",
    "armeabi-v7a": "build/rust-client-android-armv7/armv7-linux-androideabi/release/client",
    "x86_64": "build/rust-client-android-x86_64/x86_64-linux-android/release/client",
}

SERVER_ASSETS = {
    "csqtt-linux-amd64": {"target": "x86_64-unknown-linux-musl", "elf_class": 2, "elf_machine": 62},
    "csqtt-linux-arm64": {"target": "aarch64-unknown-linux-musl", "elf_class": 2, "elf_machine": 183},
    "csqtt-linux-armv7": {"target": "armv7-unknown-linux-musleabihf", "elf_class": 1, "elf_machine": 40},
}


def client_inputs() -> list[tuple[str, str]]:
    return [
        ("file", "rust-client/build_so.bat"),
        ("file", "rust-client/build_android_abis.ps1"),
        ("file", "rust-client/Cargo.toml"),
        ("file", "rust-client/Cargo.lock"),
        ("rs-top", "rust-client"),
        ("recursive", "rust-client/vendor/primp"),
        ("file", "scripts/find_android_sdk_ndk.bat"),
        ("file", "scripts/native_client_provenance.ps1"),
        ("file", "shared/wire_protocol.rs"),
    ]


def server_inputs() -> list[tuple[str, str]]:
    return [
        ("file", "rust-server/build_linux.bat"),
        ("file", "rust-server/build_linux.sh"),
        ("file", "rust-server/Cargo.toml"),
        ("file", "rust-server/Cargo.lock"),
        ("file", "shared/wire_protocol.rs"),
        ("file", "scripts/server_asset_provenance.ps1"),
        ("rs-top", "rust-server"),
    ]


def wire_protocol_revision(root: Path) -> str:
    main_rs = root / "rust-server" / "main.rs"
    assert_regular_file(main_rs, "Wire protocol revision source")
    match = re.search(
        r'WIRE_PROTOCOL_REVISION\s*:\s*&str\s*=\s*"([^"]+)"',
        main_rs.read_text(encoding="utf-8"),
    )
    if not match:
        raise SystemExit("Wire protocol revision is missing")
    return match.group(1)


def client_artifact(root: Path, abi: str, require_cargo_match: bool) -> dict:
    spec = CLIENT_ABIS[abi]
    production = root / spec["relative"]
    cargo_path = root / CLIENT_CARGO_DIRS[abi]
    assert_regular_file(production, f"{abi} production artifact")
    check_elf(production, spec["elf_class"], spec["elf_machine"], abi)
    if contains_bytes(production, b"Go buildinf:"):
        raise SystemExit(f"Go build metadata detected in production artifact for {abi}")
    if not contains_bytes(production, CLIENT_TRANSPORT_MARKER.encode("ascii")):
        raise SystemExit(f"Rust TURN transport identity is missing for {abi}")
    if require_cargo_match:
        assert_regular_file(cargo_path, f"{abi} Cargo artifact")
        if sha256_file(production) != sha256_file(cargo_path):
            raise SystemExit(f"Production artifact does not match the Cargo output for {abi}")
    return {
        "abi": abi,
        "target": spec["target"],
        "path": spec["relative"],
        "size": production.stat().st_size,
        "sha256": sha256_file(production),
    }


def server_artifact(root: Path, asset: str, revision: str) -> dict:
    spec = SERVER_ASSETS[asset]
    binary = root / "app/src/main/assets" / asset
    assert_regular_file(binary, f"Linux server asset {asset}")
    data = binary.read_bytes()
    if len(data) < 20 or data[0:4] != b"\x7fELF":
        raise SystemExit(f"Linux server asset {asset} is not ELF")
    if data[4] != spec["elf_class"] or data[5] != 1:
        raise SystemExit(f"ELF class or endianness mismatch for {asset}")
    machine = data[18] | (data[19] << 8)
    if machine != spec["elf_machine"]:
        raise SystemExit(f"ELF machine mismatch for {asset}: expected {spec['elf_machine']}, got {machine}")
    if revision not in data.decode("ascii", errors="replace"):
        raise SystemExit(f"Linux server asset {asset} does not expose wire protocol revision {revision}")
    return {
        "assetName": asset,
        "target": spec["target"],
        "elfClass": spec["elf_class"],
        "elfMachine": spec["elf_machine"],
        "binarySize": len(data),
        "binarySha256": sha256_file(binary),
    }


def write_json(path: Path, manifest: dict) -> None:
    temporary = path.with_name(f"{path.name}.tmp.{Path(__file__).name}.{__import__('os').getpid()}")
    temporary.write_text(json.dumps(manifest) + "\n", encoding="utf-8")
    temporary.replace(path)


def run_client(root: Path, mode: str, abis: list[str]) -> None:
    cargo_toml = root / "rust-client" / "Cargo.toml"
    cargo_lock = root / "rust-client" / "Cargo.lock"
    manifest_path = root / "app/src/main/libclient.provenance.json"
    package_name, package_version = cargo_package(cargo_toml)
    inputs_hash = build_input_hash(root, client_inputs())

    if mode == "write":
        assert_regular_file(cargo_lock, "Cargo.lock")
        manifest = {
            "schema": CLIENT_SCHEMA,
            "marker": CLIENT_MARKER,
            "producer": CLIENT_PRODUCER,
            "runtime": "rust",
            "buildSystem": "cargo-ndk",
            "profile": "release",
            "transport": CLIENT_TRANSPORT,
            "transportMarker": CLIENT_TRANSPORT_MARKER,
            "nativeCoreAbi": CLIENT_NATIVE_CORE_ABI,
            "package": package_name,
            "version": package_version,
            "buildInputSha256": inputs_hash,
            "cargoTomlSha256": sha256_file(cargo_toml),
            "cargoLockSha256": sha256_file(cargo_lock),
            "generatedAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "artifacts": [client_artifact(root, abi, True) for abi in abis],
        }
        write_json(manifest_path, manifest)
        print(f"Wrote Rust native provenance: {manifest_path}")
        return

    assert_regular_file(manifest_path, "Rust native provenance")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (
        manifest.get("schema") != CLIENT_SCHEMA
        or manifest.get("marker") != CLIENT_MARKER
        or manifest.get("producer") != CLIENT_PRODUCER
        or manifest.get("runtime") != "rust"
        or manifest.get("buildSystem") != "cargo-ndk"
        or manifest.get("profile") != "release"
        or manifest.get("transport") != CLIENT_TRANSPORT
        or manifest.get("transportMarker") != CLIENT_TRANSPORT_MARKER
        or int(manifest.get("nativeCoreAbi")) != CLIENT_NATIVE_CORE_ABI
    ):
        raise SystemExit(f"Rust native provenance identity mismatch: {manifest_path}")
    if manifest.get("package") != package_name or manifest.get("version") != package_version:
        raise SystemExit("Rust package identity does not match provenance")
    if manifest.get("cargoTomlSha256") != sha256_file(cargo_toml):
        raise SystemExit("Cargo.toml changed after the production native build")
    if manifest.get("cargoLockSha256") != sha256_file(cargo_lock):
        raise SystemExit("Cargo.lock changed after the production native build")
    if manifest.get("buildInputSha256") != inputs_hash:
        raise SystemExit("Rust native source changed after the production native build")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != len(abis):
        raise SystemExit(f"Rust native provenance must contain exactly {len(abis)} artifacts")
    for abi in abis:
        actual = client_artifact(root, abi, True)
        entries = [a for a in artifacts if a.get("abi") == abi]
        if len(entries) != 1:
            raise SystemExit(f"Rust native provenance must contain one entry for {abi}")
        entry = entries[0]
        if (
            entry.get("target") != actual["target"]
            or entry.get("path") != actual["path"]
            or int(entry.get("size")) != actual["size"]
            or str(entry.get("sha256")).upper() != actual["sha256"]
        ):
            raise SystemExit(f"Rust native provenance artifact mismatch for {abi}")
    print(f"Rust native provenance verified: {CLIENT_MARKER}")


def run_server(root: Path, mode: str, assets: list[str]) -> None:
    cargo_toml = root / "rust-server" / "Cargo.toml"
    cargo_lock = root / "rust-server" / "Cargo.lock"
    manifest_path = root / "app/src/main/assets/csqtt.server-provenance.json"
    package_name, package_version = cargo_package(cargo_toml)
    revision = wire_protocol_revision(root)
    inputs_hash = build_input_hash(root, server_inputs())

    if mode == "write":
        assert_regular_file(cargo_lock, "Server Cargo.lock")
        manifest = {
            "schema": SERVER_SCHEMA,
            "marker": SERVER_MARKER,
            "producer": SERVER_PRODUCER,
            "runtime": "rust",
            "package": package_name,
            "version": package_version,
            "wireProtocolRevision": revision,
            "buildInputSha256": inputs_hash,
            "cargoTomlSha256": sha256_file(cargo_toml),
            "cargoLockSha256": sha256_file(cargo_lock),
            "artifacts": [server_artifact(root, asset, revision) for asset in assets],
            "generatedAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        write_json(manifest_path, manifest)
        print(f"Wrote Rust server provenance: {manifest_path}")
        return

    assert_regular_file(manifest_path, "Rust server provenance")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (
        manifest.get("schema") != SERVER_SCHEMA
        or manifest.get("marker") != SERVER_MARKER
        or manifest.get("producer") != SERVER_PRODUCER
        or manifest.get("runtime") != "rust"
    ):
        raise SystemExit("Rust server provenance identity mismatch")
    if (
        manifest.get("package") != package_name
        or manifest.get("version") != package_version
        or manifest.get("wireProtocolRevision") != revision
    ):
        raise SystemExit("Rust server package or wire protocol identity changed after build")
    if (
        manifest.get("cargoTomlSha256") != sha256_file(cargo_toml)
        or manifest.get("cargoLockSha256") != sha256_file(cargo_lock)
        or manifest.get("buildInputSha256") != inputs_hash
    ):
        raise SystemExit("Rust server source changed after the production asset build")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != len(assets):
        raise SystemExit("Rust server provenance artifact count mismatch")
    for asset in assets:
        actual = server_artifact(root, asset, revision)
        matches = [a for a in artifacts if a.get("assetName") == asset]
        if len(matches) != 1:
            raise SystemExit(f"Rust server provenance is missing {asset}")
        record = matches[0]
        if (
            record.get("target") != actual["target"]
            or int(record.get("elfClass")) != actual["elfClass"]
            or int(record.get("elfMachine")) != actual["elfMachine"]
            or int(record.get("binarySize")) != actual["binarySize"]
            or record.get("binarySha256") != actual["binarySha256"]
        ):
            raise SystemExit(f"Rust server asset does not match provenance: {asset}")
    print(f"Rust server provenance verified: {SERVER_MARKER}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=["client", "server"])
    parser.add_argument("mode", choices=["write", "verify"])
    parser.add_argument("--root", default=None, help="project root (default: script's parent dir)")
    parser.add_argument("--abis", default=None, help="comma-separated ABIs (client only)")
    parser.add_argument("--assets", default=None, help="comma-separated assets (server only)")
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent

    if args.target == "client":
        abis = (args.abis or "arm64-v8a,armeabi-v7a,x86_64").split(",")
        unknown = [abi for abi in abis if abi not in CLIENT_ABIS]
        if unknown:
            raise SystemExit(f"Unknown ABIs: {unknown}")
        run_client(root, args.mode, abis)
    else:
        assets = (args.assets or "csqtt-linux-amd64,csqtt-linux-arm64,csqtt-linux-armv7").split(",")
        unknown = [asset for asset in assets if asset not in SERVER_ASSETS]
        if unknown:
            raise SystemExit(f"Unknown assets: {unknown}")
        run_server(root, args.mode, assets)


if __name__ == "__main__":
    main()
