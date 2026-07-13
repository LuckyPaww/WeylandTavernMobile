# fetch_runtime.py — build-time tool, runs on the dev PC (never on the phone).
#
# Downloads the Termux nodejs-lts package and its dependency libraries,
# extracts the Node executable + shared libs, and installs them into
# app/src/main/jniLibs/arm64-v8a/ so the Android packager ships them as
# "native libraries". The node executable is renamed libnode_bin.so so it
# survives APK packaging; at runtime NodeService executes it from
# applicationInfo.nativeLibraryDir with ProcessBuilder.
#
# Also extracts the CA certificate bundle into app/src/main/assets/cacert.pem
# (Termux's Node prefers an OpenSSL CA file at a Termux-only path; we point
# SSL_CERT_FILE at our copy instead).
#
# Usage:  python tools/fetch_runtime.py
# Re-run any time to refresh to the latest Termux package versions.

import hashlib
import io
import lzma
import os
import re
import sys
import tarfile
import urllib.request

MIRROR = "https://packages.termux.dev/apt/termux-main"
ARCH = "aarch64"

# nodejs-lts plus its full dependency closure (from the repo's Packages index)
PACKAGES = [
    "nodejs-lts",
    "libc++",
    "openssl",
    "c-ares",
    "libicu",
    "libsqlite",
    "zlib",
    "ca-certificates",
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNILIBS = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "tools", ".deb-cache")


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "WeyTavBuild"})
    with urllib.request.urlopen(req) as r:
        return r.read()


def load_index() -> dict:
    """Parse the repo Packages index into {name: filename}."""
    print("Fetching package index...")
    text = fetch(f"{MIRROR}/dists/stable/main/binary-{ARCH}/Packages").decode()
    index = {}
    for block in text.split("\n\n"):
        name = ver = fn = sha = None
        for line in block.splitlines():
            if line.startswith("Package: "):
                name = line[9:].strip()
            elif line.startswith("Version: "):
                ver = line[9:].strip()
            elif line.startswith("Filename: "):
                fn = line[10:].strip()
            elif line.startswith("SHA256: "):
                sha = line[8:].strip()
        if name and fn:
            index[name] = (ver, fn, sha)
    return index


def ar_members(data: bytes):
    """Minimal unix ar(1) reader: yields (name, bytes)."""
    assert data[:8] == b"!<arch>\n", "not an ar archive"
    pos = 8
    while pos < len(data):
        hdr = data[pos : pos + 60]
        if len(hdr) < 60:
            break
        name = hdr[0:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        body = data[pos + 60 : pos + 60 + size]
        yield name, body
        pos += 60 + size
        if pos % 2:  # members are 2-byte aligned
            pos += 1


def open_data_tar(deb: bytes) -> tarfile.TarFile:
    for name, body in ar_members(deb):
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                raw = lzma.decompress(body)
            elif name.endswith(".zst"):
                import zstandard  # pip install zstandard — only needed for .zst debs

                raw = zstandard.ZstdDecompressor().decompress(
                    body, max_output_size=2**31
                )
            elif name.endswith(".gz"):
                import gzip

                raw = gzip.decompress(body)
            else:
                raw = body
            return tarfile.open(fileobj=io.BytesIO(raw))
    raise RuntimeError("no data.tar member found in deb")


# Libraries Node never touches — shipped in the debs but dead weight for us.
PRUNE = ["libicutest.so", "libicutu.so", "libicuio.so", "libsqlite3.53.3.so"]


def strip_version(name: str) -> str:
    """libfoo.so.3 / libfoo.so.78.3 -> libfoo.so"""
    return re.sub(r"\.so(\.\d+)*$", ".so", name)


def prune_and_patch():
    """Termux binaries request versioned sonames (libz.so.1, libicuuc.so.78),
    but Android only installs jniLibs named exactly lib*.so. Rewrite every
    DT_NEEDED and DT_SONAME to the unversioned name so the bionic linker
    resolves against the files we actually ship."""
    import lief

    for junk in PRUNE:
        p = os.path.join(JNILIBS, junk)
        if os.path.exists(p):
            os.remove(p)
            print(f"pruned {junk}")

    print("\nPatching ELF soname/needed entries...")
    for fn in sorted(os.listdir(JNILIBS)):
        path = os.path.join(JNILIBS, fn)
        binary = lief.ELF.parse(path)
        if binary is None:
            sys.exit(f"ERROR: could not parse {fn} as ELF")
        changed = False
        for entry in binary.dynamic_entries:
            if entry.tag in (
                lief.ELF.DynamicEntry.TAG.NEEDED,
                lief.ELF.DynamicEntry.TAG.SONAME,
            ):
                new = strip_version(entry.name)
                if new != entry.name:
                    print(f"  {fn}: {entry.name} -> {new}")
                    entry.name = new
                    changed = True
        # Symbol version requirements (verneed) name the provider library
        # too; bionic refuses to link if these don't match a DT_NEEDED entry.
        for req in binary.symbols_version_requirement:
            new = strip_version(req.name)
            if new != req.name:
                print(f"  {fn}: verneed {req.name} -> {new}")
                req.name = new
                changed = True
        if changed:
            binary.write(path)


def main():
    os.makedirs(JNILIBS, exist_ok=True)
    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(CACHE, exist_ok=True)

    index = load_index()

    installed = []
    for pkg in PACKAGES:
        if pkg not in index:
            sys.exit(f"ERROR: package '{pkg}' not found in Termux repo index")
        ver, fn, sha = index[pkg]
        cached = os.path.join(CACHE, os.path.basename(fn))
        if os.path.exists(cached):
            print(f"{pkg} {ver}: using cached {os.path.basename(fn)}")
            deb = open(cached, "rb").read()
        else:
            print(f"{pkg} {ver}: downloading {os.path.basename(fn)}...")
            deb = fetch(f"{MIRROR}/{fn}")
            open(cached, "wb").write(deb)
        # Integrity check against the repo index — a truncated or tampered
        # download must never end up inside the APK.
        if sha:
            actual = hashlib.sha256(deb).hexdigest()
            if actual != sha:
                os.remove(cached)
                sys.exit(f"ERROR: SHA256 mismatch for {pkg} ({actual} != {sha}). "
                         "Cached file deleted — re-run to re-download.")

        tf = open_data_tar(deb)
        # Symlink map so we can materialise linked .so names as real files
        members = tf.getmembers()
        by_name = {m.name.lstrip("./"): m for m in members}

        def read_member(m, depth=0):
            if depth > 10:
                return None
            if m.issym():
                target = os.path.normpath(
                    os.path.join(os.path.dirname(m.name), m.linkname)
                ).replace("\\", "/").lstrip("./")
                tm = by_name.get(target)
                return read_member(tm, depth + 1) if tm else None
            f = tf.extractfile(m)
            return f.read() if f else None

        for m in members:
            base = os.path.basename(m.name)
            path = m.name.lstrip("./")

            # 1. The node executable itself
            if pkg == "nodejs-lts" and path.endswith("bin/node"):
                data = read_member(m)
                out = os.path.join(JNILIBS, "libnode_bin.so")
                open(out, "wb").write(data)
                print(f"    node executable -> libnode_bin.so ({len(data)//1048576} MB)")
                installed.append("libnode_bin.so")

            # 2. Shared libraries (skip static .a, skip subdir plugins)
            elif re.fullmatch(r"lib[\w+.-]*\.so(\.\d+)*", base) and "/lib/" in f"/{path}":
                # jniLibs only accepts exactly lib*.so — strip trailing version
                clean = re.sub(r"\.so(\.\d+)*$", ".so", base)
                data = read_member(m)
                if data is None:
                    continue
                out = os.path.join(JNILIBS, clean)
                # don't let a versioned symlink overwrite a real file with junk
                if not os.path.exists(out) or len(data) >= os.path.getsize(out):
                    open(out, "wb").write(data)
                    print(f"    {base} -> {clean} ({len(data)//1024} KB)")
                    if clean not in installed:
                        installed.append(clean)

            # 3. CA bundle
            elif pkg == "ca-certificates" and base == "cert.pem":
                data = read_member(m)
                out = os.path.join(ASSETS, "cacert.pem")
                open(out, "wb").write(data)
                print(f"    cert.pem -> assets/cacert.pem ({len(data)//1024} KB)")

    prune_and_patch()

    print("\nInstalled into jniLibs/arm64-v8a:")
    total = 0
    for f in sorted(os.listdir(JNILIBS)):
        sz = os.path.getsize(os.path.join(JNILIBS, f))
        total += sz
        print(f"  {f:32s} {sz/1048576:7.1f} MB")
    print(f"  {'TOTAL':32s} {total/1048576:7.1f} MB")


if __name__ == "__main__":
    main()
