import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readdir, rm, writeFile } from 'node:fs/promises';
import { delimiter, join } from 'node:path';
import { homedir, tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const cache = join(process.env.GRADLE_USER_HOME ?? join(homedir(), '.gradle'),
  'caches/modules-2/files-2.1/com.google.code.gson/gson');
async function findGson() {
  const versions = (await readdir(cache)).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
  for (const version of versions) {
    if (version.localeCompare('2.10', undefined, {numeric: true}) < 0) continue;
    for (const hash of await readdir(join(cache, version))) {
      const directory = join(cache, version, hash);
      const name = (await readdir(directory)).find(file => file === `gson-${version}.jar`);
      if (name) return join(directory, name);
    }
  }
  throw new Error('Run the Gradle build first, or set GSON_JAR to Gson 2.10 or newer.');
}
const gson = process.env.GSON_JAR ?? await findGson();
const root = fileURLToPath(new URL('../', import.meta.url));
const bin = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin') : '/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin';
const temp = await mkdtemp(join(tmpdir(), 'diorama-render-tasks-'));
try {
  const log = join(temp, 'Diorama.java');
  await writeFile(log, 'package dev.diorama; public class Diorama { public static final Log LOGGER = new Log(); public static class Log { public void warn(String text, Object... args) { throw new AssertionError(text); } } }');
  const source = join(root, 'src/main/java/dev/diorama/web');
  const classes = join(temp, 'classes');
  await mkdir(classes);
  execFileSync(join(bin, 'javac'), ['-cp', gson, '-d', classes, log,
    join(source, 'WebRenderTasks.java'), join(source, 'WebChunkState.java'),
    join(root, 'tests/web/WebRenderTasksTest.java')], { stdio: 'inherit' });
  execFileSync(join(bin, 'java'), ['-cp', classes + delimiter + gson,
    'dev.diorama.web.WebRenderTasksTest', temp], { stdio: 'inherit' });
} finally {
  await rm(temp, { recursive: true, force: true });
}
