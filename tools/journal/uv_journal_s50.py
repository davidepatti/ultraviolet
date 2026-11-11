"""Separate S50 validator. Historical O/T validation is untouched."""
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


def validate_attempt_accounting(report, success, resolution_model=None):
    require(resolution_model in (None, 'uv-shared-channel-htlc-v1'), 'Unknown HTLC resolution model')
    counters = [value for key, value in report.items() if key.startswith('attempt_failed_')]
    require(all(type(value) is int and value >= 0 for value in counters), 'Invalid attempt counters')
    require(resolution_model is not None or report['attempt_failed_timeout'] == 0, 'Unsafe legacy timeout')
    require(report['attempted_paths'] == sum(counters) + int(success), 'Attempt accounting mismatch')


def file_hash(root, name, expected):
    require(not Path(name).is_absolute() and '..' not in Path(name).parts, 'Unsafe artifact name')
    p = root / name
    require(p.is_file() and p.resolve() == p and not p.is_symlink(), 'Incomplete or redirected artifact: ' + name)
    require(sha(p.read_bytes()) == expected, 'Artifact hash mismatch: ' + name)


def validate_trajectory(directory, expected_source, expected_request):
    root = Path(directory).absolute()
    require(root.resolve() == root and not any((root / n).exists() for n in ('failure.json', 'launcher-failure.json')), 'Failed or redirected trajectory')
    for name in ('trajectory.json', 'request.json', 'source-manifest.json', 'expanded-config.json', 'opening-cleanup.json'):
        require((root / name).is_file() and not (root / name).is_symlink(), 'Incomplete trajectory: ' + name)
    receipt, request, source, config, cleanup = [read(root / n) for n in
        ('trajectory.json', 'request.json', 'source-manifest.json', 'expanded-config.json', 'opening-cleanup.json')]
    require(receipt['schema'] == 'journal-s50-trajectory-v1' and receipt['settled'] is True, 'Invalid completion receipt')
    require(request['schema'] == 'journal-s50-request-v1' and source['schema'] == 'journal-s50-source-v1', 'Invalid schemas')
    require(receipt['topology_id'] == request['topology_id'], 'Parent mismatch')
    require(request == expected_request, 'Frozen request/config drift')
    require(source == expected_source and source['base_commit'] == 'f3c663685ccdb7d069ecc2874ab1a8b2a7c6f774'
            and source['dirty'] is True, 'Frozen actual producer drift')
    require(sha(canonical(config)) == source['effective_configurations_sha256'][request['topology_id']], 'Frozen effective configuration drift')
    provenance = receipt['provenance']
    require(provenance['pending_checked'] is True and provenance['capture_boundary'] == 'journal-s50-joined-settled-v1'
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
    require(cleanup['schema'] == 'journal-s50-opening-cleanup-v1' and cleanup['funded_channels_changed'] is False
            and cleanup['onchain_balances_changed'] is False, 'Invalid opening reconciliation')
    expected_names = ['o', 's50'] + [b['id'] for b in request['traffic']['batches']]
    require([r['checkpoint'] for r in receipt['checkpoints']] == expected_names, 'Checkpoint order mismatch')
    total = 0; last_height = -1; first_public = first_private = first_structure = None
    channel_changes = []; all_hashes = set(); all_ids = []; total_success = 0; first_balances = None
    for index, entry in enumerate(receipt['checkpoints']):
        name = entry['checkpoint']
        require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', name), 'Unsafe checkpoint ID')
        d = root / 'checkpoints' / name
        file_hash(d, 'capture.json', entry['receipt_sha256']); cp = read(d / 'capture.json')
        require(cp['schema'] == 'journal-s50-checkpoint-v1' and cp['settled'] is True and cp['repeated_capture_identical'] is True
                and cp['topology_id'] == request['topology_id'] and cp['checkpoint'] == name, 'Invalid checkpoint identity/boundary')
        expected_files = {'public.json', 'truth.json', 'private.json'} | ({'invoices.json'} if index >= 2 else set())
        require(set(cp['files_sha256']) == expected_files, 'Unexpected checkpoint artifact inventory')
        for filename, digest in cp['files_sha256'].items(): file_hash(d, filename, digest)
        pub, truth, private = [read(d / x) for x in ('public.json', 'truth.json', 'private.json')]
        require(set(pub) == {'schema', 'topology_id', 'nodes', 'channels'} and pub['schema'] == 'journal-s50-public-v1', 'Public/private separation')
        require(truth['schema'] == 'journal-s50-truth-v1' and private['schema'] == 'journal-s50-private-v1'
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
        elif index == 1:
            require(pub == first_public and private == first_private and structure == first_structure, 'S50 structure drift')
            for c in channels:
                row = rows[c['channel_id']]
                require(row['balance_a_sat'] == c['capacity_sat']//2 and row['balance_b_sat'] == c['capacity_sat']-c['capacity_sat']//2, 'Wrong S50 rounding')
                require(row['commitment_number'] == int(first_balances[c['channel_id']] != c['capacity_sat']//2), 'Wrong S50 commitment')
        else:
            require(pub == first_public and private == first_private and structure == first_structure, 'Structure/profile/policy drift')
            batch = read(d / 'invoices.json'); planned = request['traffic']['batches'][index-2]; traffic = request['traffic']
            p2p_pool = config['bootstrap_nodes'] if 2*config['bootstrap_nodes'] <= config['max_threads'] else config['max_threads']//2
            require(batch['invoice_pool_size'] == config['max_threads']-p2p_pool and batch['invoice_pool_size'] > 0, 'Effective invoice pool mismatch')
            require(batch['schema'] == 'journal-s50-invoices-v1' and batch['batch_id'] == name and batch['settled'] is True
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
                validate_attempt_accounting(report, ok, batch.get('htlc_resolution'))
                success += int(ok); failed += int(not ok); amount += row['amount_sat']; delivered += row['amount_sat'] if ok else 0
            require(summary == {'requested': len(batch['requests']), 'completed': len(batch['requests']), 'successful': success,
                                'failed': failed, 'exceptional': 0, 'unfinished': 0, 'requested_amount_sat': amount, 'delivered_amount_sat': delivered}, 'Invoice accounting mismatch')
            require(len(batch['requests']) == planned['requested'], 'Exposure mismatch')
            total += len(batch['requests']); total_success += success
        require(cp['state_kind'] == ('genuine-opening' if index == 0 else 'exact-initial-allocation' if index == 1 else 'traffic'), 'State kind mismatch')
        require(cp['cumulative_requests'] == total and cp['block_height'] >= last_height, 'Checkpoint exposure/time mismatch')
        last_height = cp['block_height']
        channel_changes.append({'checkpoint': name, 'changed_channels_from_o': sum(row['balance_a_sat'] != first_balances[key] for key,row in rows.items())})
    file_hash(root, 'initialization.json', receipt['initialization_sha256'])
    init = read(root / 'initialization.json')
    require(init['allocation_version'] == 'raw-half-lexicographic-v1'
            and init['rounding'] == 'floor(C/2) to lexicographically smaller endpoint; remainder to other'
            and init['invoice_requests'] == init['invoice_successes'] == 0
            and init['traffic_seed'] == request['traffic_seed'], 'Initialization contract')
    for state, prefix in (('o','before'), ('s50','after')):
        for kind in ('truth', 'private'):
            file_hash(root, 'checkpoints/'+state+'/'+kind+'.json', init[prefix+'_'+kind+'_sha256'])
    for state, prefix in (('o','before'), ('s50','after')):
        combined = {'truth': read(root / ('checkpoints/'+state+'/truth.json')), 'private': read(root / ('checkpoints/'+state+'/private.json'))}
        require(init[prefix+'_private_state_sha256'] == sha(canonical(combined)+b'\n'), 'Private state digest mismatch')
    initial = read(root / 'checkpoints/o/truth.json')
    initial = {row['channel_id']: row for row in initial['channels']}
    deltas = {node: 0 for node in nodes}; moved = changed = 0
    for c in channels:
        delta = c['capacity_sat']//2 - initial[c['channel_id']]['balance_a_sat']
        moved += abs(delta); changed += delta != 0
        deltas[c['a']] += delta; deltas[c['b']] -= delta
    require(init['channels'] == len(channels) and init['changed_channels'] == changed
            and init['transferred_allocation_sat'] == moved and init['node_holding_deltas_sat'] == deltas
            and sum(deltas.values()) == 0, 'Initialization conservation/accounting')
    require(all_ids == list(range(1, total+1)) and receipt['total_requests'] == total, 'Missing/duplicate trajectory invoice')
    return {'status': 'passed', 'nodes': len(nodes), 'channels': len(channels), 'checkpoints': len(expected_names),
            'requested': total, 'successful': total_success, 'failed': total-total_success,
            'public_fingerprint': fingerprint, 'source_tree_sha256': source['source_tree_sha256'], 'channel_changes': channel_changes}

