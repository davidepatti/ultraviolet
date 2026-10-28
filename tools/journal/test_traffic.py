"""Guardrails and corruption tests; reads only an explicitly supplied tiny acceptance capture."""
import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import uv_journal_traffic as ot

class TrafficRequestTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.b = Path(temporary.name).resolve()/'gnn-lightning-journal/b'
        self.b.mkdir(parents=True)
        (self.b/'minimal-o-t-pilot.md').write_text('handcrafted test workspace')
        preflight = self.b.parent/'a2/preflight'
        preflight.mkdir(parents=True)
        for stage in ('pilot', 'main'):
            (preflight/(stage+'-plan.json')).write_text('{"topologies": []}')
        self.request = {'schema':'journal-ot-request-v1', 'purpose':'acceptance',
            'topology_id':'acceptance-ot-fixture',
            'overrides':{'bootstrap_nodes':6,'bootstrap_blocks':10,'seed':727,'max_threads':16},
            'traffic':{'node_events_per_block':1,'min_amount_sat':1000,'max_amount_sat':100000,
                'max_fees_sat':1000,'pathfinder':'lnd','drain_timeout_ms':10000,
                'batches':[{'id':'t6','blocks':1,'requested':6},{'id':'t18','blocks':2,'requested':12}]}}
        self.request_path = self.b/'acceptance-request.json'
        self.request_path.write_text(json.dumps(self.request))

    def test_request_and_preview_do_not_launch(self):
        ot.validate_request(self.request, self.b)
        validate = ot.validate_request
        with patch.object(ot, 'B', self.b), patch.object(ot, 'validate_request', side_effect=lambda r: validate(r, self.b)), \
             patch.object(sys,'argv',['uv_journal_traffic','--request',str(self.request_path),'--name','preview-ot-no-run']), \
             patch.object(ot,'capture',side_effect=AssertionError('No simulation permitted')), \
             patch('builtins.print'):
            ot.main()
        self.assertFalse((self.b/'validation/captures/preview-ot-no-run').exists())

    def test_reject_pilot_without_roster(self):
        self.request['purpose']='pilot'; self.request['topology_id']='journal-ot-pilot-000'
        with self.assertRaisesRegex(ValueError,'roster'): ot.validate_request(self.request, self.b)

    def test_pilot_roster_pins_worker_limit(self):
        request = copy.deepcopy(self.request)
        request.update(purpose='pilot', topology_id='journal-ot-pilot-000')
        request['overrides'] = {'bootstrap_nodes':500,'bootstrap_blocks':100,'seed':727,'max_threads':128}
        request['traffic'] = {'node_events_per_block':0.02,'min_amount_sat':1000,'max_amount_sat':100000,
            'max_fees_sat':1000,'pathfinder':'lnd','drain_timeout_ms':120000,
            'batches':[{'id':'t500','blocks':50,'requested':500},{'id':'t2500','blocks':200,'requested':2000},
                       {'id':'t10000','blocks':750,'requested':7500}]}
        (self.b/'configs').mkdir()
        roster = self.b/'configs/ot-pilot-plan.json'
        roster.write_text(json.dumps({'requests':[request]}))
        ot.validate_request(request,self.b)
        request['overrides']['max_threads'] = 4000
        roster.write_text(json.dumps({'requests':[request]}))
        with self.assertRaisesRegex(ValueError,'worker'):ot.validate_request(request,self.b)

    def test_reject_invalid_request_and_exposure(self):
        for edit in (lambda r:r['traffic'].update(node_events_per_block=float('nan')),
                     lambda r:r['traffic'].update(min_amount_sat=100000),
                     lambda r:r['traffic']['batches'][0].update(requested=7),
                     lambda r:r['traffic']['batches'][1].update(id='t6'),
                     lambda r:r['overrides'].update(bootstrap_nodes=500),
                     lambda r:r.update(load='forbidden')):
            bad=copy.deepcopy(self.request);edit(bad)
            with self.assertRaises(ValueError): ot.validate_request(bad, self.b)

    def test_destination_and_no_overwrite(self):
        with self.assertRaises(ValueError): ot.output_path(ot.B,'acceptance','../b2c')
        with tempfile.TemporaryDirectory() as tmp:
            b=Path(tmp).resolve()/'gnn-lightning-journal/b';b.mkdir(parents=True);(b/'minimal-o-t-pilot.md').write_text('fixture')
            dest=ot.output_path(b,'acceptance','new');dest.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError,'Overwrite'):ot.output_path(b,'acceptance','new')
            shutil.rmtree(b/'validation');(b/'validation').symlink_to(Path(tmp),target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'Symlink'):ot.output_path(b,'acceptance','new')


class TrafficAccountingTests(unittest.TestCase):
    def test_legacy_timeout_remains_invalid(self):
        with self.assertRaisesRegex(ValueError, 'Unsafe legacy timeout'):
            ot.validate_attempt_accounting({'attempted_paths': 1, 'attempt_failed_timeout': 1}, False)

    def test_resolved_expiry_and_successful_retry(self):
        ot.validate_attempt_accounting({'attempted_paths': 1, 'attempt_failed_timeout': 1}, False, 'uv-shared-channel-htlc-v1')
        ot.validate_attempt_accounting({'attempted_paths': 2, 'attempt_failed_timeout': 1}, True, 'uv-shared-channel-htlc-v1')

    def test_unknown_model_and_wrong_accounting_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown HTLC'):
            ot.validate_attempt_accounting({'attempted_paths': 1, 'attempt_failed_timeout': 0}, True, 'unknown')
        with self.assertRaisesRegex(ValueError, 'accounting mismatch'):
            ot.validate_attempt_accounting({'attempted_paths': 1, 'attempt_failed_timeout': 1}, True, 'uv-shared-channel-htlc-v1')


@unittest.skipUnless(os.environ.get('UV_OT_ACCEPTANCE'), 'Set UV_OT_ACCEPTANCE to the tiny saved acceptance capture')
class TrafficCaptureTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve()/'capture';shutil.copytree(os.environ['UV_OT_ACCEPTANCE'],self.root)

    def write(self,relative,value): (self.root/relative).write_bytes(ot.canonical(value)+b'\n')

    def rehash(self,checkpoint,filename):
        directory=Path('checkpoints')/checkpoint
        cp=ot.read(self.root/directory/'capture.json')
        cp['files_sha256'][filename]=ot.sha((self.root/directory/filename).read_bytes())
        self.write(directory/'capture.json',cp)
        top=ot.read(self.root/'trajectory.json')
        next(e for e in top['checkpoints'] if e['checkpoint']==checkpoint)['receipt_sha256']=ot.sha((self.root/directory/'capture.json').read_bytes())
        self.write('trajectory.json',top)

    def test_valid_capture(self):
        report=ot.validate_trajectory(self.root)
        self.assertEqual((report['nodes'],report['checkpoints'],report['requested']),(6,3,18))
        self.assertEqual(report['successful']+report['failed'],18)

    def test_missing_marker_and_failure_are_rejected(self):
        (self.root/'checkpoints/t6/capture.json').unlink()
        with self.assertRaises(ValueError):ot.validate_trajectory(self.root)
        self.write('failure.json',{'error':'fixture'})
        with self.assertRaisesRegex(ValueError,'Failed'):ot.validate_trajectory(self.root)

    def test_hash_tampering(self):
        with (self.root/'checkpoints/t6/truth.json').open('a') as f:f.write(' ')
        with self.assertRaisesRegex(ValueError,'hash mismatch'):ot.validate_trajectory(self.root)

    def test_public_hidden_field_even_with_repaired_hashes(self):
        p='checkpoints/t6/public.json';pub=ot.read(self.root/p);pub['profiles']={};self.write(p,pub);self.rehash('t6','public.json')
        with self.assertRaisesRegex(ValueError,'separation'):ot.validate_trajectory(self.root)

    def test_unsettled_truth_even_with_repaired_hashes(self):
        p='checkpoints/t6/truth.json';truth=ot.read(self.root/p);truth['channels'][0]['pending_a_sat']=1;self.write(p,truth);self.rehash('t6','truth.json')
        with self.assertRaisesRegex(ValueError,'financial invariant'):ot.validate_trajectory(self.root)

    def test_duplicate_invoice_even_with_repaired_hashes(self):
        p='checkpoints/t6/invoices.json';batch=ot.read(self.root/p)
        batch['requests'][1]=copy.deepcopy(batch['requests'][0]);self.write(p,batch);self.rehash('t6','invoices.json')
        with self.assertRaisesRegex(ValueError,'Duplicate'):ot.validate_trajectory(self.root)

    def test_wrong_accounting_even_with_repaired_hashes(self):
        p='checkpoints/t18/invoices.json';batch=ot.read(self.root/p);batch['summary']['successful']+=1;self.write(p,batch);self.rehash('t18','invoices.json')
        with self.assertRaisesRegex(ValueError,'accounting'):ot.validate_trajectory(self.root)

    def test_source_archive_corruption(self):
        with (self.root/'source.zip').open('ab') as f:f.write(b'corruption')
        with self.assertRaisesRegex(ValueError,'hash mismatch'):ot.validate_trajectory(self.root)

    def test_redirected_checkpoint_rejected(self):
        target=self.root/'checkpoints/t6';moved=self.root/'moved';target.rename(moved);target.symlink_to(moved,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'redirected'):ot.validate_trajectory(self.root)

if __name__=='__main__':unittest.main()
