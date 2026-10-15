import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from check_integrity import check_integrity
from compare_results import compare
from reproduce import ROOT, configure_output, reserve_output


class IntegrityTests(unittest.TestCase):
    def test_valid_and_changed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sample.txt").write_bytes(b"sample")
            manifest = {"algorithm": "sha256", "files": {"sample.txt": {
                "bytes": 6, "sha256": hashlib.sha256(b"sample").hexdigest()}}}
            (root / "checksums.json").write_text(json.dumps(manifest))
            self.assertEqual(check_integrity(root), 1)
            (root / "sample.txt").write_bytes(b"change")
            with self.assertRaises(ValueError):
                check_integrity(root)

    def test_rejects_escaping_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"algorithm": "sha256", "files": {"../outside": {"bytes": 0, "sha256": ""}}}
            (root / "checksums.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                check_integrity(root)


class ComparisonTests(unittest.TestCase):
    def test_identical_and_changed_scores(self):
        reference = {(100, 1000, 0): {"auc": 0.9, "ap": 0.8}}
        self.assertTrue(compare(reference, reference, 0)["passed"])
        actual = {(100, 1000, 0): {"auc": 0.91, "ap": 0.8}}
        self.assertFalse(compare(reference, actual, 1e-6)["passed"])

    def test_rejects_missing_runs(self):
        with self.assertRaises(ValueError):
            compare({(100, 1000, 0): {"auc": 0.9}}, {}, 1e-6)


class OutputTests(unittest.TestCase):
    def test_new_run_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = reserve_output(root, "new-run")
            self.assertEqual(output, root / "runs/new-run")
            with self.assertRaises(FileExistsError):
                reserve_output(root, "new-run")

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ["../paper", "/tmp/output", "a/b", ".", ""]:
                with self.assertRaises(ValueError):
                    reserve_output(Path(directory), name)

    def test_rejects_linked_run_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "elsewhere").mkdir()
            (root / "runs").symlink_to(root / "elsewhere", target_is_directory=True)
            with self.assertRaises(ValueError):
                reserve_output(root, "new")

    def test_redirects_both_study_versions(self):
        for version in ["revised", "submitted"]:
            module = SimpleNamespace()
            output = ROOT / "runs/test"
            configure_output(module, output, version)
            self.assertEqual(module.ROOT, ROOT)
            self.assertEqual(module.RESULTS_DIR, output / "results")
            self.assertEqual(module.FIGURES_DIR, output / "figures")
            if version == "submitted":
                self.assertEqual(module.METRICS_CSV, output / "results/directed-liquidity-metrics.csv")
                self.assertEqual(module.METHODOLOGY_FIG, output / "figures/methodology-dataflow.pdf")


if __name__ == "__main__":
    unittest.main()
