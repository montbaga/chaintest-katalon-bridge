#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const PACKAGE_ROOT = path.join(__dirname, '..');
const PAYLOAD_ROOT = path.join(PACKAGE_ROOT, 'payload');
const VERSION = fs.readFileSync(path.join(PACKAGE_ROOT, 'VERSION'), 'utf8').trim();

const MANIFEST_DIRNAME = '.chaintest-bridge';
const CONFIG_REL = path.join('Include', 'config', 'chaintest', 'chaintest.properties');
const TAGS_REL = path.join('Include', 'config', 'chaintest', 'failure-tags.json');
const DRIVER_JARS = [
    'chaintest-core-1.0.12.jar',
    'freemarker-2.3.33.jar',
    'jackson-databind-2.18.0.jar',
    'jackson-core-2.18.0.jar',
    'jackson-annotations-2.18.0.jar',
    'snakeyaml-2.3.jar',
    'slf4j-api-2.0.16.jar',
].map((jar) => path.join('Drivers', jar));
const DIRS_TO_PRUNE_ON_UNINSTALL = ['Keywords/chaintest', 'Include/config/chaintest', 'Test Listeners', 'Drivers'];

function printUsage() {
    console.log(`chaintest-katalon-bridge v${VERSION}

Usage:
  chaintest-katalon-bridge install <projectPath> [--force]
  chaintest-katalon-bridge uninstall <projectPath> [--remove-config]

  install     Copies the bridge into a Katalon Studio project.
              --force also overwrites an existing chaintest.properties.
  uninstall   Removes a previously installed bridge, using the manifest
              install left behind. --remove-config also deletes
              chaintest.properties and failure-tags.json.
`);
}

function findProjectFile(projectPath) {
    return fs.readdirSync(projectPath, { withFileTypes: true })
        .find((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.prj'));
}

function listFilesRecursively(dir) {
    const found = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            found.push(...listFilesRecursively(fullPath));
        } else if (entry.isFile()) {
            found.push(fullPath);
        }
    }
    return found;
}

function resolveExistingProjectPath(rawPath) {
    if (!fs.existsSync(rawPath)) {
        throw new Error(`Project path does not exist: ${rawPath}`);
    }
    const resolved = fs.realpathSync(rawPath);
    const prjFile = findProjectFile(resolved);
    if (!prjFile) {
        throw new Error(
            `No *.prj file found directly under '${resolved}'. This does not look like a Katalon Studio project root - aborting to avoid writing into the wrong folder.`
        );
    }
    return { projectPath: resolved, prjName: prjFile.name };
}

function install(projectPath, force) {
    const { projectPath: resolvedPath, prjName } = resolveExistingProjectPath(projectPath);

    console.log(`Installing ChainTest-Katalon Bridge v${VERSION} into: ${resolvedPath}`);
    console.log(`  (detected Katalon project: ${prjName})`);

    const manifestDir = path.join(resolvedPath, MANIFEST_DIRNAME);
    fs.mkdirSync(manifestDir, { recursive: true });

    const installedRelativePaths = [];
    for (const sourceFile of listFilesRecursively(PAYLOAD_ROOT)) {
        const relativePath = path.relative(PAYLOAD_ROOT, sourceFile);
        const destinationFile = path.join(resolvedPath, relativePath);

        const isUserConfig = relativePath === CONFIG_REL;
        if (isUserConfig && fs.existsSync(destinationFile) && !force) {
            console.log(`  SKIP (already customized, use --force to overwrite): ${relativePath}`);
            // Still part of this install even though this run didn't touch
            // it - the manifest tracks "what this bridge is responsible
            // for", not "what this specific run copied". Without this,
            // uninstall --remove-config would be silently unable to find a
            // customized chaintest.properties to remove.
            installedRelativePaths.push(relativePath);
            continue;
        }

        fs.mkdirSync(path.dirname(destinationFile), { recursive: true });
        fs.copyFileSync(sourceFile, destinationFile);
        if (/\.(sh|command)$/.test(relativePath)) {
            fs.chmodSync(destinationFile, 0o755);
        }
        installedRelativePaths.push(relativePath);
        console.log(`  OK   ${relativePath}`);
    }

    fs.writeFileSync(
        path.join(manifestDir, 'manifest.txt'),
        [VERSION, ...installedRelativePaths].join('\n') + '\n',
        'utf8'
    );

    registerDriverJarsInClasspath(resolvedPath);

    console.log('');
    console.log('Install complete.');
    console.log('Next steps:');
    console.log('  1. Reopen (or refresh) the project in Katalon Studio.');
    console.log('  2. Run any Test Suite as usual - no changes needed to existing tests.');
    console.log("  3. Look for '[ChainTest]' lines in the console, and a chaintest-report/ folder afterwards.");
    console.log('  4. Open chaintest-report/<Name>_<timestamp>/Index.html directly - no server needed.');
    console.log('  5. Want real-time analytics/history too? See chainlp/ in this bridge\'s own repository, then set chaintest.generator.chainlp.enabled=true.');
}

// Best-effort, mirroring what Katalon's own IDE editor needs to resolve the
// bridge's classes without a manual project refresh: only touches
// .classpath if the project already has one, and only adds jars not
// already listed there.
function registerDriverJarsInClasspath(projectPath) {
    const classpathFile = path.join(projectPath, '.classpath');
    if (!fs.existsSync(classpathFile)) {
        return;
    }
    let xml = fs.readFileSync(classpathFile, 'utf8');
    if (!xml.includes('</classpath>')) {
        return;
    }
    let changed = false;
    for (const jarPath of DRIVER_JARS) {
        const asForwardSlash = jarPath.replace(/\\/g, '/');
        if (!xml.includes(`path="${asForwardSlash}"`)) {
            xml = xml.replace('</classpath>', `\t<classpathentry kind="lib" path="${asForwardSlash}"/>\n</classpath>`);
            changed = true;
        }
    }
    if (changed) {
        fs.writeFileSync(classpathFile, xml, 'utf8');
        console.log('  OK   .classpath (registered Drivers jars for the IDE editor)');
    }
}

function uninstall(projectPath, removeConfig) {
    if (!fs.existsSync(projectPath)) {
        throw new Error(`Project path does not exist: ${projectPath}`);
    }
    const resolvedPath = fs.realpathSync(projectPath);

    const manifestPath = path.join(resolvedPath, MANIFEST_DIRNAME, 'manifest.txt');
    if (!fs.existsSync(manifestPath)) {
        throw new Error(`No install manifest found at ${manifestPath} - this project doesn't look like it has the bridge installed.`);
    }

    const manifestLines = fs.readFileSync(manifestPath, 'utf8').split('\n').map((line) => line.trim()).filter(Boolean);
    const [installedVersion, ...installedRelativePaths] = manifestLines;
    console.log(`Uninstalling ChainTest-Katalon Bridge v${installedVersion} from: ${resolvedPath}`);

    const configPaths = new Set([CONFIG_REL, TAGS_REL]);

    for (const relativePath of installedRelativePaths) {
        if (configPaths.has(relativePath) && !removeConfig) {
            console.log(`  KEEP (config; pass --remove-config to delete): ${relativePath}`);
            continue;
        }
        const targetFile = path.join(resolvedPath, relativePath);
        if (fs.existsSync(targetFile)) {
            fs.unlinkSync(targetFile);
            console.log(`  REMOVED  ${relativePath}`);
        }
    }

    for (const dir of DIRS_TO_PRUNE_ON_UNINSTALL) {
        const fullDir = path.join(resolvedPath, dir);
        if (fs.existsSync(fullDir) && fs.readdirSync(fullDir).length === 0) {
            fs.rmdirSync(fullDir);
            console.log(`  REMOVED  ${dir}/ (now empty)`);
        }
    }

    fs.unlinkSync(manifestPath);
    const manifestDir = path.join(resolvedPath, MANIFEST_DIRNAME);
    if (fs.existsSync(manifestDir) && fs.readdirSync(manifestDir).length === 0) {
        fs.rmdirSync(manifestDir);
    }

    console.log('');
    console.log('Uninstall complete.');
    console.log('Note: chaintest-report/ and chaintest-results/ (generated output) were left in place - delete them manually if you want them gone too.');
}

function parseArgv(argv) {
    const [command, ...rest] = argv;
    const flags = new Set();
    const positional = [];
    for (const arg of rest) {
        if (arg.startsWith('--')) {
            flags.add(arg);
        } else {
            positional.push(arg);
        }
    }
    return { command, projectPath: positional[0], flags };
}

function main() {
    const { command, projectPath, flags } = parseArgv(process.argv.slice(2));

    if (!command || command === '--help' || command === '-h') {
        printUsage();
        process.exit(command ? 0 : 1);
        return;
    }

    if (command !== 'install' && command !== 'uninstall') {
        console.error(`Unknown command: ${command}\n`);
        printUsage();
        process.exit(1);
        return;
    }

    if (!projectPath) {
        console.error('Missing <projectPath>.\n');
        printUsage();
        process.exit(1);
        return;
    }

    try {
        if (command === 'install') {
            install(projectPath, flags.has('--force'));
        } else {
            uninstall(projectPath, flags.has('--remove-config'));
        }
    } catch (err) {
        console.error(`Error: ${err.message}`);
        process.exit(1);
    }
}

main();
