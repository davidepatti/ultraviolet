#!/usr/bin/env python3
"""One fresh journal opening capture. No DSE, saved-state load, allocation or ML execution."""

import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
A2 = REPO.parent / "a2"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def sha(value):
    return hashlib.sha256(value).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def create_json(path, value):
    with Path(path).open("xb") as stream:
        stream.write(canonical(value) + b"\n")


def output_path(a2, purpose, name):
    require(bool(re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", name)), "Invalid capture name")
    require(a2.resolve() == a2 and a2.name == "a2" and a2.parent.name == "gnn-lightning-journal"
            and (a2 / "configs/study.json").is_file(), "Not the journal A2 workspace")
    group = a2 / ("validation/captures" if purpose == "acceptance" else "incoming")
    require(group.resolve() == group, "Capture destination redirects through a symlink")
    result = group / name
    require(not result.exists() and not result.is_symlink(), "Capture exists; overwrite/resume is forbidden")
    return result


def validate_request(request, a2=A2):
    require(set(request) == {"schema", "purpose", "topology_id", "overrides"}, "Unknown request fields (load/commands unsupported)")
    require(request["schema"] == "journal-a2-capture-request-v1", "Unsupported request schema")
    require(request["purpose"] in ("acceptance", "study"), "Unknown capture purpose")
    key, overrides = request["topology_id"], request["overrides"]
    require(isinstance(key, str) and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", key), "Invalid topology ID")
    require(isinstance(overrides, dict) and all(type(v) is int for v in overrides.values()), "Overrides must be integer values")
    for name in ("bootstrap_nodes", "bootstrap_blocks", "seed"):
        require(name in overrides and 1 <= overrides[name] < 2**31, "Missing/invalid " + name)
    if request["purpose"] == "study":
        require(set(overrides) == {"bootstrap_nodes", "bootstrap_blocks", "seed"}, "Study uses the fixed template family")
        require(overrides["bootstrap_nodes"] == 500 and overrides["bootstrap_blocks"] == 100, "Study size/block mismatch")
        stage = "pilot" if key.startswith("a2-pilot-") else "main" if key.startswith("a2-main-") else None
        require(stage is not None, "Study topology ID not in plan")
        if stage == "main":
            require(read(a2 / "configs/study.json")["main_protocol_frozen"], "Main protocol is not frozen")
        rows = read(a2 / f"preflight/{stage}-plan.json")["topologies"]
        require(any(r["topology_id"] == key and r["bootstrap_seed"] == overrides["seed"] for r in rows), "Unplanned topology/seed")
    else:
        allowed = {"bootstrap_nodes", "bootstrap_blocks", "seed", "max_threads",
                   "profile.small.min_channels", "profile.small.max_channels",
                   "profile.medium.min_channels", "profile.medium.max_channels",
                   "profile.hub.min_channels", "profile.hub.max_channels",
                   "profile.default.min_channels", "profile.default.max_channels"}
        require(set(overrides) <= allowed and key.startswith("acceptance-"), "Acceptance-only settings/identity required")
        require(2 <= overrides["bootstrap_nodes"] <= 20 and 2 <= overrides["bootstrap_blocks"] <= 30,
                "Acceptance captures must be small and separate from the pilot")
        require(overrides.get("max_threads", 32) >= 2, "At least two worker threads required")
        planned = [r["bootstrap_seed"] for stage in ("pilot", "main")
                   for r in read(a2 / f"preflight/{stage}-plan.json")["topologies"]]
        require(overrides["seed"] not in planned, "Acceptance seed overlaps the study plan")
    return request


def source_snapshot(repo=REPO):
    paths = sorted(set(repo.glob("src/**/*.java")) | {repo / "src/aliases.txt", repo / "uv_configs/template.properties"}
                   | set(repo.glob("tools/journal/*.py")))
    require(all(p.is_file() and not p.is_symlink() for p in paths), "Source symlinks/missing files are unsupported")
    sources = {p.relative_to(repo).as_posix(): p.read_bytes() for p in paths}
    manifest = {name: sha(value) for name, value in sources.items()}
    commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    dirty = bool(subprocess.check_output(["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=all"], text=True).strip())
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as stream:
        for name, value in sources.items():
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            stream.writestr(entry, value)
    return sources, archive.getvalue(), {"schema": "journal-a2-source-v1", "base_commit": commit,
                                        "dirty": dirty, "files_sha256": manifest,
                                        "source_tree_sha256": sha(canonical(manifest))}


def validate_capture(directory):
    """Independent byte, source-archive, configuration, public/oracle and receipt checks."""
    root = Path(directory)
    names = ("capture.json", "request.json", "public.json", "opening-truth.json", "expanded-config.json", "source-manifest.json", "source.zip")
    require(all((root / n).is_file() and not (root / n).is_symlink() for n in names), "Incomplete or redirected capture")
    receipt, request, public, truth, config, source = [read(root / n) for n in names[:-1]]
    require(receipt["schema"] == "journal-a2-capture-receipt-v1" and receipt["repeated_capture_identical"] is True, "Missing completion check")
    for kind, name in (("public", "public.json"), ("opening_truth", "opening-truth.json")):
        require(receipt[kind + "_file"] == name and receipt[kind + "_sha256"] == sha((root / name).read_bytes()), "Capture file hash mismatch")
    require(receipt["expanded_config_file"] == "expanded-config.json", "Invalid expanded configuration path")
    require(set(public) == {"schema", "topology_id", "nodes", "channels", "provenance"}, "Public field separation failed")
    require(public["schema"] == "journal-a2-public-v1" and truth["schema"] == "journal-a2-truth-v1", "Schema mismatch")
    require(public["topology_id"] == truth["topology_id"] == receipt["topology_id"] == request["topology_id"], "Topology identity mismatch")
    provenance = public["provenance"]
    require(provenance["pending_checked"] is True and provenance["capture_boundary"] == "fresh-bootstrap-terminal-workers-joined-v1", "Missing capture barrier")
    require(provenance["expanded_config_sha256"] == sha((root / "expanded-config.json").read_bytes()), "Effective configuration hash mismatch")
    require(provenance["source_manifest_sha256"] == receipt["source_manifest_sha256"] == sha((root / "source-manifest.json").read_bytes()), "Source manifest hash mismatch")
    require(re.fullmatch(r"[0-9a-f]{40}", source["base_commit"]) and provenance["uv_commit"] == source["base_commit"], "Source revision mismatch")
    require(type(source["dirty"]) is bool and provenance["source_dirty"] == source["dirty"], "Dirty-source status mismatch")
    require(source["source_tree_sha256"] == sha(canonical(source["files_sha256"])) == provenance["source_tree_sha256"], "Source tree hash mismatch")
    require(source["source_archive_sha256"] == sha((root / "source.zip").read_bytes()), "Source archive hash mismatch")
    with zipfile.ZipFile(root / "source.zip") as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate source archive entries")
        require(set(archive.namelist()) == set(source["files_sha256"]), "Source archive inventory mismatch")
        require(all(not Path(n).is_absolute() and ".." not in Path(n).parts for n in archive.namelist()), "Unsafe source archive path")
        require(all(sha(archive.read(n)) == h for n, h in source["files_sha256"].items()), "Archived source differs from compiled inputs")
    if "opening_cleanup_sha256" in provenance:
        cleanup_path = root / "opening-cleanup.json"
        require(cleanup_path.is_file() and not cleanup_path.is_symlink(), "Missing opening cleanup record")
        require(provenance["opening_cleanup_sha256"] == receipt["opening_cleanup_sha256"] == sha(cleanup_path.read_bytes()), "Opening cleanup hash mismatch")
        cleanup = read(cleanup_path)
        require(cleanup["schema"] == "journal-a2-opening-cleanup-v1" and cleanup["topology_id"] == public["topology_id"]
                and cleanup["funded_channels_changed"] is False and cleanup["onchain_balances_changed"] is False, "Invalid opening cleanup boundary")
        for node, requests in cleanup["cancelled_unaccepted_requests"].items():
            require(node in public["nodes"] and isinstance(requests, dict), "Invalid cleanup node")
            for peer, amount in requests.items():
                require(peer in public["nodes"] and peer != node and type(amount) is int and amount > 0, "Invalid cancelled reservation")
                require(not any({c["a"], c["b"]} == {node, peer} for c in public["channels"]), "Cleanup references a funded channel")
    for key, value in request["overrides"].items():
        require(str(config[key]) == str(value), "Requested override was ignored: " + key)
    require(type(config["seed"]) is int and provenance["bootstrap_seed"] == config["seed"]
            and config["logfile"] == "simulator.log", "Seed/log configuration mismatch")
    nodes, channels = public["nodes"], public["channels"]
    require(len(nodes) == config["bootstrap_nodes"] and len(set(nodes)) == len(nodes) and nodes == sorted(nodes), "Node count/order mismatch")
    require(channels and channels == sorted(channels, key=lambda c: c["channel_id"]), "Noncanonical channel order")
    fingerprint = sha(canonical({"nodes": nodes, "channels": channels}))
    require(truth["public_fingerprint"] == receipt["public_fingerprint"] == fingerprint, "Public fingerprint mismatch")
    require(truth["regime"] == "O" and truth["draw"] == 0 and truth["allocation_seed"] is None and truth["settled"] is True, "Not an opening capture")
    rows = {r["channel_id"]: r for r in truth["channels"]}
    require(len(rows) == len(truth["channels"]) == len(channels) and set(rows) == {c["channel_id"] for c in channels}, "Duplicate/mismatched channel IDs")
    for channel in channels:
        require(set(channel) == {"channel_id", "a", "b", "capacity_sat", "a_to_b", "b_to_a"}, "Hidden/unsupported public channel fields")
        require(channel["a"] in nodes and channel["b"] in nodes and channel["a"] < channel["b"], "Invalid endpoints")
        capacity = channel["capacity_sat"]
        require(type(capacity) is int and 0 < capacity < 2**31, "Invalid capacity")
        for direction in ("a_to_b", "b_to_a"):
            policy = channel[direction]
            require(set(policy) == {"fee_base_msat", "fee_rate_milli_msat"} and all(type(v) is int and 0 <= v < 2**31 for v in policy.values()), "Missing/invalid policy")
        row = rows[channel["channel_id"]]
        require(set(row) == {"channel_id", "balance_a_sat", "balance_b_sat", "reserve_sat", "pending_a_sat", "pending_b_sat"}, "Truth field mismatch")
        require(all(type(v) is int for k, v in row.items() if k != "channel_id"), "Truth values must be integers")
        require(sorted((row["balance_a_sat"], row["balance_b_sat"])) == [0, capacity]
                and row["reserve_sat"] == capacity // 100 and row["pending_a_sat"] == row["pending_b_sat"] == 0,
                "Opening balance/reserve/pending violation")
    return {"nodes": len(nodes), "channels": len(channels), "directed_edges": 2 * len(channels), "public_fingerprint": fingerprint,
            "source_tree_sha256": source["source_tree_sha256"], "bootstrap_seed": config["seed"], "status": "passed"}


def reject_duplicate(directory, fingerprint):
    for sibling in directory.parent.iterdir():
        if sibling != directory and (sibling / "validation.json").is_file():
            result = read(sibling / "validation.json")
            require(result.get("public_fingerprint") != fingerprint, "Exact duplicate of an earlier successful capture")


def capture(request, output, javac, java, timeout):
    sources, archive, source = source_snapshot()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir()  # Exclusive ownership; no overwrite or cleanup of pre-existing output.
    create_json(output / "request.json", request)
    (output / "source.zip").write_bytes(archive)
    source["source_archive_sha256"] = sha(archive)
    source["compiler_version"] = subprocess.check_output([javac, "-version"], stderr=subprocess.STDOUT, text=True).strip()
    source["runtime_version"] = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True).strip()
    source["release"] = 24
    runtime = output / "runtime"
    (runtime / "src").mkdir(parents=True)
    (runtime / "src/aliases.txt").write_bytes(sources["src/aliases.txt"])
    with tempfile.TemporaryDirectory(prefix="uv-journal-build-") as temporary:
        build = Path(temporary)
        for name, value in sources.items():
            target = build / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(value)
        classes = build / "classes"
        classes.mkdir()
        # Configuration is read from the exact source snapshot used by this build.
        properties = "@include=" + str(build / "uv_configs/template.properties") + "\nlogfile=simulator.log\n"
        properties += "\n".join(f"{k}={v}" for k, v in sorted(request["overrides"].items())) + "\n"
        (runtime / "capture.properties").write_text(properties)
        with (output / "build.log").open("w") as log:
            subprocess.run([javac, "--release", "24", "-d", str(classes)] + [str(build / n) for n in sources if n.endswith(".java")],
                           check=True, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        source["compiled_classes_sha256"] = {p.relative_to(classes).as_posix(): sha(p.read_bytes()) for p in sorted(classes.rglob("*.class"))}
        create_json(output / "source-manifest.json", source)
        with (output / "capture.log").open("w") as log:
            subprocess.run([java, "-cp", str(classes), "JournalCaptureRunner", request["topology_id"]], cwd=runtime,
                           check=True, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    result = validate_capture(output)
    reject_duplicate(output, result["public_fingerprint"])
    create_json(output / "validation.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path)
    parser.add_argument("--name")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--validate", type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if args.validate:
        require(not args.execute and not args.request and not args.name, "Validation is read-only")
        print(json.dumps(validate_capture(args.validate), indent=2))
        return
    require(args.request and args.name, "Supply --request and --name; default is preview only")
    request = validate_request(read(args.request))
    output = output_path(A2, request["purpose"], args.name)
    require(args.java and args.javac and args.timeout > 0, "Existing Java/Javac and positive timeout required")
    if not args.execute:
        print(json.dumps({"status": "preview-only", "output": str(output), "request": request}, indent=2))
        return
    print(json.dumps(capture(request, output, args.javac, args.java, args.timeout), indent=2))


if __name__ == "__main__":
    main()
