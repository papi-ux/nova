"""Generate C++ fixture values, including compact deterministic series, never mapper answers."""
import hashlib
import json
from pathlib import Path
import sys

def generate(source, destination):
    raw = Path(source).read_bytes(); data = json.loads(raw)
    if data["schema"] != "nova-source-time-replay-v1" or data["policyVersion"] != 1:
        raise ValueError("unsupported source timing fixture")
    contract_raw = (Path(source).parent.parent / 'source-time-contract-v1.json').read_bytes()
    contract = json.loads(contract_raw)
    if (data['policy'] != contract['policy'] or data['policyVersion'] != contract['policyVersion'] or
            data['offsetInstabilityRevision'] != contract['offsetInstabilityRevision']):
        raise ValueError('contract/fixture policy drift')
    out = ['// Shared replay SHA256: ' + hashlib.sha256(raw).hexdigest(),
           '// Source-time contract SHA256: ' + hashlib.sha256(contract_raw).hexdigest(),
           '// Policy version: ' + str(data['policyVersion']) + '; instability revision: ' + str(data['offsetInstabilityRevision']),
           '// Base policy: ' + json.dumps(data['policy'], sort_keys=True),
           'static std::vector<ReplayCase> replayCases() { std::vector<ReplayCase> out;']
    out.insert(1, 'static bool replayPolicyDefaultsMatch() { DeckSourceClockPolicy p; return ' + ' && '.join(f'p.{k} == {v}' for k,v in data['policy'].items()) + '; }')
    for case in data["cases"]:
        out.append('{ ReplayCase c; c.name = ' + json.dumps(case['name']) + ';')
        for name, value in (data['policy'] | case['policyOverrides']).items():
            if not name.isidentifier() or not isinstance(value, int): raise ValueError('invalid policy')
            out.append(f'c.policy.{name} = {value};')
        out.append(f'c.capacity = {case["capacity"]}; c.period = {case["admittedPeriodUs"]};')
        for step in case.get('steps', []):
            i,e=step['input'],step['expected']
            val=lambda k: -1 if e.get(k) is None else e[k]
            out.append('c.steps.push_back({' + ','.join(map(str,[i['frame'],i['rtpTimestamp'],i['anchorUs'],i['decodedUs'] if i['decodedUs'] is not None else -1,int(i['resetBefore']),val('targetUs'),val('sourceUs'),val('reserveUs'),val('reserveLessThan')])) + ',' + json.dumps(e['state']) + ',' + json.dumps(e['reason'] or '') + ',' + str(val('epoch')) + '});')
        # The shared JSON describes integer trace generation and independent
        # aggregate bounds. Keep long traces compact rather than emitting a
        # mapper implementation or hundreds of thousands of C++ statements.
        if 'series' in case:
            for name, value in case['series'].items():
                if not name.isidentifier() or not isinstance(value, int):
                    raise ValueError('invalid deterministic series value')
                out.append(f'c.series.{name} = {value};')
        out.append('out.push_back(std::move(c)); }')
    out.append('return out; }')
    Path(destination).write_text('\n'.join(out)+'\n')

if __name__ == '__main__': generate(*sys.argv[1:])
