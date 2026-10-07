"""Stage the source-only tree (no APKs, build outputs or secrets) for archiving."""
import os
import shutil

SRC = '/workspace/hh'
DST = '/tmp/hh-src/src'
if os.path.exists(DST):
    shutil.rmtree(DST)

EXCLUDE_DIRS = {'.git', 'build', '.gradle', '__pycache__', 'pkg', 'work',
                'intermediates', 'outputs', '.idea', 'node_modules'}
EXCLUDE_EXT = {'.apk', '.dex', '.class', '.idsig', '.aar', '.so', '.log',
               '.keystore'}
EXCLUDE_NAMES = {'store.json', 'store.tmp', 'local.properties', '.DS_Store',
                 'server.log', 'emu.log', 'gradle_build.log'}

count = 0
for root, dirs, files in os.walk(SRC):
    rel = os.path.relpath(root, SRC)
    rel = '' if rel == '.' else rel
    dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]

    out = os.path.join(DST, rel)
    os.makedirs(out, exist_ok=True)
    for f in files:
        if f in EXCLUDE_NAMES:
            continue
        if os.path.splitext(f)[1].lower() in EXCLUDE_EXT:
            continue
        fp = os.path.join(root, f)
        if os.path.getsize(fp) > 4 * 1024 * 1024:
            continue
        shutil.copy2(fp, os.path.join(out, f))
        count += 1

print('kopyalanan dosya:', count)
