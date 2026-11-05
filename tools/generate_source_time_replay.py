"""Generate C++ fixture values from the shared JSON; no mapping implementation here."""
import hashlib
import json
from pathlib import Path
import sys

def generate(source, destination):
    raw = Path(source).read_bytes(); data = json.loads(raw)
    if data["schema"] != "nova-source-time-replay-v1" or data["policyVersion"] != 1:
        raise ValueError("unsupported source timing fixture")
    out = ['// Shared replay SHA256: ' + hashlib.sha256(raw).hexdigest(),
           'static std::vector<ReplayCase> replayCases() { std::vector<ReplayCase> out;']
    out.insert(1, 'static bool replayPolicyDefaultsMatch() { DeckSourceClockPolicy p; return ' + ' && '.join(f'p.{k} == {v}' for k,v in data['policy'].items()) + '; }')
    for case in data["cases"]:
        out.append('{ ReplayCase c; c.name = ' + json.dumps(case['name']) + ';')
        for name, value in (data['policy'] | case['policyOverrides']).items():
            if not name.isidentifier() or not isinstance(value, int): raise ValueError('invalid policy')
            out.append(f'c.policy.{name} = {value};')
        out.append(f'c.capacity = {case["capacity"]}; c.period = {case["admittedPeriodUs"]};')
        for step in case['steps']:
            i,e=step['input'],step['expected']
            val=lambda k: -1 if e.get(k) is None else e[k]
            out.append('c.steps.push_back({' + ','.join(map(str,[i['frame'],i['rtpTimestamp'],i['anchorUs'],i['decodedUs'] if i['decodedUs'] is not None else -1,int(i['resetBefore']),val('targetUs'),val('sourceUs'),val('reserveUs'),val('reserveLessThan')])) + ',' + json.dumps(e['state']) + ',' + json.dumps(e['reason'] or '') + ',' + str(val('epoch')) + '});')
        out.append('out.push_back(c); }')
    out.append('return out; }')
    Path(destination).write_text('\n'.join(out)+'\n')

if __name__ == '__main__': generate(*sys.argv[1:])
