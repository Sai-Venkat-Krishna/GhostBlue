import json, subprocess, sys, os, glob
swift, kotlin, fixtures = sys.argv[1], sys.argv[2], sys.argv[3]
env = dict(os.environ)

def run(binary, args):
    p = subprocess.run([binary, "parse"] + args, capture_output=True, text=True, env=env, timeout=120)
    out = p.stdout
    starts = [i for i, line in enumerate(out.splitlines()) if line.startswith("{") or line.startswith("[")]
    if p.returncode != 0 or not starts:
        # Swift prints errors to stdout, Kotlin to stderr.
        err = ((p.stderr.strip() or p.stdout.strip()).splitlines() or ["?"])[-1]
        return ("error", err)
    text = "\n".join(out.splitlines()[starts[0]:])
    return ("ok", json.loads(text))

def normalize(value):
    if isinstance(value, dict):
        return {k: normalize(v) for k, v in value.items() if k not in ("lastUpdated", "accountId")}
    if isinstance(value, list):
        return [normalize(v) for v in value]
    return value

cases = [(["-t", "vehicles"], "vehicles.json")]
for path in sorted(glob.glob(os.path.join(fixtures, "status-*.json"))):
    name = os.path.basename(path)
    cases.append((["-t", "status", "--electric"], name))
    cases.append((["-t", "status"], name))

failures = 0
for args, name in cases:
    path = os.path.join(fixtures, name)
    s, k = run(swift, args + [path]), run(kotlin, args + [path])
    label = f"{name} {' '.join(a for a in args if a.startswith('--')) or '(gas)'}"
    if s[0] == "error" or k[0] == "error":
        same = s[0] == k[0]
        print(f"{'SAME ' if same else 'DIFF '} {label}: swift={s[1] if s[0]=='error' else 'ok'} | kotlin={k[1] if k[0]=='error' else 'ok'}")
        failures += 0 if same else 1
        continue
    a, b = normalize(s[1]), normalize(k[1])
    if a == b:
        print(f"SAME  {label}")
    else:
        failures += 1
        print(f"DIFF  {label}\n  swift : {json.dumps(a, sort_keys=True)}\n  kotlin: {json.dumps(b, sort_keys=True)}")
print(f"\n{len(cases) - failures}/{len(cases)} cases identical")
sys.exit(1 if failures else 0)
