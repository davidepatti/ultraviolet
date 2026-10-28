"""Handcrafted acceptance-contract tests; no live bootstrap or model fitting."""
import copy
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import uv_journal_capture as capture


def put(path, value):
    path.write_bytes(capture.canonical(value) + b"\n")


def fixture(root):
    request = {"schema": "journal-a2-capture-request-v1", "purpose": "acceptance", "topology_id": "acceptance-fixture",
               "overrides": {"bootstrap_nodes": 2, "bootstrap_blocks": 3, "seed": 727}}
    config = dict(request["overrides"], logfile="simulator.log")
    put(root / "request.json", request)
    put(root / "expanded-config.json", config)
    source_bytes = b"handcrafted source-hash fixture; not a simulator build"
    with zipfile.ZipFile(root / "source.zip", "w") as archive:
        archive.writestr("fixture.txt", source_bytes)
    hashes = {"fixture.txt": capture.sha(source_bytes)}
    source = {"base_commit": "a" * 40, "dirty": True, "files_sha256": hashes,
              "source_tree_sha256": capture.sha(capture.canonical(hashes)),
              "source_archive_sha256": capture.sha((root / "source.zip").read_bytes())}
    put(root / "source-manifest.json", source)
    policy = {"fee_base_msat": 100, "fee_rate_milli_msat": 200}
    public = {"schema": "journal-a2-public-v1", "topology_id": request["topology_id"], "nodes": ["a", "b"],
              "channels": [{"channel_id": "c1", "a": "a", "b": "b", "capacity_sat": 10000,
                            "a_to_b": policy, "b_to_a": policy}],
              "provenance": {"capture_id": "fixture", "bootstrap_seed": 727, "uv_commit": "a" * 40,
                             "source_tree_sha256": source["source_tree_sha256"], "source_dirty": True,
                             "source_manifest_sha256": capture.sha((root / "source-manifest.json").read_bytes()),
                             "expanded_config_sha256": capture.sha((root / "expanded-config.json").read_bytes()),
                             "pending_checked": True, "warnings": ["fixture"],
                             "capture_boundary": "fresh-bootstrap-terminal-workers-joined-v1"}}
    fingerprint = capture.sha(capture.canonical({k: public[k] for k in ("nodes", "channels")}))
    truth = {"schema": "journal-a2-truth-v1", "topology_id": request["topology_id"], "public_fingerprint": fingerprint,
             "regime": "O", "draw": 0, "allocation_seed": None, "settled": True,
             "channels": [{"channel_id": "c1", "balance_a_sat": 10000, "balance_b_sat": 0,
                           "reserve_sat": 100, "pending_a_sat": 0, "pending_b_sat": 0}]}
    put(root / "public.json", public)
    put(root / "opening-truth.json", truth)
    receipt = {"schema": "journal-a2-capture-receipt-v1", "topology_id": request["topology_id"],
               "repeated_capture_identical": True, "expanded_config_file": "expanded-config.json",
               "public_fingerprint": fingerprint, "source_manifest_sha256": public["provenance"]["source_manifest_sha256"]}
    for key, name in (("public", "public.json"), ("opening_truth", "opening-truth.json")):
        receipt[key + "_file"], receipt[key + "_sha256"] = name, capture.sha((root / name).read_bytes())
    put(root / "capture.json", receipt)
    return request


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.request = fixture(self.root)

    def change(self, name, mutate, resign=False):
        payload = capture.read(self.root / name)
        mutate(payload)
        put(self.root / name, payload)
        if resign:
            receipt = capture.read(self.root / "capture.json")
            key = {"public.json": "public_sha256", "opening-truth.json": "opening_truth_sha256"}[name]
            receipt[key] = capture.sha((self.root / name).read_bytes())
            put(self.root / "capture.json", receipt)

    def test_valid_separated_capture(self):
        self.assertEqual(capture.validate_capture(self.root)["directed_edges"], 2)

    def test_payload_tampering(self):
        self.change("opening-truth.json", lambda p: p.update(settled=False))
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            capture.validate_capture(self.root)

    def test_pending_rejected_even_with_new_receipt_hash(self):
        self.change("opening-truth.json", lambda p: p["channels"][0].update(pending_a_sat=1), True)
        with self.assertRaisesRegex(ValueError, "pending violation"):
            capture.validate_capture(self.root)

    def test_hidden_public_field_rejected(self):
        self.change("public.json", lambda p: p.update(balance=100), True)
        with self.assertRaisesRegex(ValueError, "field separation"):
            capture.validate_capture(self.root)

    def test_configuration_hash_rejected(self):
        self.change("expanded-config.json", lambda p: p.update(seed=99))
        with self.assertRaisesRegex(ValueError, "configuration hash"):
            capture.validate_capture(self.root)

    def test_source_archive_tampering_rejected(self):
        with (self.root / "source.zip").open("ab") as stream:
            stream.write(b"tampered")
        with self.assertRaisesRegex(ValueError, "archive hash"):
            capture.validate_capture(self.root)

    def add_cleanup(self):
        cleanup = {"schema": "journal-a2-opening-cleanup-v1", "topology_id": self.request["topology_id"],
                   "cancelled_unaccepted_requests": {}, "funded_channels_changed": False, "onchain_balances_changed": False}
        put(self.root / "opening-cleanup.json", cleanup)
        digest = capture.sha((self.root / "opening-cleanup.json").read_bytes())
        self.change("public.json", lambda p: p["provenance"].update(opening_cleanup_sha256=digest), True)
        receipt = capture.read(self.root / "capture.json")
        receipt["opening_cleanup_sha256"] = digest
        put(self.root / "capture.json", receipt)

    def test_cleanup_provenance_and_tamper_detection(self):
        self.add_cleanup()
        self.assertEqual(capture.validate_capture(self.root)["status"], "passed")
        self.change("opening-cleanup.json", lambda p: p.update(funded_channels_changed=True))
        with self.assertRaisesRegex(ValueError, "cleanup hash"):
            capture.validate_capture(self.root)

    def test_cleanup_cannot_reference_a_funded_channel(self):
        self.add_cleanup()
        self.change("opening-cleanup.json", lambda p: p.update(cancelled_unaccepted_requests={"a": {"b": 100}}))
        digest = capture.sha((self.root / "opening-cleanup.json").read_bytes())
        self.change("public.json", lambda p: p["provenance"].update(opening_cleanup_sha256=digest), True)
        receipt = capture.read(self.root / "capture.json")
        receipt["opening_cleanup_sha256"] = digest
        put(self.root / "capture.json", receipt)
        with self.assertRaisesRegex(ValueError, "funded channel"):
            capture.validate_capture(self.root)

    def test_source_revision_mismatch_rejected(self):
        self.change("public.json", lambda p: p["provenance"].update(uv_commit="b" * 40), True)
        with self.assertRaisesRegex(ValueError, "revision mismatch"):
            capture.validate_capture(self.root)

    def test_incomplete_or_redirected_capture_rejected(self):
        data = (self.root / "public.json").read_bytes()
        outside = self.root / "external.json"
        outside.write_bytes(data)
        (self.root / "public.json").unlink()
        (self.root / "public.json").symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "redirected"):
            capture.validate_capture(self.root)

    def test_no_overwrite_escape_or_b2c_destination(self):
        a2 = self.root / "gnn-lightning-journal/a2"
        (a2 / "configs").mkdir(parents=True)
        put(a2 / "configs/study.json", {})
        valid = capture.output_path(a2, "acceptance", "fresh")
        valid.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "overwrite"):
            capture.output_path(a2, "acceptance", "fresh")
        with self.assertRaisesRegex(ValueError, "Invalid capture name"):
            capture.output_path(a2, "acceptance", "../b2c")
        with self.assertRaisesRegex(ValueError, "Not the journal"):
            capture.output_path(self.root / "b2c", "acceptance", "fresh")
        (a2 / "incoming").symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            capture.output_path(a2, "study", "fresh")

    def test_load_and_study_mislabel_rejected(self):
        bad = dict(self.request, mode="load")
        with self.assertRaisesRegex(ValueError, "unsupported"):
            capture.validate_request(bad)
        bad = copy.deepcopy(self.request)
        bad["topology_id"] = "a2-pilot-000"
        with self.assertRaisesRegex(ValueError, "Acceptance-only"):
            capture.validate_request(bad)

    def test_preview_has_no_build_or_capture(self):
        a2 = self.root / "gnn-lightning-journal/a2"
        (a2 / "configs").mkdir(parents=True)
        (a2 / "preflight").mkdir()
        put(a2 / "configs/study.json", {})
        for stage in ("pilot", "main"):
            put(a2 / f"preflight/{stage}-plan.json", {"topologies": []})
        validate = capture.validate_request
        with mock.patch.object(capture, "A2", a2), \
                mock.patch.object(capture, "validate_request", side_effect=lambda request: validate(request, a2)), \
                 mock.patch.object(sys, "argv", ["capture", "--request", str(self.root / "request.json"), "--name", "preview-contract-test"]), \
                mock.patch.object(capture, "capture", side_effect=AssertionError("No execution during preview")), \
                mock.patch("sys.stdout", new_callable=io.StringIO) as output:
            capture.main()
            self.assertIn("preview-only", output.getvalue())

    def test_duplicate_fingerprint_rejected(self):
        existing = self.root / "previous"
        existing.mkdir()
        put(existing / "validation.json", {"public_fingerprint": "same"})
        with self.assertRaisesRegex(ValueError, "duplicate"):
            capture.reject_duplicate(self.root / "new", "same")


if __name__ == "__main__":
    unittest.main()
