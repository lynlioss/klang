#!/usr/bin/env python3
"""Run the actual release APK's lyric parser in Android Runtime, without installing it."""
import argparse
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--mapping', type=Path, default=Path('app/build/outputs/mapping/release/mapping.txt'))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-arm64'))
local_sdk = root / '.android-tools' / 'sdk'
sdk = Path(os.environ.get('ANDROID_HOME', str(local_sdk if local_sdk.is_dir() else Path('/opt/android-sdk'))))
linker = Path('/apex/com.android.runtime/bin/linker64')
art = Path('/apex/com.android.art/bin/dalvikvm64')
if not linker.is_file() or not art.is_file():
    raise SystemExit('Android Runtime is required for this release APK check.')

sections = {}
current = None
for line in args.mapping.read_text().splitlines():
    match = re.fullmatch(r'(\S+) -> (\S+):', line)
    if match:
        current = match.group(1)
        sections[current] = [match.group(2), []]
    elif current and line.startswith('    '):
        sections[current][1].append(line.strip())

def renamed_field(owner, name):
    for entry in sections[owner][1]:
        match = re.fullmatch(r'\S+ ' + re.escape(name) + r' -> (\S+)', entry)
        if match:
            return match.group(1)
    raise ValueError(f'Missing release field mapping: {owner}.{name}')

parser_class = 'app.lynli.klang.lyrics.LyricsParser'
document_class = 'app.lynli.klang.lyrics.LyricsDocument'
line_class = 'app.lynli.klang.lyrics.LyricLine'
word_class = 'app.lynli.klang.lyrics.LyricWord'
method = next(entry.rsplit(' -> ', 1)[1] for entry in sections[parser_class][1]
              if ' lyricsfile(java.lang.String,long)' in entry)
field_names = [renamed_field(document_class, 'lines'), renamed_field(line_class, 'words'),
               renamed_field(word_class, 'text'), renamed_field(word_class, 'startMs'), renamed_field(word_class, 'endMs')]

# Package names matter on Android: getPackage() returns null for the default package.
for original, (renamed, _) in sections.items():
    if original.startswith('org.yaml.snakeyaml.') and not renamed.startswith('R8$$REMOVED$$'):
        assert '.' in renamed, f'SnakeYAML was moved to the default package: {original} -> {renamed}'

source = r'''
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class KlangReleaseLyricsCheck {
    static Object field(Object owner, String name) throws Exception {
        Field result = owner.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result.get(owner);
    }
    static Object parse(Class<?> parser, String name, String yaml) throws Exception {
        for (Method method : parser.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals(name) || types.length != 2) continue;
            Object[] arguments;
            if (types[0] == long.class && types[1] == String.class) arguments = new Object[] { 203000L, yaml };
            else if (types[0] == String.class && types[1] == long.class) arguments = new Object[] { yaml, 203000L };
            else continue;
            method.setAccessible(true);
            Object owner = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                for (Field candidate : parser.getDeclaredFields()) {
                    if (Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == parser) {
                        candidate.setAccessible(true);
                        owner = candidate.get(null);
                    }
                }
            }
            try { return method.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw new RuntimeException("Release YAML parsing failed", failure.getCause()); }
        }
        throw new AssertionError("Release lyricsfile method was not found");
    }
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.runtime.name", "").startsWith("Android Runtime"))
            throw new AssertionError("This check must use Android Runtime");
        Class<?> parser = Class.forName(args[0]);
        String yaml = new String(Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8);
        Object doc = parse(parser, args[1], yaml);
        List<?> lines = (List<?>) field(doc, args[3]);
        int words = 0;
        long previousStart = -1;
        for (Object line : lines) {
            for (Object word : (List<?>) field(line, args[4])) {
                long start = ((Number) field(word, args[6])).longValue();
                long end = ((Number) field(word, args[7])).longValue();
                if (start < 0 || end <= start) throw new AssertionError("Invalid provider word boundaries");
                words++;
                previousStart = start;
            }
        }
        int expectedLines = Integer.parseInt(args[8]);
        int expectedWords = Integer.parseInt(args[9]);
        if (lines.size() != expectedLines || words != expectedWords)
            throw new AssertionError("Expected " + expectedLines + " lines / " + expectedWords + " words; got " + lines.size() + " / " + words);
        if (expectedWords == 6) {
            List<?> first = (List<?>) field(lines.get(0), args[4]);
            List<?> second = (List<?>) field(lines.get(1), args[4]);
            Object last = second.get(second.size() - 1);
            if (!field(first.get(0), args[5]).equals("Yeah") || previousStart != 7932L ||
                ((Number) field(last, args[7])).longValue() != 8227L)
                throw new AssertionError("Homecoming text or word timestamps changed");
        }
        System.out.println("Android Runtime release parser: " + lines.size() + " lines, " + words + " supplied word timestamps");
    }
}
'''

def run(command):
    result = subprocess.run([str(item) for item in command], text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()

fixture = json.loads((root / 'app/src/test/resources/lyrics/homecoming-word-timing.json').read_text())
with tempfile.TemporaryDirectory(prefix='klang-release-lyrics-') as name:
    directory = Path(name)
    classes = directory / 'classes'
    dex = directory / 'dex'
    classes.mkdir()
    dex.mkdir()
    java_source = directory / 'KlangReleaseLyricsCheck.java'
    java_source.write_text(source)
    run([java_home / 'bin/javac', '--release', '8', '-d', classes, java_source])
    run([java_home / 'bin/java', '-cp', sdk / 'build-tools/37.0.0/lib/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', '36', '--output', dex, classes / 'KlangReleaseLyricsCheck.class'])
    probe = directory / 'probe.jar'
    with zipfile.ZipFile(probe, 'w') as jar:
        jar.write(dex / 'classes.dex', 'classes.dex')
    # DEX is taken from the delivered APK, not from debug classes or a JVM library.
    apk = args.apk.resolve()
    cases = [('homecoming', fixture['lyricsfile'], 2, 6),
             ('line-only', "version: '1.0'\nlines:\n- text: Hold this note\n  start_ms: 1000\n  end_ms: 9000\n", 1, 0)]
    for label, yaml, expected_lines, expected_words in cases:
        data = directory / f'{label}.yaml'
        data.write_text(yaml)
        print(run([linker, art, '-Xmx256m', '-cp', f'{probe}:{apk}', 'KlangReleaseLyricsCheck',
                   sections[parser_class][0], method, data, *field_names, expected_lines, expected_words]))
print('Release lyric timing verified in Android Runtime.')
