#!/usr/bin/env python3
"""Preview, capture or independently verify one journal O/T trajectory. No ML or stage loop."""
import argparse
import hashlib
import json
import math
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

from uv_journal_capture import canonical, create_json, read, require, sha, source_snapshot

REPO = Path(__file__).resolve().parents[2]
B = REPO.parent / 'b'


def validate_request(request, b=B):
    require(set(request) == {'schema', 'purpose', 'topology_id', 'overrides', 'traffic'}, 'Unexpected O/T request fields')
    require(request['schema'] == 'journal-ot-request-v1' and request['purpose'] in ('acceptance', 'pilot'), 'Unsupported O/T request')
    require(isinstance(request['topology_id'], str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', request['topology_id']), 'Invalid topology identity')
    o, t = request['overrides'], request['traffic']
    require(isinstance(o, dict) and all(type(v) is int and 0 < v < 2**31 for v in o.values()), 'Positive integer overrides required')
    require({'bootstrap_nodes', 'bootstrap_blocks', 'seed'} <= set(o), 'Missing bootstrap settings')
    require(set(t) == {'node_events_per_block', 'min_amount_sat', 'max_amount_sat', 'max_fees_sat', 'pathfinder', 'drain_timeout_ms', 'batches'}, 'Unexpected traffic fields')
    require(type(t['node_events_per_block']) in (int, float) and math.isfinite(t['node_events_per_block']) and t['node_events_per_block'] > 0, 'Invalid traffic rate')
    require(all(type(t[k]) is int for k in ('min_amount_sat', 'max_amount_sat', 'max_fees_sat', 'drain_timeout_ms')), 'Integer invoice settings required')
    require(0 < t['min_amount_sat'] < t['max_amount_sat'] < 2**31-1 and 0 <= t['max_fees_sat'] < 2**31 and 0 < t['drain_timeout_ms'] <= 120000, 'Invalid invoice settings')
    require(t['pathfinder'] == 'lnd', 'Explicit LND required')
    require(isinstance(t['batches'], list) and 1 <= len(t['batches']) <= 3, 'One to three batches required')
    ids = {'o'}
    rate = t['node_events_per_block'] * o['bootstrap_nodes']
    require(rate.is_integer() if isinstance(rate, float) else True, 'This pilot requires integer nominal requests per iteration')
    for batch in t['batches']:
        require(set(batch) == {'id', 'blocks', 'requested'} and isinstance(batch['id'], str)
                and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', batch['id']) and batch['id'] not in ids, 'Invalid/duplicate batch identity')
        ids.add(batch['id'])
        require(type(batch['blocks']) is int and 0 < batch['blocks'] <= 1000 and type(batch['requested']) is int
                and batch['requested'] == rate * batch['blocks'], 'Exposure must match native nominal rate')
    if request['purpose'] == 'acceptance':
        allowed = {'bootstrap_nodes', 'bootstrap_blocks', 'seed', 'max_threads'} | {
            f'profile.{profile}.{bound}_channels' for profile in ('default', 'small', 'medium', 'hub') for bound in ('min', 'max')}
        require(set(o) <= allowed and 'max_threads' in o and request['topology_id'].startswith('acceptance-ot-'), 'Acceptance identity/settings required')
        require(2 <= o['bootstrap_nodes'] <= 20 and 2 <= o['bootstrap_blocks'] <= 30 and 2 <= o.get('max_threads', 32) <= 64, 'Acceptance capture must be small')
        require(sum(x['blocks'] for x in t['batches']) <= 10 and sum(x['requested'] for x in t['batches']) <= 200, 'Acceptance traffic must be small')
        planned = [row['bootstrap_seed'] for stage in ('pilot', 'main')
                   for row in read(b.parent / f'a2/preflight/{stage}-plan.json')['topologies']]
        require(o['seed'] not in planned, 'Acceptance seed overlaps A2 study')
    else:
        # No pilot roster is created by implementation/acceptance. Future preparation must supply it.
        plan = b / 'configs/ot-pilot-plan.json'
        require(plan.is_file() and not plan.is_symlink(), 'O/T pilot roster has not been prepared')
        require(request in read(plan)['requests'], 'Unplanned O/T pilot request')
        require(o['bootstrap_nodes'] == 500 and o['bootstrap_blocks'] == 100 and set(o) == {'bootstrap_nodes', 'bootstrap_blocks', 'seed'}, 'Pinned pilot opening settings required')
        require(t == {'node_events_per_block': 0.02, 'min_amount_sat': 1000, 'max_amount_sat': 100000,
                      'max_fees_sat': 1000, 'pathfinder': 'lnd', 'drain_timeout_ms': 120000,
                      'batches': [{'id': 't500', 'blocks': 50, 'requested': 500}, {'id': 't2500', 'blocks': 200, 'requested': 2000},
                                  {'id': 't10000', 'blocks': 750, 'requested': 7500}]}, 'Pinned pilot traffic required')
    return request


def output_path(b, purpose, name):
    require(isinstance(name, str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', name), 'Invalid output name')
    require(b.resolve() == b and b.name == 'b' and b.parent.name == 'gnn-lightning-journal'
            and (b / 'minimal-o-t-pilot.md').is_file(), 'Not the journal O/T workspace')
    require(purpose in ('acceptance', 'pilot'), 'Invalid output purpose')
    group = b / ('validation/captures' if purpose == 'acceptance' else 'incoming')
    require(group.resolve() == group, 'Symlink output group forbidden')
    out = group / name
    require(not out.exists() and not out.is_symlink(), 'Overwrite/resume is forbidden')
    return out


def file_hash(root, name, expected):
    require(not Path(name).is_absolute() and '..' not in Path(name).parts, 'Unsafe artifact name')
    p = root / name
    require(p.is_file() and p.resolve() == p and not p.is_symlink(), 'Incomplete or redirected artifact: ' + name)
    require(sha(p.read_bytes()) == expected, 'Artifact hash mismatch: ' + name)


def validate_trajectory(directory):
    root = Path(directory).absolute()
    require(root.resolve() == root and not any((root / n).exists() for n in ('failure.json', 'launcher-failure.json')), 'Failed or redirected trajectory')
    for name in ('trajectory.json', 'request.json', 'source-manifest.json', 'expanded-config.json', 'opening-cleanup.json'):
        require((root / name).is_file() and not (root / name).is_symlink(), 'Incomplete trajectory: ' + name)
    receipt, request, source, config, cleanup = [read(root / n) for n in
        ('trajectory.json', 'request.json', 'source-manifest.json', 'expanded-config.json', 'opening-cleanup.json')]
    require(receipt['schema'] == 'journal-ot-trajectory-v1' and receipt['settled'] is True, 'Invalid completion receipt')
    require(request['schema'] == 'journal-ot-request-v1' and source['schema'] == 'journal-ot-source-v1', 'Invalid schemas')
    require(receipt['topology_id'] == request['topology_id'], 'Parent mismatch')
    provenance = receipt['provenance']
    require(provenance['pending_checked'] is True and provenance['capture_boundary'] == 'journal-ot-joined-settled-v1'
            and provenance['pathfinder'] == request['traffic']['pathfinder'] == 'lnd', 'Missing settled/LND contract')
    for key, name in (('request', 'request.json'), ('expanded_config', 'expanded-config.json'),
                      ('source_manifest', 'source-manifest.json'), ('opening_cleanup', 'opening-cleanup.json')):
        file_hash(root, name, provenance[key + '_sha256'])
    require(provenance['bootstrap_seed'] == config['seed'] and config['logfile'] == 'simulator.log', 'Seed/log mismatch')
    require(all(str(config[k]) == str(v) for k, v in request['overrides'].items()), 'Ignored override')
    require(provenance['uv_commit'] == source['base_commit'] and re.fullmatch('[0-9a-f]{40}', source['base_commit'])
            and type(source['dirty']) is bool and provenance['source_dirty'] == source['dirty'], 'Revision mismatch')
    require(provenance['source_tree_sha256'] == source['source_tree_sha256'] == sha(canonical(source['files_sha256'])), 'Source tree mismatch')
    file_hash(root, 'source.zip', source['source_archive_sha256'])
    with zipfile.ZipFile(root / 'source.zip') as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())) and set(archive.namelist()) == set(source['files_sha256']), 'Source inventory mismatch')
        for name, digest in source['files_sha256'].items():
            require(not Path(name).is_absolute() and '..' not in Path(name).parts and sha(archive.read(name)) == digest, 'Source archive mismatch')
    require(cleanup['schema'] == 'journal-ot-opening-cleanup-v1' and cleanup['funded_channels_changed'] is False
            and cleanup['onchain_balances_changed'] is False, 'Invalid opening reconciliation')
    expected_names = ['o'] + [b['id'] for b in request['traffic']['batches']]
    require([r['checkpoint'] for r in receipt['checkpoints']] == expected_names, 'Checkpoint order mismatch')
    total = 0; last_height = -1; first_public = first_private = first_structure = None
    channel_changes = []; all_hashes = set(); all_ids = []; total_success = 0; first_balances = None
    for index, entry in enumerate(receipt['checkpoints']):
        name = entry['checkpoint']
        require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', name), 'Unsafe checkpoint ID')
        d = root / 'checkpoints' / name
        file_hash(d, 'capture.json', entry['receipt_sha256']); cp = read(d / 'capture.json')
        require(cp['schema'] == 'journal-ot-checkpoint-v1' and cp['settled'] is True and cp['repeated_capture_identical'] is True
                and cp['topology_id'] == request['topology_id'] and cp['checkpoint'] == name, 'Invalid checkpoint identity/boundary')
        expected_files = {'public.json', 'truth.json', 'private.json'} | ({'invoices.json'} if index else set())
        require(set(cp['files_sha256']) == expected_files, 'Unexpected checkpoint artifact inventory')
        for filename, digest in cp['files_sha256'].items(): file_hash(d, filename, digest)
        pub, truth, private = [read(d / x) for x in ('public.json', 'truth.json', 'private.json')]
        require(set(pub) == {'schema', 'topology_id', 'nodes', 'channels'} and pub['schema'] == 'journal-ot-public-v1', 'Public/private separation')
        require(truth['schema'] == 'journal-ot-truth-v1' and private['schema'] == 'journal-ot-private-v1'
                and pub['topology_id'] == truth['topology_id'] == private['topology_id'] == request['topology_id'], 'State schema/identity mismatch')
        require(truth['checkpoint'] == name and truth['provenance'] == provenance and truth['settled'] is True, 'Truth provenance mismatch')
        nodes, channels = pub['nodes'], pub['channels']
        require(nodes == sorted(set(nodes)) and len(nodes) == config['bootstrap_nodes'], 'Node count/order mismatch')
        require(channels and channels == sorted(channels, key=lambda c: c['channel_id']), 'Channel order mismatch')
        fingerprint = sha(canonical({'nodes': nodes, 'channels': channels}))
        require(fingerprint == cp['public_fingerprint'] == truth['public_fingerprint'], 'Public fingerprint mismatch')
        structure = sha(canonical({'public_fingerprint': fingerprint, 'private_state': private}))
        require(structure == cp['structural_fingerprint'] == truth['structural_fingerprint'], 'Structural fingerprint mismatch')
        require(set(private['profiles']) == set(nodes), 'Missing private profile assignments')
        for node, state in private['profiles'].items():
            require(set(state) == {'profile', 'onchain_balance_sat'} and type(state['onchain_balance_sat']) is int
                    and state['onchain_balance_sat'] >= 0 and 'profile.' + state['profile'] + '.min_funding' in config, 'Invalid profile provenance')
        rows = {r['channel_id']: r for r in truth['channels']}
        require(len(rows) == len(truth['channels']) == len(channels) and set(rows) == {c['channel_id'] for c in channels}
                and set(rows) == set(private['routing_policies']), 'Channel identity mismatch')
        for c in channels:
            require(set(c) == {'channel_id', 'a', 'b', 'capacity_sat', 'a_to_b', 'b_to_a'} and re.fullmatch('jch-[0-9a-f]{64}', c['channel_id']), 'Public channel fields')
            require(c['a'] in nodes and c['b'] in nodes and c['a'] < c['b'] and type(c['capacity_sat']) is int and c['capacity_sat'] > 0, 'Channel endpoints/capacity')
            for direction in ('a_to_b', 'b_to_a'):
                require(set(c[direction]) == {'fee_base_msat', 'fee_rate_milli_msat'}
                        and all(type(v) is int and v >= 0 for v in c[direction].values()), 'Directional policy')
            row = rows[c['channel_id']]
            require(set(row) == {'channel_id', 'balance_a_sat', 'balance_b_sat', 'reserve_sat', 'pending_a_sat', 'pending_b_sat', 'commitment_number'}
                    and all(type(v) is int and v >= 0 for k,v in row.items() if k != 'channel_id'), 'Invalid truth values')
            require(row['balance_a_sat'] + row['balance_b_sat'] == c['capacity_sat'] and row['reserve_sat'] == c['capacity_sat']//100
                    and row['pending_a_sat'] == row['pending_b_sat'] == 0, 'Settled financial invariant')
            if index == 0:
                require(row['commitment_number'] == 0 and min(row['balance_a_sat'], row['balance_b_sat']) == 0, 'Not a genuine opening')
        if index == 0:
            first_public, first_private, first_structure = pub, private, structure
            first_balances = {key: row['balance_a_sat'] for key,row in rows.items()}
            for node, cancelled in cleanup['cancelled_unaccepted_requests'].items():
                require(node in nodes and isinstance(cancelled, dict), 'Invalid cleanup node')
                for peer, amount in cancelled.items():
                    require(peer in nodes and peer != node and type(amount) is int and amount > 0
                            and not any({c['a'],c['b']} == {node,peer} for c in channels), 'Cleanup of funded/invalid opening')
        else:
            require(pub == first_public and private == first_private and structure == first_structure, 'Structure/profile/policy drift')
            batch = read(d / 'invoices.json'); planned = request['traffic']['batches'][index-1]; traffic = request['traffic']
            p2p_pool = config['bootstrap_nodes'] if 2*config['bootstrap_nodes'] <= config['max_threads'] else config['max_threads']//2
            require(batch['invoice_pool_size'] == config['max_threads']-p2p_pool and batch['invoice_pool_size'] > 0, 'Effective invoice pool mismatch')
            require(batch['schema'] == 'journal-ot-invoices-v1' and batch['batch_id'] == name and batch['settled'] is True
                    and batch['infrastructure_failure'] is None and batch['generator_iterations'] == planned['blocks'], 'Invalid batch receipt')
            for k in ('node_events_per_block','min_amount_sat','max_amount_sat','max_fees_sat'):
                require(batch[k] == traffic[k], 'Traffic setting mismatch')
            require(last_height <= batch['start_height'] <= batch['injection_end_height'] <= batch['settled_height'] == cp['block_height'], 'Traffic height order')
            summary = batch['summary']; success = failed = amount = delivered = 0
            for row in batch['requests']:
                identity = row['request_id']; full_hash = row['invoice_hash']
                require(type(identity) is int and identity > 0 and full_hash not in all_hashes, 'Duplicate/invalid invoice identity')
                expected_hash = hashlib.sha256(identity.to_bytes((identity.bit_length()+8)//8, 'big', signed=True)).hexdigest()
                require(full_hash == expected_hash, 'Invoice full identity mismatch')
                all_hashes.add(full_hash); all_ids.append(identity)
                require(row['sender'] in nodes and row['destination'] in nodes and row['sender'] != row['destination'], 'Invalid invoice endpoints')
                require(type(row['amount_sat']) is int and traffic['min_amount_sat'] <= row['amount_sat'] <= traffic['max_amount_sat'], 'Invoice amount outside law')
                require(batch['start_height'] <= row['submitted_height'] <= batch['injection_end_height']
                        and row['submitted_height'] <= row['completed_height'] <= batch['settled_height'], 'Invoice height order')
                require(row['status'] in ('success','failure'), 'Unfinished/exceptional invoice')
                report = row['report']; ok = row['status'] == 'success'
                require(report['success'] is ok and report['sender'] == row['sender'] and report['dest'] == row['destination']
                        and report['amt'] == row['amount_sat'] and report['max_fees'] == traffic['max_fees_sat'] and report['path_finder'] == 'lnd', 'Invoice report mismatch')
                failures = sum(report[k] for k in report if k.startswith('attempt_failed_'))
                require(report['attempt_failed_timeout'] == 0 and report['attempted_paths'] == failures + int(ok), 'Unsafe timeout or attempt accounting mismatch')
                success += int(ok); failed += int(not ok); amount += row['amount_sat']; delivered += row['amount_sat'] if ok else 0
            require(summary == {'requested': len(batch['requests']), 'completed': len(batch['requests']), 'successful': success,
                                'failed': failed, 'exceptional': 0, 'unfinished': 0, 'requested_amount_sat': amount, 'delivered_amount_sat': delivered}, 'Invoice accounting mismatch')
            require(len(batch['requests']) == planned['requested'], 'Exposure mismatch')
            total += len(batch['requests']); total_success += success
        require(cp['cumulative_requests'] == total and cp['block_height'] >= last_height, 'Checkpoint exposure/time mismatch')
        last_height = cp['block_height']
        channel_changes.append({'checkpoint': name, 'changed_channels_from_o': sum(row['balance_a_sat'] != first_balances[key] for key,row in rows.items())})
    require(all_ids == list(range(1, total+1)) and receipt['total_requests'] == total, 'Missing/duplicate trajectory invoice')
    return {'status': 'passed', 'nodes': len(nodes), 'channels': len(channels), 'checkpoints': len(expected_names),
            'requested': total, 'successful': total_success, 'failed': total-total_success,
            'public_fingerprint': fingerprint, 'source_tree_sha256': source['source_tree_sha256'], 'channel_changes': channel_changes}


def capture(request, output, javac, java, timeout):
    sources, archive, source = source_snapshot(REPO)
    source['schema'] = 'journal-ot-source-v1'
    output.parent.mkdir(parents=True, exist_ok=True); output.mkdir()
    create_json(output/'request.json', request); (output/'source.zip').write_bytes(archive)
    source.update(source_archive_sha256=sha(archive), release=24,
                  compiler_version=subprocess.check_output([javac,'-version'],stderr=subprocess.STDOUT,text=True).strip(),
                  runtime_version=subprocess.check_output([java,'-version'],stderr=subprocess.STDOUT,text=True).strip())
    runtime = output/'runtime'; (runtime/'src').mkdir(parents=True)
    (runtime/'src/aliases.txt').write_bytes(sources['src/aliases.txt'])
    with tempfile.TemporaryDirectory(prefix='uv-journal-ot-build-') as tmp:
        build = Path(tmp)
        for name,value in sources.items():
            target=build/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(value)
        classes=build/'classes'; classes.mkdir()
        (runtime/'capture.properties').write_text('@include='+str(build/'uv_configs/template.properties')+'\nlogfile=simulator.log\n'
            +'\n'.join(f'{k}={v}' for k,v in sorted(request['overrides'].items()))+'\n')
        with (output/'build.log').open('w') as log:
            subprocess.run([javac,'--release','24','-d',str(classes)]+[str(build/n) for n in sources if n.endswith('.java')],check=True,stdout=log,stderr=subprocess.STDOUT,timeout=120)
        source['compiled_classes_sha256']={p.relative_to(classes).as_posix():sha(p.read_bytes()) for p in sorted(classes.rglob('*.class'))}
        create_json(output/'source-manifest.json',source)
        try:
            with (output/'capture.log').open('w') as log:
                subprocess.run([java,'-cp',str(classes),'JournalTrafficRunner'],cwd=runtime,check=True,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
        except (subprocess.SubprocessError,OSError) as error:
            create_json(output/'launcher-failure.json',{'status':'failed','error':str(error)})
            raise
    result=validate_trajectory(output)
    create_json(output/'validation.json',result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request',type=Path); parser.add_argument('--name'); parser.add_argument('--execute',action='store_true')
    parser.add_argument('--validate',type=Path); parser.add_argument('--java',default=shutil.which('java'))
    parser.add_argument('--javac',default=shutil.which('javac')); parser.add_argument('--timeout',type=int,default=240)
    args=parser.parse_args()
    if args.validate:
        require(not args.execute and not args.request and not args.name,'Validation is read-only')
        print(json.dumps(validate_trajectory(args.validate),indent=2)); return
    require(args.request and args.name,'Request/name required; default is preview only')
    request=validate_request(read(args.request)); output=output_path(B,request['purpose'],args.name)
    require(args.java and args.javac and args.timeout>0,'Existing Java/Javac and positive timeout required')
    if not args.execute:
        print(json.dumps({'status':'preview-only','output':str(output),'request':request},indent=2)); return
    print(json.dumps(capture(request,output,args.javac,args.java,args.timeout),indent=2))

if __name__ == '__main__': main()
