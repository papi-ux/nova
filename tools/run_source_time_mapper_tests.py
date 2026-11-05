"""Small clang-only replay entrypoint, including a required arrival-only negative control."""
from pathlib import Path
import argparse
import subprocess
import tempfile
import sys
from generate_source_time_replay import generate
ROOT=Path(__file__).resolve().parents[1]
def run(work):
    header=work/'source_time_replay.h'; generate(ROOT/'shared/presentation/tests/source-time-replay-v1.json',header)
    command=['clang++','-std=c++20','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-omit-frame-pointer','-I'+str(ROOT/'clients/deck/src'),'-DNOVA_SOURCE_REPLAY_HEADER="'+str(header)+'"',str(ROOT/'clients/deck/src/runtime/deck_source_clock_mapper.cpp'),str(ROOT/'clients/deck/tests/deck_source_clock_mapper_test.cpp'),'-o',str(work/'mapper')]
    for name,cmd,want in [('build',command,0),('replay',[str(work/'mapper')],0),('arrival-only',[str(work/'mapper'),'--arrival-only'],1)]:
        p=subprocess.run(cmd,capture_output=True,text=True,timeout=60);(work/(name+'.log')).write_text(str(cmd)+'\n'+p.stdout+p.stderr+'exit='+str(p.returncode)+'\n');print(p.stdout+p.stderr,end='')
        if p.returncode!=want: return 1
    return 0
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--work-dir',type=Path);args=parser.parse_args()
    if args.work_dir:
        args.work_dir.mkdir(exist_ok=False,parents=True);sys.exit(run(args.work_dir.resolve()))
    with tempfile.TemporaryDirectory(prefix='nova-mapper-') as path:sys.exit(run(Path(path)))
