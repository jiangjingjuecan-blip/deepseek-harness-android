"""Stage verified upstream files. Only Android's installer places executable pages on device."""
import concurrent.futures
import hashlib
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
import brotli

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


def build_proot(archive, base, termux, libs):
    # Retain the installed separate loader. Its pokedata offset is compiled
    # into PRoot, so build and ship both from the same upstream sources.
    ndk = Path(os.environ.get('ANDROID_HOME', '/home/box/Android/sdk')) / 'ndk/27.3.13750724'
    llvm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    triple = 'aarch64' if DSH_ARCH == 'arm64' else 'x86_64'
    cc = llvm / f'{triple}-linux-android29-clang'
    if not cc.is_file():
        raise RuntimeError("Install Android NDK 27.3.13750724 in ANDROID_HOME/ndk")
    with zipfile.ZipFile(archive) as z:
        z.extractall(base)
    source = base / 'proot-5.1.107.102'
    subprocess.run(['patch', '--batch', '-p1', '-i', ROOT/'tools/proot-statx.patch'], cwd=source, check=True)
    env = dict(os.environ, CC=str(cc), STRIP=str(llvm/'llvm-strip'), OBJDUMP=str(llvm/'llvm-objdump'),
               OBJCOPY=str(llvm/'llvm-objcopy'), GIT='true', PROOT_WITH_LIBANDROID_SHMEM='true',
               PROOT_UNBUNDLE_LOADER='/run/apk-libs',
               CPPFLAGS=f'-I{termux}/include -DARG_MAX=131072 -DVERSION=\\"5.1.107.102\\"',
               LDFLAGS=f'-L{termux}/lib -Wl,-z,max-page-size=16384')
    subprocess.run(['make', '-C', source/'src', '-j4', 'proot'], env=env, check=True)
    for src, name in [('proot', 'libproot.so'), ('loader/loader', 'libproot_loader.so')]:
        subprocess.run([llvm/'llvm-strip', '-o', libs/name, source/'src'/src], check=True)
    subprocess.run(['patchelf', '--replace-needed', 'libtalloc.so.2', 'libtalloc.so', libs/'libproot.so'], check=True)


def stage_pkg_addons(libs, bindings):
    # The pinned pkg SEA has two length-prefixed assets. Its dlopen wrapper copies
    # the top-level package (the entire scope for a scoped package) under pkg/<digest>.
    # Bind every native sibling there, including libraries needed via $ORIGIN.
    exe = (libs / 'libdsh.so').read_bytes()

    def asset(name):
        pos = exe.rindex(name) + len(name)
        if struct.unpack_from('<Q', exe, pos - len(name) - 8)[0] != len(name):
            raise ValueError('Unexpected SEA asset header')
        size, = struct.unpack_from('<Q', exe, pos)
        return memoryview(exe)[pos + 8:pos + 8 + size]

    manifest = json.loads(asset(b'__pkg_manifest__').tobytes())
    archive = asset(b'__pkg_archive__')
    if manifest['compression'] != 2 or manifest['symlinks']:
        raise ValueError('Review native cache layout for this pkg version')
    modules = {}
    expected_system = f'@deepseek-ai/node-addon-system-linux-{DSH_ARCH}/bin/glibc/system.node'
    for path, (offset, length) in manifest['offsets'].items():
        if not re.search(r'(?:\.node|\.so(?:\.\d+)*)$', path):
            continue
        rel = path.removeprefix('/node/node_modules/')
        if rel == path or '..' in Path(rel).parts:
            raise ValueError('Unexpected native module path')
        data = brotli.decompress(archive[offset:offset + length])
        if not data.startswith(b'\x7fELF') or len(data) != manifest['stats'][path]['size']:
            raise ValueError('Invalid native asset')
        sha = hashlib.sha256(data).hexdigest()
        if rel == expected_system:
            name = 'libdsh_flock.so'
        elif rel.startswith('node-addon-require-builtin-'):
            name = 'libdsh_builtin.so'
            if digest(libs / name) != sha:
                raise ValueError('Embedded builtin differs from the pinned prebuild')
        else:
            name = 'libpkg_' + sha[:16] + '.so'
        (libs / name).write_bytes(data)
        modules[rel] = (name, sha)
    if expected_system not in modules:
        raise ValueError('Missing session lock addon')
    for addon, (_, sha) in modules.items():
        if addon.endswith('.node'):
            scope = addon.split('/')[0]
            for path, (name, _) in modules.items():
                if path.split('/')[0] == scope:
                    bindings.append(f'{name}\t/opt/pkg-cache/pkg/{sha}/{path}')

    # pkg otherwise copies and hashes the whole @deepseek-ai JS scope on the
    # first session lock. The lock addon has no package sidecars. Its exact ELF
    # already occupies newPath via the binding above. Keep pkg's hash, path
    # selection and dlopen, including its original behavior for other addons.
    # The worker bootstrap is a quoted copy. Preserve SEA offsets in both.
    skip_flock_copy = (b'if(moduleBaseName!=="system.node")cpRecursive(modulePkgFolder,'
                       b'path.join(tmpFolder,path.basename(modulePkgFolder)));')
    for newline in (b'\n', b'\\n'):
        original = (b'var destFolder = path.join(tmpFolder, path.basename(modulePkgFolder));'
                    + newline + b'            cpRecursive(modulePkgFolder, destFolder);')
        if exe.count(original) != 1 or len(skip_flock_copy) > len(original):
            raise ValueError('Review pkg native loader in the pinned DSH wheel')
        exe = exe.replace(original, skip_flock_copy.ljust(len(original)))
    (libs / 'libdsh.so').write_bytes(exe)


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
        build_proot(files['proot-source'], base, termux, libs)
        for src, name in [('lib/libtalloc.so.2','libtalloc.so'), ('lib/libandroid-shmem.so','libandroid-shmem.so')]:
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
        probe = base / 'guest-check'
        compiler = ('aarch64' if DSH_ARCH == 'arm64' else 'x86_64') + '-linux-gnu-gcc'
        # A static first tracee can check the guest interpreter before any guest
        # program asks PRoot to load it.
        subprocess.run([compiler, '-std=c11', '-Os', '-Wall', '-Wextra', '-Werror', '-static', '-s',
                        ROOT/'tools/guest_check.c', '-o', probe], check=True)
        guest(probe, '/bin/guest-check', 'libguest_check.so')
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
    stage_pkg_addons(libs, bindings)
    (assets / 'bindings.tsv').write_text('\n'.join(bindings) + '\n')
    (assets / 'runtime-arch').write_text(DSH_ARCH)
    for path in libs.iterdir():
        path.chmod(0o755)


def main():
    (CACHE / 'archives').mkdir(parents=True, exist_ok=True)
    target = CACHE / ABI
    fp = ''.join(digest(path) for path in (LOCK, Path(__file__), ROOT/'tools/guest_rename.c',
                                          ROOT/'tools/guest_check.c', ROOT/'tools/proot-statx.patch'))
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
