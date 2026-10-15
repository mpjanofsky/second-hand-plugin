#!/usr/bin/env python3
"""Install, inspect or configure this plugin on the test kiosk through the KS Remote Admin API.

Credentials: env KS_ADMIN_URL and KS_ADMIN_AUTH, else `KS_ADMIN_URL:` / `KS_ADMIN_AUTH:` lines in
.local/LOCAL.md (gitignored). Never printed.

Usage:
  ks.py state                  this plugin's enabled flag, status line and settings (read only)
  ks.py screenshot <out.jpg>   the kiosk's current screen (read only; may miss native overlays, use adb then)
  ks.py install <zip>          install or update, enable, then show state
  ks.py set key=value...       change settings (numbers and true/false are converted); key=@env:VAR takes the
                               value from an environment variable, so a secret never appears on the command line

`set` reads the current values and writes the whole map once: configurePlugin replaces the map, so a partial
write would wipe the user's other values. Saved secrets read back as `__set__`, which KS keeps as is.
Output is filtered to this plugin and password-like fields are masked.
"""
import base64, json, os, pathlib, re, sys, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLUGIN_ID = json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())["id"]


def local(key):
    p = ROOT / ".local" / "LOCAL.md"
    m = re.search(r"^\W*%s:\s*(\S+)" % key, p.read_text(), re.M) if p.exists() else None
    return m.group(1) if m else None


def request(url, path, body=None, token=None):
    req = urllib.request.Request(url.rstrip("/") + path, json.dumps(body).encode() if body is not None else None,
                                 {"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    return req


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # A redirect would carry the Authorization header to wherever it points; fail instead.
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def call(url, path, body, token=None):
    req = request(url, path, body, token)
    for attempt in (1, 2):
        try:
            with OPENER.open(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            # The body is withheld: it can echo request content.
            sys.exit("%s failed: HTTP %d" % (path, e.code))
        except TimeoutError:
            # Seen right after configurePlugin while KS restarted the plugin. Reads are safe to repeat;
            # writes are not, so only the state read retries.
            if attempt == 2 or not path.endswith("getPluginState"):
                sys.exit("%s timed out" % path)


SENSITIVE = re.compile("pass|secret|token|key|auth|cred|pin|bearer|url|webhook|host", re.I)


def scrub(text):
    # Status and error text can echo addresses, credentials in URLs or tokens in query strings.
    text = re.sub(r"\b\d{1,3}(\.\d{1,3}){3}\b", "x.x.x.x", text)
    text = re.sub(r"(://)[^/@\s]+@", r"\1***@", text)
    return re.sub(r"([?&][^=&\s]+=)[^&\s]+", r"\1***", text)


def mask(o):
    if isinstance(o, dict):
        return {k: "***" if SENSITIVE.search(k) else mask(v) for k, v in o.items()}
    if isinstance(o, list):
        return [mask(v) for v in o]
    return scrub(o) if isinstance(o, str) else o


def find(o):
    if isinstance(o, dict):
        if o.get("id") == PLUGIN_ID:
            return o
        for v in o.values():
            hit = find(v)
            if hit:
                return hit
    elif isinstance(o, list):
        for v in o:
            hit = find(v)
            if hit:
                return hit
    return None


def summary(plugin):
    if not plugin:
        return "%s is not installed" % PLUGIN_ID
    keep = {k: plugin.get(k) for k in ("id", "version", "enabled", "running", "status", "statusError", "error", "values") if k in plugin}
    return json.dumps(mask(keep), indent=1, ensure_ascii=False)[:2000]


def value(text):
    if text.startswith("@env:"):
        v = os.environ.get(text[5:])
        if v is None:
            sys.exit("environment variable %s is not set" % text[5:])
        return v
    if text in ("true", "false"):
        return text == "true"
    try:
        n = float(text)
        return int(n) if n.is_integer() and "." not in text else n
    except ValueError:
        return text


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("install", "state", "set", "screenshot"):
        sys.exit(__doc__)
    url = os.environ.get("KS_ADMIN_URL") or local("KS_ADMIN_URL")
    pw = os.environ.get("KS_ADMIN_AUTH") or local("KS_ADMIN_AUTH")
    if not url or not pw:
        sys.exit("KS_ADMIN_URL / KS_ADMIN_AUTH missing (env or .local/LOCAL.md)")
    token = call(url, "/api/login", {"password": pw})["token"]
    cmd = sys.argv[1]
    if cmd == "screenshot":
        if len(sys.argv) < 3:
            sys.exit("usage: ks.py screenshot <out.jpg> (outside the repo, e.g. a scratch folder, or under private/)")
        out = pathlib.Path(sys.argv[2]).resolve()
        inside = out.is_relative_to(ROOT.resolve())
        if out.suffix.lower() not in (".jpg", ".jpeg") or (inside and not out.is_relative_to((ROOT / "private").resolve())):
            # Screenshots can show personal data; keep them out of the published tree.
            sys.exit("refused: write a .jpg outside the repo or under private/")
        with OPENER.open(request(url, "/api/screenshot", token=token), timeout=30) as r:
            out.write_bytes(r.read())
        print("wrote %s (%d bytes)" % (out, out.stat().st_size))
        return
    if cmd == "install":
        data = base64.b64encode(pathlib.Path(sys.argv[2]).read_bytes()).decode()
        call(url, "/api/commands/installPlugin", {"data": data, "trusted": True}, token)
        call(url, "/api/commands/enablePlugin", {"id": PLUGIN_ID}, token)
    elif cmd == "set":
        plugin = find(call(url, "/api/commands/getPluginState", {}, token))
        if not plugin:
            sys.exit(summary(plugin))
        values = dict(plugin.get("values") or {})
        for pair in sys.argv[2:]:
            k, _, v = pair.partition("=")
            values[k] = value(v)
        call(url, "/api/commands/configurePlugin", {"id": PLUGIN_ID, "values": values}, token)
    print(summary(find(call(url, "/api/commands/getPluginState", {}, token))))


main()
