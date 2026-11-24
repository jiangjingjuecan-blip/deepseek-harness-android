"""Stage verified upstream files. Only Android's installer places executable pages on device."""
import concurrent.futures
import hashlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/runtime.lock.json'
CACHE = ROOT / 'runtime-cache'
ABI = sys.argv[1] if len(sys.argv) == 2 else 'arm64-v8a'
PLATFORMS = {'arm64-v8a': ('arm64', 'aarch64'), 'x86_64': ('x64', 'x86-64')}
DSH_ARCH, LD_ARCH = PLATFORMS[ABI]


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def download(item):
    key, spec = item
    path = CACHE / 'archives' / spec['url'].split('/')[-1]
    if not path.exists() or digest(path) != spec['sha256']:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as tmp:
            with urllib.request.urlopen(spec['url'], timeout=120) as response:
                shutil.copyfileobj(response, tmp)
        if digest(Path(tmp.name)) != spec['sha256']:
            os.unlink(tmp.name)
            raise ValueError(f'Checksum mismatch for {key}')
        os.replace(tmp.name, path)
    return key, path


def stage(target, files):
    libs = target / 'jni' / ABI
    assets = target / 'assets'
    data = assets / 'guest'
    notices = assets / 'licenses'
    for folder in [libs, data, notices]:
        folder.mkdir(parents=True)
    bindings = []

    def guest(src, guest_path, name):
        shutil.copyfile(src, libs / name)
        bindings.append(f'{name}\t{guest_path}')

    with tempfile.TemporaryDirectory(dir=CACHE) as work:
        base = Path(work)
        for key, path in files.items():
            if path.suffix == '.deb':
                subprocess.run(['dpkg-deb', '-x', path, base], check=True)
        termux = base / 'data/data/com.termux/files/usr'
        shutil.copyfile(termux / 'bin/proot', libs / 'libproot.so')
        # Android only installs lib*.so entries. Keep the non-versioned dependency in that directory.
        subprocess.run(['patchelf', '--replace-needed', 'libtalloc.so.2', 'libtalloc.so', libs / 'libproot.so'], check=True)
        for src, name in [('libexec/proot/loader','libproot_loader.so'), ('lib/libtalloc.so.2','libtalloc.so'),
                          ('lib/libandroid-shmem.so','libandroid-shmem.so')]:
            shutil.copyfile(termux / src, libs / name)
        shutil.copyfile(termux / 'share/doc/libandroid-shmem/copyright', notices / 'libandroid-shmem')
        (notices / 'termux-packages.txt').write_text(
            'PRoot: https://github.com/termux/proot (GPL-2)\n'
            'libtalloc: https://talloc.samba.org/ (see GPL-3 and LGPL-3)\n'
            'Android packages and build recipes: https://github.com/termux/termux-packages\n')
        platform = 'linux-' + DSH_ARCH + '-gnu'
        package = 'node-addon-require-builtin-' + platform
        binary = platform + '-napi-v9.node'
        # The SEA loader verifies these bytes against its bundled copy before dlopen.
        with tarfile.open(files['node-addon']) as archive:
            (libs / 'libdsh_builtin.so').write_bytes(archive.extractfile('package/prebuilt/' + binary).read())
            (notices / 'node-addon-require-builtin').write_bytes(archive.extractfile('package/LICENSE').read())
        bindings.append(f'libdsh_builtin.so\t/opt/native-cache/{package}/0.1.9/{platform}/{binary}')
        linux = next((base / 'usr').glob('*-linux-gnu/lib'))
        loader = f'ld-linux-{LD_ARCH}.so.' + ('1' if DSH_ARCH == 'arm64' else '2')
        loader_path = ('/lib/' if DSH_ARCH == 'arm64' else '/lib64/') + loader
        guest(linux / loader, loader_path, 'libguest_ld.so')
        for lib in ['libc.so.6', 'libdl.so.2', 'libm.so.6', 'libpthread.so.0', 'librt.so.1',
                    'libresolv.so.2', 'libstdc++.so.6', 'libgcc_s.so.1', 'libutil.so.1']:
            guest(linux / lib, '/lib/' + lib, 'libguest_' + lib.split('.')[0] + '.so')
        guest(next(base.rglob('bin/busybox')), '/bin/busybox', 'libguest_busybox.so')
        if ABI == 'x86_64':
            shim = base / 'rename.so'
            subprocess.run(['x86_64-linux-gnu-gcc', '-shared', '-fPIC', '-O2', '-Wl,-z,relro,-z,now',
                            ROOT/'tools/guest_rename.c', '-o', shim], check=True)
            guest(shim, '/lib/chat-rename.so', 'libguest_rename.so')
        # The official executable resolves its ripgrep helper and resource manifest relative to argv[0].
        with zipfile.ZipFile(files['wheel']) as z:
            stem = 'deepseek-harness-sdk-runtime-linux-' + DSH_ARCH
            for suffix, name in [('', 'libdsh.so'), ('-rg', 'libdsh_rg.so')]:
                member = 'deepseek_harness_runtime/runtime/' + stem + suffix
                with z.open(member) as src, (libs / name).open('wb') as dst:
                    shutil.copyfileobj(src, dst)
                bindings.append(f'{name}\t/opt/dsh/{stem}{suffix}')
            for member in z.namelist():
                if member.endswith('downloads.json'):
                    (data / 'downloads.json').write_bytes(z.read(member))
                if '.dist-info/licenses/' in member and not member.endswith('/'):
                    (notices / ('dsh-' + Path(member).name)).write_bytes(z.read(member))
        (data / 'ca-certificates.crt').write_bytes(b'\n'.join(
            p.read_bytes() for p in sorted((base/'usr/share/ca-certificates/mozilla').glob('*.crt'))))
        for copy in (base / 'usr/share/doc').glob('*/copyright'):
            if copy.exists():
                shutil.copyfile(copy, notices / copy.parent.name)
        for license_name in ['GPL-2', 'GPL-3', 'LGPL-2.1', 'LGPL-3']:
            shutil.copyfile(Path('/usr/share/common-licenses') / license_name, notices / license_name)
    (assets / 'bindings.tsv').write_text('\n'.join(bindings) + '\n')
    (assets / 'runtime-arch').write_text(DSH_ARCH)
    for path in libs.iterdir():
        path.chmod(0o755)


def main():
    (CACHE / 'archives').mkdir(parents=True, exist_ok=True)
    target = CACHE / ABI
    fp = digest(LOCK) + digest(Path(__file__)) + digest(ROOT / 'tools/guest_rename.c')
    stamp = target / 'complete'
    if stamp.exists() and stamp.read_text() == fp:
        return
    registry = json.loads(LOCK.read_text())
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        files = dict(pool.map(download, (registry[ABI] | registry['common']).items()))
    with tempfile.TemporaryDirectory(dir=CACHE) as tmp:
        prepared = Path(tmp) / 'abi'
        stage(prepared, files)
        (prepared / 'complete').write_text(fp)
        if target.exists():
            shutil.rmtree(target)
        prepared.rename(target)
    print(f'Prepared {ABI}', flush=True)


if __name__ == '__main__':
    CACHE.mkdir(exist_ok=True)
    with (CACHE / (ABI + '.lock')).open('w') as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        main()
