"""P6-05 / P8-08 / P6-11 (MASTG-TEST-0203, logs): no Android or common app log call may write a
credential to logcat. The scan reads every log call in the Android and common app sources
(android.util.Log, Kermit loggers, println, System.out/err, printStackTrace), with its whole
argument list and trailing message lambda, and flags a call when
  - code inside it (an argument, a string template, a concatenation) names a credential-like
    identifier: token, password, secret, cookie, authorization, api key, bearer, credential, jwt,
    session id, private key; or
  - the literal text right before a template reads like a credential label ("token=$x"); or
  - a literal holds a URL with userinfo (scheme://user:pass@host); or
  - it names a URL-like value (url, uri, href, link, endpoint, header, query): those can carry
    userinfo or a token in the query, so every one of them needs a review.
Each flagged call must match an entry of REVIEWED, which says why that site is safe. A new or
changed log call that touches such data fails until someone reviews it. The review behind each
entry is in docs/plus/build15/security-logs-storage.md."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE_ROOTS = (
    "composeApp/src/commonMain",
    "composeApp/src/fullCommonMain",
    "composeApp/src/androidMain",
    "composeApp/src/androidFull",
    "androidApp/src/main",
)

CALL = re.compile(
    r"(?<![\w.])(?<!fun )(?:"
    r"(?:android\.util\.)?Log\.(?:v|d|i|w|e|wtf)"
    r"|(?:co\.touchlab\.kermit\.)?(?:log|Logger|[a-z]\w*Log)\.(?:v|d|i|w|e|a)"
    r"|System\.(?:out|err)\.print(?:ln|f)?"
    r"|println|print"
    # The app's own log helpers (downloadLog, logPhase, logSnapshot, ...): what their callers pass.
    r"|log[A-Z]\w*|[a-z]\w*Log"
    r")\s*(?=[({])"
    r"|\.printStackTrace\s*\("
)
SECRET = re.compile(
    r"(?i)token|passw|secret|cookie|authoriz|api_?key|bearer|credential|jwt|session_?id|private_?key"
)
URLISH = re.compile(r"(?i)url|uri\b|uri[A-Z_]|href|link|endpoint|header|query")
LABEL = re.compile(
    r"(?i)(token|passw\w*|secret|cookie|authorization|api_?key|bearer|credential|jwt)\s*[=:]\s*$"
)
USERINFO = re.compile(r"[a-z][a-z0-9+.-]*://[^\s/@:\"]+:[^\s/@\"]*@")


class _Lexer:
    """Just enough of Kotlin to find the end of a call: strings (plain, raw, templates), chars and
    comments. Collects the code part (everything outside literal text, template code included) and
    the literal text segments, each paired with the template that follows it."""

    def __init__(self, text, start):
        self.t = text
        self.i = start
        self.code = []
        self.literals = []  # (text before a template or the end of the string, followed_by_template)

    def group(self, open_ch, close_ch):
        assert self.t[self.i] == open_ch
        depth = 0
        while self.i < len(self.t):
            c = self.t[self.i]
            if self.t.startswith("//", self.i):
                self.i = self.t.find("\n", self.i)
                if self.i < 0:
                    self.i = len(self.t)
                continue
            if self.t.startswith("/*", self.i):
                end = self.t.find("*/", self.i + 2)
                self.i = len(self.t) if end < 0 else end + 2
                continue
            if self.t.startswith('"""', self.i):
                self.string(raw=True)
                continue
            if c == '"':
                self.string(raw=False)
                continue
            if c == "'":
                end = self.i + 1
                while end < len(self.t) and self.t[end] != "'":
                    end += 2 if self.t[end] == "\\" else 1
                self.code.append(" ")
                self.i = end + 1
                continue
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            self.code.append(c)
            self.i += 1
            if depth == 0:
                return
        raise ValueError("unbalanced call")

    def string(self, raw):
        quote = '"""' if raw else '"'
        self.i += len(quote)
        buf = []
        while self.i < len(self.t):
            if self.t.startswith(quote, self.i):
                self.i += len(quote)
                self.literals.append(("".join(buf), False))
                self.code.append('""')
                return
            c = self.t[self.i]
            if not raw and c == "\\":
                buf.append(self.t[self.i:self.i + 2])
                self.i += 2
                continue
            if c == "$" and self.i + 1 < len(self.t):
                nxt = self.t[self.i + 1]
                if nxt == "{":
                    self.literals.append(("".join(buf), True))
                    buf = []
                    self.i += 1
                    self.code.append(" (")
                    self.group("{", "}")
                    self.code.append(") ")
                    continue
                m = re.match(r"[A-Za-z_]\w*", self.t[self.i + 1:])
                if m:
                    self.literals.append(("".join(buf), True))
                    buf = []
                    self.code.append(" " + m.group(0) + " ")
                    self.i += 1 + len(m.group(0))
                    continue
            buf.append(c)
            self.i += 1
        raise ValueError("unterminated string")


def _strip_comments_and_strings_positions(text):
    """Offsets that are inside a comment or a string, so a log-looking word there is ignored."""
    dead = bytearray(len(text))
    i = 0
    while i < len(text):
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = len(text) if end < 0 else end
            dead[i:end] = b"\1" * (end - i)
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = len(text) if end < 0 else end + 2
            dead[i:end] = b"\1" * (end - i)
            i = end
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = len(text) if end < 0 else end + 3
            dead[i:end] = b"\1" * (end - i)
            i = end
        elif text[i] == '"':
            j = i + 1
            depth = 0
            while j < len(text):
                if text[j] == "\\":
                    j += 2
                    continue
                if text.startswith("${", j):
                    depth += 1
                    j += 2
                    continue
                if depth and text[j] == "}":
                    depth -= 1
                elif not depth and text[j] in '"\n':
                    break
                j += 1
            dead[i:j + 1] = b"\1" * (j + 1 - i)
            i = j + 1
        else:
            i += 1
    return dead


def scan_text(text, path="<snippet>"):
    """Every log call in a Kotlin source: (path, line, normalised call text, reasons)."""
    dead = _strip_comments_and_strings_positions(text)
    found = []
    for m in CALL.finditer(text):
        if dead[m.start()]:
            continue
        lex = _Lexer(text, m.end() if text[m.end() - 1] != "(" else m.end() - 1)
        while lex.i < len(text) and text[lex.i].isspace():
            lex.i += 1
        try:
            if text[lex.i] == "(":
                lex.group("(", ")")
                j = lex.i
                while j < len(text) and text[j] in " \t":
                    j += 1
                if j < len(text) and text[j] == "{":
                    lex.i = j
                    lex.group("{", "}")
            elif text[lex.i] == "{":
                lex.group("{", "}")
        except (ValueError, IndexError):
            line = text.count("\n", 0, m.start()) + 1
            found.append((path, line, text[m.start():text.find("\n", m.start())].strip(), frozenset({"unparsed"})))
            continue
        call = " ".join(text[m.start():lex.i].split())
        code = "".join(lex.code)
        idents = set(re.findall(r"[A-Za-z_]\w*", code))
        reasons = set()
        for ident in idents:
            if SECRET.search(ident):
                reasons.add("secret:" + ident)
            elif URLISH.search(ident):
                reasons.add("url:" + ident)
        for literal, followed in lex.literals:
            if followed and LABEL.search(literal):
                reasons.add("label:" + LABEL.search(literal).group(1).lower())
            if USERINFO.search(literal):
                reasons.add("userinfo-literal")
        line = text.count("\n", 0, m.start()) + 1
        found.append((path, line, call, frozenset(reasons)))
    return found


def scan_repository():
    calls = []
    for root in SOURCE_ROOTS:
        for file in sorted((ROOT / root).rglob("*.kt")):
            calls += scan_text(file.read_text(encoding="utf-8"), str(file.relative_to(ROOT)))
    return calls


# Reviewed log sites that touch URL-like or credential-like names, keyed by file and a substring
# of the normalised call text (whitespace collapsed). Each was read in full, with what feeds it.
#   verdict "safe": the call cannot write a credential; "why" says what it writes instead.
#   verdict "known-leak": the call can write a credential; it is Nuvio's own code, which Nuvio Plus
#     does not change, and "finding" names the finding in security-logs-storage.md. Plus code may
#     never be accepted this way (test_known_leaks_are_nuvio_findings).
# origin: "nuvio" = upstream Nuvio code (git blame: upstream authors); "plus" = added by Nuvio Plus.
C = "composeApp/src/commonMain/kotlin/com/nuvio/app/features/"
FC = "composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/"
A = "composeApp/src/androidMain/kotlin/com/nuvio/app/features/"
REVIEWED = [
    {"file": C + "addons/AddonRepository.kt", "call": "local addon count: ${storedUrls.size}",
     "origin": "nuvio", "verdict": "safe", "why": "logs the number of stored addon URLs, not the URLs"},
    {"file": C + "addons/AddonRepository.kt", "call": "applied ${urls.size} addons",
     "origin": "nuvio", "verdict": "safe", "why": "logs a count only"},
    {"file": C + "collection/TmdbCollectionSourceResolver.kt", "call": "TMDB source request failed for $endpoint",
     "origin": "nuvio", "verdict": "safe",
     "why": "endpoint is the TMDB path; buildTmdbUrl adds api_key to a separate url that is not logged; the "
            "throwable comes from executeTextRequest (HTTP code only) or OkHttp IOExceptions (host, no query)"},
    {"file": C + "tmdb/TmdbMetadataService.kt", "call": "TMDB request failed for $endpoint: ${error.message}",
     "origin": "nuvio", "verdict": "safe", "why": "as TmdbCollectionSourceResolver: path only, the key stays in url"},
    {"file": C + "tmdb/TmdbService.kt", "call": "TMDB request failed for $endpoint: ${error.message}",
     "origin": "nuvio", "verdict": "safe", "why": "as TmdbCollectionSourceResolver: path only, the key stays in url"},
    {"file": C + "library/LibraryRepository.kt", "call": ".token.profileId}",
     "origin": "nuvio", "verdict": "safe",
     "why": "six calls; token is LibraryProfileToken(profileId: Int, generation: Long), a local revision marker"},
    {"file": C + "library/LibraryRepository.kt", "call": "scheduled=${snapshot.token}",
     "origin": "nuvio", "verdict": "safe", "why": "LibraryProfileToken: profile number and generation counter"},
    {"file": C + "trakt/TraktAuthRepository.kt", "call": "Failed to revoke Trakt token: ${error.message}",
     "origin": "nuvio", "verdict": "safe",
     "why": "the token goes in the POST body, which is not logged; the message is the HTTP code or an IOException"},
    {"file": C + "trakt/TraktCommentsRepository.kt", "call": "Failed to load comments from $url",
     "origin": "nuvio", "verdict": "safe", "why": "public api.trakt.tv comments path with page/limit; headers not logged"},
    {"file": C + "trakt/TraktPublicListSourceResolver.kt", "call": "Failed to parse Trakt response for $endpoint",
     "origin": "nuvio", "verdict": "safe", "why": "Trakt API path; the client id travels in a header that is not logged"},
    {"file": C + "trakt/TraktScrobbleRepository.kt", "call": "append(requestHeaders.redactedForLogs().formatForLog())",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-04",
     "why": "trakt-api-key is redacted, but Authorization keeps the first 6 and last 4 characters of the bearer token"},
    {"file": C + "trakt/TraktScrobbleRepository.kt", "call": "append(response.headers.formatForLog())",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-04",
     "why": "response headers unredacted (a set-cookie would be written); url is the fixed scrobble endpoint"},
    {"file": FC + "trailer/InAppYouTubeExtractor.kt", "call": "Trailer extractor failed for $youtubeUrl",
     "origin": "nuvio", "verdict": "safe", "why": "public YouTube watch URL of a trailer"},
    {"file": A + "p2p/P2pStreamingEngine.android.kt", "call": "url=${diagnosticLoopbackUrl(stream.url)}",
     "origin": "nuvio", "verdict": "safe", "why": "diagnosticLoopbackUrl keeps scheme, host and port only"},
    {"file": A + "p2p/P2pStreamingEngine.android.kt", "call": "Nuvio Engine stream ready: ${stream.url}",
     "origin": "nuvio", "verdict": "safe",
     "why": "URL of the on-device torrent engine's local HTTP server (the same value diagnosticLoopbackUrl "
            "formats); no remote host or credential"},
    {"file": A + "player/HlsParameterSetInitDataSource.kt", "call": "Added an SPS/PPS init segment to $baseUri",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-02",
     "why": "the full HLS playlist URL, query (signed CDN / debrid tokens) and userinfo included"},
    {"file": A + "player/HlsParameterSetInitDataSource.kt", "call": "Could not read the start of $uri",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-02", "why": "the full HLS segment URL"},
    {"file": A + "player/PlayerEngine.android.kt", "call": "\"prepare begin attempt=",
     "origin": "nuvio", "verdict": "safe",
     "why": "diagnosticPlaybackSource keeps scheme, host, port; the audio URL is logged as a boolean"},
    {"file": A + "player/PlayerEngine.android.kt", "call": "\"mpv_prepare_begin attempt=",
     "origin": "nuvio", "verdict": "safe", "why": "as prepare begin: host only and a boolean"},
    {"file": A + "player/PlayerEngine.android.kt", "call": "setSubtitleUri: url=${url.redactedForLogs()}",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-03",
     "why": "redactedForLogs drops query and fragment but keeps userinfo and path"},
    {"file": A + "player/PlayerEngine.android.kt", "call": "buffer-preserving sidecar for url=${url.redactedForLogs()}",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-03", "why": "as above"},
    {"file": A + "player/PlayerEngine.android.kt", "call": "subtitleConfig built, uri=${url.redactedForLogs()}",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-03", "why": "as above"},
    {"file": A + "player/SubtitleFileCache.kt", "call": "downloading subtitle: ${input.url.redactedForLogs()}",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-03", "why": "as above"},
    {"file": A + "player/SubtitleFileCache.kt", "call": "Failed to download subtitle file: ${input.url.redactedForLogs()}",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-03", "why": "as above"},
    {"file": A + "player/PlayerSidecarSubtitles.kt", "call": "Sidecar subtitle parse empty for url=$url",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-02", "why": "the subtitle URL unredacted, query included"},
    {"file": A + "player/PlayerSidecarSubtitles.kt", "call": "Sidecar subtitle ready url=$url",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-02", "why": "as above"},
    {"file": A + "player/PlayerSidecarSubtitles.kt", "call": "Sidecar subtitle failed url=$url",
     "origin": "nuvio", "verdict": "known-leak", "finding": "W5-LOG-02", "why": "as above"},
]


def unreviewed(calls, reviewed=REVIEWED):
    left = []
    for path, line, call, reasons in calls:
        if not reasons:
            continue
        if any(path == r["file"] and r["call"] in call for r in reviewed):
            continue
        left.append((path, line, call, sorted(reasons)))
    return left


class Scanner(unittest.TestCase):
    """The scan itself, on snippets, so a broken scanner cannot pass the repository silently."""

    def flagged(self, snippet):
        return [c for c in scan_text(snippet) if c[3]]

    def test_a_logged_token_is_caught(self):
        hits = self.flagged('fun f(accessToken: String) { Log.d(TAG, "refresh with $accessToken") }')
        self.assertEqual(1, len(hits))
        self.assertIn("secret:accessToken", hits[0][3])

    def test_a_kermit_lambda_with_a_password_is_caught(self):
        hits = self.flagged('log.i { "login ${account.password} ok" }')
        self.assertIn("secret:password", hits[0][3])

    def test_a_concatenated_cookie_header_is_caught(self):
        hits = self.flagged('Log.w("Net", "sent " + request.headers["Cookie"] + " " + cookieJar)')
        self.assertTrue(any(r.startswith("secret:cookie") for r in hits[0][3]), hits)

    def test_a_label_before_a_template_is_caught(self):
        hits = self.flagged('println("auth: token=$value")')
        self.assertIn("label:token", hits[0][3])

    def test_a_literal_url_with_userinfo_is_caught(self):
        hits = self.flagged('Log.i(TAG, "playing https://bob:hunter2@example.com/a.mkv")')
        self.assertIn("userinfo-literal", hits[0][3])

    def test_a_stream_url_needs_a_review(self):
        hits = self.flagged('log.d { "opening ${stream.url}" }')
        self.assertIn("url:url", hits[0][3])

    def test_an_exception_and_a_plain_message_are_not_flagged(self):
        snippet = 'log.e(error) { "Failed to load the token list" }\nLog.w(TAG, "retry ${attempt} of 3", e)'
        calls = scan_text(snippet)
        self.assertEqual(2, len(calls))
        self.assertFalse(any(c[3] for c in calls), calls)

    def test_log_words_in_comments_and_strings_are_not_calls(self):
        snippet = '// Log.d(TAG, token)\nval s = "println(password)"\n/* log.i { secret } */'
        self.assertEqual([], scan_text(snippet))

    def test_a_multiline_call_is_read_to_its_end(self):
        snippet = 'Log.d(\n    TAG,\n    "a=${a} " +\n        "b=${session.refreshToken}",\n)\nval x = token'
        hits = self.flagged(snippet)
        self.assertEqual(1, len(hits))
        self.assertIn("secret:refreshToken", hits[0][3])
        self.assertEqual(1, hits[0][1])

    def test_a_reviewed_site_passes_and_a_changed_one_fails(self):
        reviewed = [{"file": "<snippet>", "call": 'Log.d(TAG, "host ${uri.host}")', "why": "host only"}]
        self.assertEqual([], unreviewed(scan_text('Log.d(TAG, "host ${uri.host}")'), reviewed))
        changed = unreviewed(scan_text('Log.d(TAG, "host ${uri.host} ${uri}")'), reviewed)
        self.assertEqual(1, len(changed))

    def test_a_synthetic_leak_in_the_repository_scan_fails(self):
        """The repository check, fed an extra leaking file, reports exactly that file."""
        leak = 'object A { fun go(s: Session) { Log.i("A", "bearer ${s.accessToken}") } }'
        calls = scan_repository() + scan_text(leak, "composeApp/src/androidMain/kotlin/Leak.kt")
        left = unreviewed(calls)
        self.assertEqual(["composeApp/src/androidMain/kotlin/Leak.kt"], [c[0] for c in left])


class Repository(unittest.TestCase):
    def test_the_scan_sees_the_app_logs(self):
        calls = scan_repository()
        self.assertGreater(len(calls), 300, "the scanner found too few log calls; is it still reading the sources?")

    def test_sensitive_addon_url_log_paths_stay_removed(self):
        """Avoid URL aliases and raw response snippets escaping the lexical credential scan."""
        forbidden = {
            "commonMain/kotlin/com/nuvio/app/features/addons/AddonRepository.kt": [
                "rawUrl=$rawUrl", "removeAddon() — $manifestUrl", "server[$i]: $u",
            ],
            "commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsRepository.kt": [
                "Fetching meta from: $url", "manifest=${manifest.transportUrl}",
                "payload.take(500)",
            ],
            "commonMain/kotlin/com/nuvio/app/features/streams/StreamSearchSessions.kt": [
                "Fetching streams from: $url",
            ],
            "commonMain/kotlin/com/nuvio/app/features/search/SearchRepository.kt": [
                "url=$requestUrl",
            ],
            "fullCommonMain/kotlin/com/nuvio/app/features/plugins/runtime/network/FetchBridge.kt": [
                "Fetch bridge error for $method $url",
            ],
        }
        for relative, patterns in forbidden.items():
            source = (ROOT / "composeApp/src" / relative).read_text(encoding="utf-8")
            for pattern in patterns:
                with self.subTest(file=relative, pattern=pattern):
                    self.assertNotIn(pattern, source)

    def test_no_unreviewed_log_call_touches_a_credential_or_url(self):
        left = unreviewed(scan_repository())
        self.assertEqual([], left, "log calls that need a review (add a REVIEWED entry only after reading the "
                                   "call and proving it cannot write a credential):\n" +
                         "\n".join(f"{p}:{n}: {c} {r}" for p, n, c, r in left))

    def test_every_reviewed_entry_still_matches_a_call(self):
        calls = scan_repository()
        for r in REVIEWED:
            self.assertTrue(r["why"].strip())
            self.assertIn(r["origin"], ("nuvio", "plus"))
            self.assertIn(r["verdict"], ("safe", "known-leak"))
            self.assertTrue(any(p == r["file"] and r["call"] in c and reasons for p, _, c, reasons in calls),
                            f"stale REVIEWED entry: {r['file']}: {r['call']}")

    def test_known_leaks_are_nuvio_findings_in_the_report(self):
        """A leak may only be carried as a reported finding in upstream code, never in Plus code."""
        report = (ROOT / "docs/plus/build15/security-logs-storage.md").read_text(encoding="utf-8")
        for r in REVIEWED:
            if r["verdict"] != "known-leak":
                continue
            self.assertEqual("nuvio", r["origin"], f"Plus code may not keep a leak: {r['file']}: {r['call']}")
            self.assertRegex(r["finding"], r"^W5-[A-Z]+-\d{2}$")
            self.assertIn(f"### {r['finding']}", report, f"{r['finding']} is not described in the report")

    def test_every_scanned_call_was_parsed(self):
        self.assertEqual([], [c for c in scan_repository() if "unparsed" in c[3]])


if __name__ == "__main__":
    unittest.main()
