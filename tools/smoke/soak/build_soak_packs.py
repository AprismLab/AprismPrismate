#!/usr/bin/env python
"""Builds the AprismPrismate multi-mod soak packs (v26.0-Alpha.5).

Creates from the compiled classes in <classes-dir>:
  soakcore.aje     - id soakcore, entrypoint com.example.soak.CoreProbe,
                     no dependencies.
  soakapi.aje      - id soakapi, entrypoint com.example.soak.ProviderProbe,
                     provides the virtual id "soak-api".
  soakconsumer.aje - id soakconsumer, entrypoint com.example.soak.ConsumerProbe,
                     depends on soak-api >=1.0.0 (resolved through the
                     soakapi pack's provides alias).

Usage:
    python build_soak_packs.py <outdir> <classes-dir>
"""
import io
import json
import pathlib
import sys
import zipfile


def jar_from_classes(classes_dir, include, extra_entries=None):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as dst:
        for cls in classes_dir.rglob("*.class"):
            rel = cls.relative_to(classes_dir).as_posix()
            if any(rel.startswith(p) for p in include):
                dst.writestr(rel, cls.read_bytes())
        for name, data in (extra_entries or {}).items():
            dst.writestr(name, data)
    return buf.getvalue()


def write_aje(target, manifest, mod_id, jar_bytes, extra):
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("aprism.manifest.json", json.dumps(manifest, indent=2))
        z.writestr(f"{mod_id}.jar", jar_bytes)
        for name, data in extra.items():
            z.writestr(name, data)
    print(f"[soak-packs] wrote {target}")


def manifest(mod_id, version, entrypoint, depends=None, provides=None):
    return {
        "schemaVersion": 1,
        "id": mod_id,
        "version": version,
        "displayName": f"Soak {mod_id}",
        "description": "Prismate multi-mod soak probe (v26.0-Alpha.5).",
        "environment": "*",
        "entrypoints": {"main": [entrypoint]},
        "mixins": [],
        "depends": depends or {},
        "platforms": {},
        "accessWidener": None,
        "provides": provides or [],
        "custom": {},
    }


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    outdir = pathlib.Path(sys.argv[1])
    classes = pathlib.Path(sys.argv[2])
    outdir.mkdir(parents=True, exist_ok=True)

    write_aje(
        outdir / "soakcore.aje",
        manifest("soakcore", "1.0.0", "com.example.soak.CoreProbe"),
        "soakcore",
        jar_from_classes(classes, ("com/example/soak/CoreProbe",)),
        {},
    )
    write_aje(
        outdir / "soakapi.aje",
        manifest("soakapi", "1.0.0", "com.example.soak.ProviderProbe",
                 provides=["soak-api"]),
        "soakapi",
        jar_from_classes(classes, ("com/example/soak/ProviderProbe",)),
        {},
    )
    write_aje(
        outdir / "soakconsumer.aje",
        manifest("soakconsumer", "1.0.0", "com.example.soak.ConsumerProbe",
                 depends={"soak-api": ">=1.0.0"}),
        "soakconsumer",
        jar_from_classes(classes, ("com/example/soak/ConsumerProbe",)),
        {},
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
