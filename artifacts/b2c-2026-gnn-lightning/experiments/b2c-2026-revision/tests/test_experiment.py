import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import run_directed_liquidity_classification as experiment
import torch


class GroupedSplitTests(unittest.TestCase):
    def setUp(self):
        self.groups = torch.arange(60).repeat_interleave(2)
        self.y = torch.tensor(([0, 0] * 20) + ([0, 1] * 20) + ([1, 1] * 20), dtype=torch.float32)

    def test_channel_disjoint_and_stratified(self):
        for seed in range(10):
            train, test = experiment.grouped_stratified_split(self.y, self.groups, seed)
            self.assertFalse(set(self.groups[train].tolist()) & set(self.groups[test].tolist()))
            self.assertEqual(sorted(torch.cat([train, test]).tolist()), list(range(len(self.y))))
            for subset in [train, test]:
                _, counts = self.groups[subset].unique(return_counts=True)
                self.assertTrue(torch.all(counts == 2))
                self.assertAlmostEqual(self.y[subset].mean().item(), 0.5)
            self.assertEqual(len(test), 36)

    def test_seed_reproduces_membership(self):
        a = experiment.grouped_stratified_split(self.y, self.groups, 4)
        b = experiment.grouped_stratified_split(self.y, self.groups, 4)
        c = experiment.grouped_stratified_split(self.y, self.groups, 5)
        self.assertTrue(all(torch.equal(x, y) for x, y in zip(a, b)))
        self.assertNotEqual(set(a[1].tolist()), set(c[1].tolist()))

    def test_rejects_incomplete_channel(self):
        with self.assertRaises(ValueError):
            experiment.grouped_stratified_split(self.y[:-1], self.groups[:-1], 0)


class BaselineTests(unittest.TestCase):
    def test_endpoint_model_ignores_other_nodes(self):
        torch.manual_seed(1)
        model = experiment.EndpointFeatureMlp(5, 7).eval()
        x = torch.randn(4, 5)
        edge_index = torch.tensor([[0, 2], [1, 3]])
        edge_attr = torch.randn(2, 7)
        with torch.no_grad():
            original = model(x, edge_index, edge_attr)[0]
            x[2:] += 100
            self.assertEqual(original.item(), model(x, edge_index, edge_attr)[0].item())
            x[0] += 10
            self.assertNotEqual(original.item(), model(x, edge_index, edge_attr)[0].item())

    def test_edge_only_model_ignores_node_features(self):
        model = experiment.EdgeFeatureMlp(5, 7).eval()
        edge_index = torch.tensor([[0, 1], [1, 0]])
        edge_attr = torch.randn(2, 7)
        with torch.no_grad():
            self.assertTrue(torch.equal(
                model(torch.zeros(2, 5), edge_index, edge_attr),
                model(torch.ones(2, 5) * 100, edge_index, edge_attr),
            ))


if __name__ == "__main__":
    unittest.main()
