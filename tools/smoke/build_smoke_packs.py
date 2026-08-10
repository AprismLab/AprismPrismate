#!/usr/bin/env python
"""Builds the AprismPrismate Fabric smoke packs.

Creates from the compiled classes in <classes-dir>:
  ressmoke.aje    - entrypoint com.example.ressmoke.ResourceProbe plus a
                    resources/ dir containing pack.mcmeta and an asset entry
                    that exist ONLY in resources/ (proves resource-dir
                    injection into the host classloader).
  prismatemix.aje - entrypoint com.example.prismatemix.MixinProofEntrypoint
                    with a mixin (com.example.prismatemix.mixin) targeting
                    net.minecraft.client.Minecraft (proves mixin passthrough
                    through the host Mixin environment). The entrypoint lives
                    in its OWN package, as required by the host Mixin
                    environment's package-ownership rule.

Usage:
    python build_smoke_packs.py <outdir> <classes-dir>
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
    print(f"[smoke-packs] wrote {target}")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    outdir = pathlib.Path(sys.argv[1])
    classes = pathlib.Path(sys.argv[2])
    outdir.mkdir(parents=True, exist_ok=True)

    # --- ressmoke.aje (resource-dir injection proof) ---
    pack_mcmeta = json.dumps({
        "pack": {
            "pack_format": 1,
            "supported_formats": {"min_inclusive": 1, "max_inclusive": 9999},
            "description": "Prismate smoke resource pack",
        }
    })
    ressmoke_manifest = {
        "schemaVersion": 1,
        "id": "ressmoke",
        "version": "1.0.0",
        "displayName": "Prismate Resource Smoke",
        "description": "Smoke pack proving Prismate resource-dir injection on Fabric.",
        "environment": "*",
        "entrypoints": {"main": ["com.example.ressmoke.ResourceProbe"]},
        "mixins": [],
        "depends": {},
        "platforms": {},
        "accessWidener": None,
        "provides": [],
        "custom": {},
    }
    ressmoke_jar = jar_from_classes(classes, ("com/example/ressmoke/",))
    write_aje(outdir / "ressmoke.aje", ressmoke_manifest, "ressmoke", ressmoke_jar, {
        "resources/pack.mcmeta": pack_mcmeta.encode(),
        "resources/assets/prismatesmoke/lang/en_us.json": json.dumps(
            {"prismatesmoke.smoke": "Prismate smoke"}).encode(),
    })

    # --- prismatemix.aje (mixin passthrough proof) ---
    mixin_config_name = "prismatemix.mixins.json"
    mixin_config = json.dumps({
        "required": True,
        "minVersion": "0.8",
        "package": "com.example.prismatemix.mixin",
        "client": ["MinecraftSmokeMixin"],
        "injectors": {"defaultRequire": 1},
    })
    prismatemix_manifest = {
        "schemaVersion": 1,
        "id": "prismatemix",
        "version": "1.0.0",
        "displayName": "Prismate Mixin Smoke",
        "description": "Smoke pack proving Prismate mixin passthrough on Fabric.",
        "environment": "*",
        "entrypoints": {"main": ["com.example.prismatemix.MixinProofEntrypoint"]},
        "mixins": [mixin_config_name],
        "depends": {},
        "platforms": {},
        "accessWidener": None,
        "provides": [],
        "custom": {},
    }
    prismatemix_jar = jar_from_classes(
        classes,
        ("com/example/prismatemix/",),
        extra_entries={mixin_config_name: mixin_config.encode()},
    )
    write_aje(outdir / "prismatemix.aje", prismatemix_manifest, "prismatemix",
              prismatemix_jar, {f"mixins/{mixin_config_name}": mixin_config.encode()})

    # --- faultsmoke.aje (v26.1-Alpha.8 fault-injection drill) ---
    # Its entrypoint throws in onInitialize; the harness asserts Prismate
    # records a named lifecycle failure for THIS pack while the healthy pack
    # next to it still completes its lifecycle (per-mod isolation, live game).
    faultsmoke_manifest = {
        "schemaVersion": 1,
        "id": "faultsmoke",
        "version": "1.0.0",
        "displayName": "Prismate Fault Injection",
        "description": "Fault-injection pack: entrypoint throws on purpose.",
        "environment": "*",
        "entrypoints": {"main": ["com.example.faultsmoke.ThrowingProbe"]},
        "mixins": [],
        "depends": {},
        "platforms": {},
        "accessWidener": None,
        "provides": [],
        "custom": {},
    }
    faultsmoke_jar = jar_from_classes(classes, ("com/example/faultsmoke/",))
    write_aje(outdir / "faultsmoke.aje", faultsmoke_manifest, "faultsmoke",
              faultsmoke_jar, {})

    return 0


if __name__ == "__main__":
    sys.exit(main())
