package chaintest

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.context.TestCaseContext
import com.kms.katalon.core.context.TestSuiteContext
import com.kms.katalon.core.logging.KeywordLogger
import com.kms.katalon.core.logging.TestSuiteXMLLogParser
import com.kms.katalon.core.logging.model.ILogRecord
import com.kms.katalon.core.logging.model.TestCaseLogRecord
import com.kms.katalon.core.logging.model.TestStatus
import com.kms.katalon.core.logging.model.TestStepLogRecord
import com.kms.katalon.core.logging.model.TestSuiteLogRecord
import com.kms.katalon.core.webui.driver.DriverFactory
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.WebDriver

import com.aventstack.chaintest.domain.ExecutionStage
import com.aventstack.chaintest.domain.Test as ChainTest
import com.aventstack.chaintest.generator.ChainTestSimpleGenerator
import com.aventstack.chaintest.generator.ChainLPGenerator
import com.aventstack.chaintest.generator.Generator as ChainGenerator
import com.aventstack.chaintest.service.ChainPluginService

import java.util.concurrent.ConcurrentLinkedQueue

import java.io.FileFilter
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Bridges Katalon Studio's Test Listener lifecycle to ChainTest.
 *
 * A Test Suite Collection can run its member suites as genuinely separate
 * OS processes, not just threads in one JVM, so nothing here builds a live
 * ChainTest Test/Build object graph mid-run - a static field or in-memory
 * object populated by one suite's process is not visible to another
 * suite's process. Instead, every test case's outcome - status, timing,
 * message, a screenshot file, and (once parsed) its step tree - is written
 * as a small, immutable JSON record under chaintest.bridge.results.dir the
 * moment it's known. The real ChainTest object graph (Test/Build via
 * ChainPluginService) is only built once per run, by whichever suite's
 * AfterTestSuite is determined to be the last one - see generateReport().
 *
 * Every public method here catches Throwable and only logs a warning: a
 * bug in report generation must never fail, skip, or change the outcome of
 * the real test it's reporting on.
 */
class ChainTestReportBridge {

    private static final KeywordLogger logger = KeywordLogger.getInstance(ChainTestReportBridge.class)

    /** Last-resort fallback only - cross-phase state is not guaranteed to survive (see class doc). */
    private static volatile String currentSuiteName = 'Katalon Test Suite'

    /** Guards every file this bridge writes/reads under the results dir - see withRunLock(). */
    private static final Object INTRA_JVM_LOCK = new Object()

    /**
     * Buffers ChainTestKeywords.step()/log() calls made during ONE test
     * case's own execution, and tracks that same test case's start time.
     * Keyed by testCaseId rather than a ThreadLocal: Katalon does not
     * guarantee BeforeTestCase and AfterTestCase of the same test case run
     * on the same thread.
     */
    private static final Map<String, Long> testCaseStartTimes = new ConcurrentHashMap<>()

    private static final Map<String, List<Map>> testCaseManualEntries = new ConcurrentHashMap<>()

    /** Only needs to survive from startTestCase() to that same test case's own script body - one continuous, in-order call on a single thread. */
    private static final ThreadLocal<String> currentTestCaseId = new ThreadLocal<>()

    // ------------------------------------------------------------------
    // Test Listener entry points
    // ------------------------------------------------------------------

    static void startSuite(TestSuiteContext testSuiteContext) {
        try {
            if (!ChainTestConfig.isEnabled()) {
                return
            }
            File resultsDir = ChainTestConfig.getResultsDir()
            resultsDir.mkdirs()
            new File(resultsDir, 'attachments').mkdirs()

            withRunLock(resultsDir) {
                File thisRunDir = currentRunDir()
                Map marker = readRunMarker(resultsDir)
                String previousRunDir = marker.runDir as String
                boolean continuingRun
                if (thisRunDir != null) {
                    continuingRun = thisRunDir.absolutePath == previousRunDir
                } else {
                    continuingRun = previousRunDir != null && !previousRunDir.trim().isEmpty()
                }
                if (!continuingRun) {
                    if (ChainTestConfig.cleanResultsBeforeRun()) {
                        clearPreviousResults(resultsDir)
                    }
                    marker.reportPath = ''
                    new File(resultsDir, '.chaintest-collection-progress.txt').delete()
                }
                String runDirToRecord = thisRunDir != null ? thisRunDir.absolutePath : previousRunDir
                writeRunMarker(resultsDir, runDirToRecord ?: '', marker.reportPath as String)
            }

            currentSuiteName = resolveSuiteName(testSuiteContext)
            logger.logInfo("[ChainTest] Reporting enabled. Results directory: ${resultsDir.absolutePath}")
        } catch (Throwable t) {
            logger.logWarning("[ChainTest] Failed to initialize ChainTest reporting: ${t}")
        }
    }

    static void startTestCase(TestCaseContext testCaseContext) {
        try {
            if (!ChainTestConfig.isEnabled()) {
                return
            }
            String testCaseId = testCaseContext.getTestCaseId()
            testCaseManualEntries[testCaseId] = []
            testCaseStartTimes[testCaseId] = System.currentTimeMillis()
            currentTestCaseId.set(testCaseId)
        } catch (Throwable t) {
            logger.logWarning("[ChainTest] Failed to start test case reporting: ${t}")
        }
    }

    static void finishTestCase(TestCaseContext testCaseContext) {
        try {
            if (!ChainTestConfig.isEnabled()) {
                return
            }
            File resultsDir = ChainTestConfig.getResultsDir()
            String uuid = UUID.randomUUID().toString()
            String testCaseId = testCaseContext.getTestCaseId()
            String name = readableName(testCaseId)
            String suiteName = resolveSuiteNameQuiet() ?: currentSuiteName ?: 'Suite'
            String katalonStatus = (testCaseContext.getTestCaseStatus() ?: 'FAILED').toUpperCase()
            String message = testCaseContext.getMessage()

            String screenshotFile = null
            boolean shouldScreenshot = ChainTestConfig.attachScreenshotAlways() ||
                (ChainTestConfig.attachScreenshotOnFailure() && katalonStatus != 'PASSED')
            if (shouldScreenshot) {
                screenshotFile = captureScreenshot(resultsDir, uuid)
            }

            String activeBrowser = detectActiveBrowser()
            String suiteInstance = safeCall { suiteInstanceKey(currentRunDir()) } ?: suiteName

            Map record = [
                uuid          : uuid,
                testCaseId    : testCaseId,
                name          : name,
                suiteName     : suiteName,
                suiteInstance : suiteInstance,
                browser       : activeBrowser,
                status        : katalonStatus,
                start         : testCaseStartTimes.remove(testCaseId) ?: System.currentTimeMillis(),
                stop          : System.currentTimeMillis(),
                message       : message,
                screenshotFile: screenshotFile,
                manualEntries : testCaseManualEntries.remove(testCaseId) ?: [],
                autoSteps     : null,
                logFolder     : safeCall { RunConfiguration.getReportFolder() },
            ]

            withRunLock(resultsDir) {
                new File(resultsDir, "${uuid}.json").text = new JsonBuilder(record).toPrettyString()
            }

            if (ChainTestConfig.captureSteps() && record.logFolder) {
                queuePendingSteps(resultsDir, uuid, testCaseId)
            }

            currentTestCaseId.remove()
        } catch (Throwable t) {
            logger.logWarning("[ChainTest] Failed to finish test case reporting: ${t}")
        }
    }

    static void finishSuite(TestSuiteContext testSuiteContext) {
        try {
            if (!ChainTestConfig.isEnabled()) {
                return
            }
            File resultsDir = ChainTestConfig.getResultsDir()
            logger.logInfo("[ChainTest] Suite finished. Results: ${resultsDir.absolutePath}")
            String suiteName = resolveSuiteName(testSuiteContext)

            // Resolved once, right here, rather than re-resolving later:
            // processPendingSteps() below can block on the final suite,
            // and RunConfiguration.getReportFolder() reflects Katalon's
            // *current* execution context - after a delay it can no
            // longer be trusted to resolve the same run directory.
            File runDir = currentRunDir()

            boolean isFinalSuite = shouldGenerateReportNow(resultsDir, runDir)

            if (ChainTestConfig.captureSteps()) {
                processPendingSteps(resultsDir, isFinalSuite)
            }

            if (isFinalSuite) {
                generateReport(resultsDir, suiteName)
            }
        } catch (Throwable ignored) {
            // never fail the suite because of reporting
        }
    }

    // ------------------------------------------------------------------
    // Manual keyword support (ChainTestKeywords)
    // ------------------------------------------------------------------

    private static void addManualEntry(Map entry) {
        String testCaseId = currentTestCaseId.get()
        if (testCaseId == null) {
            return
        }
        List<Map> bucket = testCaseManualEntries[testCaseId]
        if (bucket == null) {
            return
        }
        bucket << entry
    }

    static void logManual(String status, String name, String details) {
        try {
            addManualEntry([status: status, name: name, details: details, screenshotFile: null])
        } catch (Throwable ignored) { }
    }

    static void step(String stepName, Closure body) {
        long start = System.currentTimeMillis()
        try {
            body.call()
            addManualEntry([status: 'PASS', name: stepName, details: null, screenshotFile: null, start: start, stop: System.currentTimeMillis()])
        } catch (Throwable t) {
            addManualEntry([status: 'FAIL', name: stepName, details: t.getMessage(), screenshotFile: null, start: start, stop: System.currentTimeMillis()])
            throw t
        }
    }

    static void attachScreenshot(String name) {
        try {
            File resultsDir = ChainTestConfig.getResultsDir()
            String file = captureScreenshot(resultsDir, UUID.randomUUID().toString())
            if (file) {
                addManualEntry([status: 'INFO', name: name, details: null, screenshotFile: file])
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    // Final report assembly - runs exactly once per run, in whichever
    // suite's process is determined to finish last.
    // ------------------------------------------------------------------

    private static void generateReport(File resultsDir, String suiteName) {
        try {
            List<File> recordFiles = withRunLock(resultsDir) {
                resultsDir.listFiles({ File f -> f.isFile() && f.name.endsWith('.json') } as FileFilter)?.toList() ?: []
            } as List<File>
            if (!recordFiles) {
                logger.logWarning('[ChainTest] No test case records found - skipping report generation.')
                return
            }

            JsonSlurper slurper = new JsonSlurper()
            List<Map> records = recordFiles.collect { File f ->
                try {
                    return slurper.parse(f) as Map
                } catch (Throwable t) {
                    logger.logWarning("[ChainTest] Skipping unreadable result record ${f.name}: ${t.getMessage()}")
                    return null
                }
            }.findAll { it != null }

            // Suites ordered alphabetically by suite name, start time as
            // tiebreaker between repeat occurrences of the same suite and
            // between test cases within one occurrence. Not attempted via
            // the Test Suite Collection's own configured row order: a
            // sibling bridge for a related reporting library tried that
            // twice (using the run's plan.jsonl) and found it silently
            // unreliable across real console-mode runs both times, for
            // reasons that couldn't be fully pinned down even with
            // real evidence - alphabetical order carries no such
            // run-state dependency.
            Map<String, Long> firstStartByInstance = [:]
            records.each { Map record ->
                String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
                long start = record.start as Long
                Long existing = firstStartByInstance[rawInstance]
                if (existing == null || start < existing) {
                    firstStartByInstance[rawInstance] = start
                }
            }
            records.sort { Map record ->
                String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
                [bareSuiteName(rawInstance).toLowerCase(), firstStartByInstance[rawInstance], record.start as Long]
            }
            assignDisplayLabels(records)

            File reportBaseDir = ChainTestConfig.getReportDir()
            reportBaseDir.mkdirs()
            String timestamp = new java.text.SimpleDateFormat('yyyyMMdd_HHmmss').format(new Date())
            String displayName = sanitizeForFilename(suiteName)
            File runReportDir = new File(reportBaseDir, "${displayName}_${timestamp}")
            File indexFile = new File(runReportDir, 'Index.html')

            ChainPluginService service = new ChainPluginService('katalon')
            service.getBuild().setProjectName(ChainTestConfig.getProjectName())
            addSystemInfo(service)

            List<String> registeredGeneratorNames = []
            List<ChainGenerator> activeGenerators = []

            ChainTestSimpleGenerator simple = null
            if (ChainTestConfig.simpleGeneratorEnabled()) {
                simple = new ChainTestSimpleGenerator()
                Map<String, String> simpleConfig = ChainTestConfig.buildSimpleGeneratorConfig(
                    indexFile.absolutePath, "${suiteName} - ChainTest Report")
                simple.start(Optional.of(simpleConfig), 'katalon', service.getBuild())
                service.register(simple)
                activeGenerators << simple
                registeredGeneratorNames << 'simple'
            }

            ChainLPGenerator chainLP = null
            if (ChainTestConfig.chainLPEnabled()) {
                chainLP = new ChainLPGenerator('katalon')
                Map<String, String> chainLPConfig = ChainTestConfig.buildChainLPGeneratorConfig()
                chainLP.start(Optional.of(chainLPConfig), 'katalon', service.getBuild())
                if (chainLP.started()) {
                    // service.register() is what makes service.afterTest()
                    // (called once per suite occurrence below, via
                    // ChainPluginService.afterTest() -> _generators.forEach)
                    // actually forward each Test to ChainLPGenerator's own
                    // afterTest(), which is what queues it for async upload.
                    // Found by checking ChainLP's own REST API after a real
                    // run: the Build landed (sent unconditionally inside
                    // ChainLPGenerator.flush()) but zero individual Tests
                    // did, because this registration was missing - flush()
                    // only sends whatever afterTest() had already queued.
                    service.register(chainLP)
                    activeGenerators << chainLP
                }
                registeredGeneratorNames << (chainLP.started() ? 'chainlp (connected)' : 'chainlp (unreachable, skipped)')
            }

            if (!registeredGeneratorNames) {
                logger.logWarning('[ChainTest] No generator is enabled - nothing will be produced. Set chaintest.generator.simple.enabled=true to restore the default static report.')
                return
            }

            List<Map> tagDefs = loadTagDefinitions()

            // Scoped to this one replay only - a fresh map per
            // generateReport() call, never reused across runs. Katalon
            // Studio's IDE keeps one JVM alive across many separate runs,
            // so a static/shared cache here would leak Test objects from
            // an earlier run into this one whenever a suiteInstance string
            // happened to collide.
            Map<String, ChainTest> suiteTestsByInstance = [:]
            records.each { Map record -> replayTestCase(service, record, resultsDir, tagDefs, suiteTestsByInstance) }

            // Each suite-level Test's own displayed duration/timestamp is
            // rendered from ITS OWN startedAt/endedAt, same as any other
            // Test - and Test.complete() bubbles up to the parent on every
            // child completion (recomputing the parent's result AND
            // re-stamping its endedAt to the replay instant each time), so
            // whatever a suite Test's window happened to end up with after
            // the loop above is bubbling noise, not a real value. Fixed up
            // here, once, per suite occurrence, from the true min(start)/
            // max(stop) across that occurrence's own test cases - the last
            // thing done to these objects before any generator reads them.
            Map<String, long[]> suiteWindowByInstance = [:]
            records.each { Map record ->
                String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
                long start = record.start as Long
                long stop = (record.stop ?: record.start) as Long
                long[] window = suiteWindowByInstance[rawInstance]
                if (window == null) {
                    suiteWindowByInstance[rawInstance] = [start, stop] as long[]
                } else {
                    window[0] = Math.min(window[0], start)
                    window[1] = Math.max(window[1], stop)
                }
            }
            suiteTestsByInstance.each { String rawInstance, ChainTest suiteTest ->
                long[] window = suiteWindowByInstance[rawInstance]
                if (window != null) {
                    suiteTest.setStartedAt(window[0])
                    suiteTest.setEndedAt(window[1])
                }
            }

            // service.afterTest() is what actually adds a Test to the
            // Build's tracked test list and (via Build.updateStats(),
            // which recurses into test.getChildren()) computes runStats/
            // tagStats - the numbers the dashboard's pass/fail/skip charts
            // are built from. Deliberately called here, once per suite
            // occurrence, only now that every one of its test cases (and
            // their own step children) has already been attached - calling
            // it earlier, e.g. at suite-Test creation time in
            // suiteTestFor(), would let updateStats() walk a still-empty
            // children list and permanently miss every count. Verified
            // with a controlled standalone test after finding this by
            // reading Build.updateStats()/updateRunStats() directly, not
            // assumed from ChainPluginService's own method name alone.
            Queue<ChainTest> topLevelTests = new ConcurrentLinkedQueue<>()
            suiteTestsByInstance.each { String rawInstance, ChainTest suiteTest ->
                service.afterTest(suiteTest, Optional.empty())
                topLevelTests << suiteTest
            }

            // Not service.flush(): it unconditionally calls Build.complete(),
            // which re-stamps Build.endedAt to the replay instant with no
            // window left to override it before a generator reads it -
            // verified with a controlled standalone test (a deliberately
            // wrong, decades-old timestamp) that this is otherwise exactly
            // what ends up in the rendered report's top summary bar, even
            // though every individual test's own timing is already correct
            // at that point. Build.result is unaffected either way - it was
            // already correctly priority-aggregated by each afterTest() call
            // above, independently of complete().
            long realStart = records.collect { it.start as Long }.min()
            long realStop = records.collect { it.stop as Long }.max()
            service.getBuild().setStartedAt(realStart)
            service.getBuild().setEndedAt(realStop)
            service.getBuild().setExecutionStage(ExecutionStage.FINISHED)
            activeGenerators.each { it.flush(topLevelTests) }

            withRunLock(resultsDir) {
                Map marker = readRunMarker(resultsDir)
                String previousPath = marker.reportPath as String
                if (previousPath) {
                    File previous = new File(previousPath).parentFile
                    if (previous != null && previous.exists() && previous.canonicalPath != runReportDir.canonicalPath) {
                        deleteRecursively(previous)
                    }
                }
                writeRunMarker(resultsDir, marker.runDir as String, indexFile.absolutePath)
            }

            logger.logInfo("[ChainTest] Report generators run: ${registeredGeneratorNames.join(', ')}")
            if (ChainTestConfig.simpleGeneratorEnabled()) {
                logger.logInfo("[ChainTest] HTML report ready: ${indexFile.absolutePath}")
            }
        } catch (Throwable t) {
            logger.logWarning("[ChainTest] Could not assemble the report: ${t}")
        }
    }

    private static void addSystemInfo(ChainPluginService service) {
        service.addSystemInfo('Project', safe(RunConfiguration.getProjectName()))
        service.addSystemInfo('Execution Profile', safe(RunConfiguration.getExecutionProfile()))
        service.addSystemInfo('Katalon Studio Version', safe(RunConfiguration.getAppVersion()))
        service.addSystemInfo('OS', safe(RunConfiguration.getOS()))
        service.addSystemInfo('Host', safe(RunConfiguration.getHostName()))
        Map ci = detectCI()
        service.addSystemInfo('Executor', ci.name as String)
        if (ci.buildUrl) {
            service.addSystemInfo('Build URL', ci.buildUrl as String)
        }
    }

    /**
     * Every distinct suite occurrence Katalon actually ran gets its own
     * separate group in the report - two occurrences of the same suite are
     * always shown separately. The label replaces Katalon's own opaque
     * disambiguation suffix with the occurrence's own browser where that's
     * already enough to tell two occurrences apart, falling back to a
     * plain "#2"/"#3" ordinal (ordered by which occurrence started first)
     * only when name+browser also collide.
     */
    private static void assignDisplayLabels(List<Map> records) {
        Map<String, Map> firstRecordByInstance = [:]
        Map<String, String> browserByInstance = [:]
        records.each { Map record ->
            String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
            if (!firstRecordByInstance.containsKey(rawInstance)) {
                firstRecordByInstance[rawInstance] = record
            }
            if (record.browser && !browserByInstance[rawInstance]) {
                browserByInstance[rawInstance] = record.browser as String
            }
        }

        Map<String, String> candidateLabel = firstRecordByInstance.collectEntries { String rawInstance, Map record ->
            String bareName = bareSuiteName(rawInstance)
            String browser = browserByInstance[rawInstance]
            [(rawInstance): (browser ? "${bareName} (${browser})" : bareName)]
        }

        Map<String, List<String>> instancesByCandidate = firstRecordByInstance.keySet().groupBy { candidateLabel[it] }

        Map<String, String> labelByInstance = [:]
        instancesByCandidate.each { String candidate, List<String> rawInstances ->
            if (rawInstances.size() == 1) {
                labelByInstance[rawInstances[0]] = candidate
            } else {
                List<String> ordered = rawInstances.sort { firstRecordByInstance[it].start as Long }
                ordered.eachWithIndex { String rawInstance, int index ->
                    labelByInstance[rawInstance] = "${candidate} #${index + 1}"
                }
            }
        }

        records.each { Map record ->
            String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
            record.displayLabel = labelByInstance[rawInstance]
        }
    }

    /**
     * suiteInstance carries the full Test Suites folder path, its own
     * per-occurrence timestamp segment, and, on runs where Katalon adds
     * one, its own disambiguation hash suffix glued onto the folder name
     * itself - all meaningful as the internal grouping key, none
     * meaningful to show. Order matters: the timestamp segment is
     * stripped first, since a timestamp like "20260818_080402" is also a
     * valid hex string and would otherwise get wrongly eaten by the
     * hash-suffix pattern first.
     */
    private static String bareSuiteName(String rawInstance) {
        String withoutTimestamp = rawInstance.replaceFirst(/\/\d{8}_\d{6}$/, '')
        String withoutHash = withoutTimestamp.replaceFirst(/_[0-9a-fA-F]{6,10}$/, '')
        int lastSlash = withoutHash.lastIndexOf('/')
        return lastSlash >= 0 ? withoutHash.substring(lastSlash + 1) : withoutHash
    }

    private static ChainTest replayTestCase(ChainPluginService service, Map record, File resultsDir, List<Map> tagDefs, Map<String, ChainTest> suiteTestsByInstance) {
        String suiteLabel = (record.displayLabel ?: safeCall { bareSuiteName(record.suiteInstance as String) } ?: record.suiteName ?: 'Suite') as String
        String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String

        ChainTest suiteTest = suiteTestFor(service, rawInstance, suiteLabel, suiteTestsByInstance)

        ChainTest tc = new ChainTest(record.name as String, Optional.empty(), Collections.emptyList())
        tc.setExternalId(record.uuid as String)
        if (record.start) {
            tc.setStartedAt(record.start as Long)
        }
        suiteTest.addChild(tc)

        String browser = record.browser as String
        if (browser) {
            tc.addTag(browser)
        }

        List<String> tags = matchTags(tagDefs, record.status as String, record.message as String)
        tags.each { tc.addTag(it) }

        List autoSteps = record.autoSteps as List
        if (autoSteps) {
            autoSteps.each { addStepNode(tc, it as Map) }
        }
        (record.manualEntries as List)?.each { Map entry ->
            addLogEntry(tc, entry.details as String ?: entry.name as String, entry.screenshotFile as String, resultsDir)
        }
        if (record.message) {
            tc.addLog("Failure details: ${record.message}" as String)
        }
        if (record.screenshotFile) {
            addLogEntry(tc, null, record.screenshotFile as String, resultsDir)
        }
        tc.addLog("Katalon status: ${record.status}" as String)

        String katalonStatus = (record.status as String)?.toUpperCase()
        if (katalonStatus == 'SKIPPED') {
            tc.complete()
            tc.setResult('SKIPPED')
        } else if (katalonStatus == 'FAILED' || katalonStatus == 'ERROR' || katalonStatus == 'INCOMPLETE') {
            tc.complete(new RuntimeException(record.message as String ?: katalonStatus))
        } else {
            tc.complete()
        }
        if (record.stop) {
            tc.setEndedAt(record.stop as Long)
        }
        return suiteTest
    }

    /**
     * One ChainTest.domain.Test per distinct suite occurrence, created
     * lazily and cached in the caller's map for the duration of this one
     * replay so every test case in that occurrence nests under the same
     * parent. Deliberately does NOT call service.afterTest() here - see
     * the call site in generateReport() for why that has to wait until
     * this suite's full children tree (every test case, and their own
     * step children) has actually been built.
     */
    private static ChainTest suiteTestFor(ChainPluginService service, String rawInstance, String suiteLabel, Map<String, ChainTest> suiteTestsByInstance) {
        ChainTest existing = suiteTestsByInstance[rawInstance]
        if (existing != null) {
            return existing
        }
        ChainTest suiteTest = new ChainTest(suiteLabel)
        suiteTestsByInstance[rawInstance] = suiteTest
        return suiteTest
    }

    private static void addStepNode(ChainTest parent, Map step) {
        ChainTest node = new ChainTest(step.name as String)
        if (step.start) {
            node.setStartedAt(step.start as Long)
        }
        parent.addChild(node)
        if (step.message) {
            node.addLog(step.message as String)
        }
        (step.children as List)?.each { addStepNode(node, it as Map) }
        String stepStatus = (step.status as String)?.toUpperCase()
        if (stepStatus == 'SKIPPED') {
            node.complete()
            node.setResult('SKIPPED')
        } else if (stepStatus == 'FAILED' || stepStatus == 'ERROR' || stepStatus == 'INCOMPLETE') {
            node.complete(new RuntimeException(step.message as String ?: stepStatus))
        } else {
            node.complete()
        }
        if (step.stop) {
            node.setEndedAt(step.stop as Long)
        }
    }

    private static void addLogEntry(ChainTest test, String text, String screenshotFile, File resultsDir) {
        if (screenshotFile) {
            File img = new File(resultsDir, "attachments/${screenshotFile}")
            if (img.isFile()) {
                try {
                    byte[] bytes = Files.readAllBytes(img.toPath())
                    test.addEmbed(bytes, 'image/png')
                    if (text) {
                        test.addLog(text)
                    }
                    return
                } catch (Throwable ignored) { }
            }
        }
        if (text) {
            test.addLog(text)
        }
    }

    // ------------------------------------------------------------------
    // Failure tags - Katalon/Selenium-specific classification, matched
    // here in plain Groovy, applied via ChainTest's own Tag system
    // (visible as filterable tag chips in the static report).
    // ------------------------------------------------------------------

    private static List<Map> loadTagDefinitions() {
        try {
            File src = ChainTestConfig.getTagsFile()
            if (!src.exists()) {
                return []
            }
            return new JsonSlurper().parse(src) as List<Map>
        } catch (Throwable t) {
            logger.logWarning("[ChainTest] Could not read failure-tags file: ${t.getMessage()}")
            return []
        }
    }

    private static List<String> matchTags(List<Map> tagDefs, String status, String message) {
        List<String> matches = []
        tagDefs.each { Map tagDef ->
            List<String> statuses = (tagDef.matchedStatuses as List)?.collect { it.toString().toUpperCase() } ?: []
            if (statuses && !statuses.contains(status?.toUpperCase())) {
                return
            }
            String messageRegex = tagDef.messageRegex as String
            boolean matches1 = !messageRegex || (message && Pattern.compile(messageRegex, Pattern.DOTALL).matcher(message).matches())
            if (matches1) {
                matches << (tagDef.name as String)
            }
        }
        return matches
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    private static String captureScreenshot(File resultsDir, String uuid) {
        try {
            WebDriver driver = DriverFactory.getWebDriver()
            if (driver instanceof TakesScreenshot) {
                byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)
                String fileName = "${uuid}.png"
                new File(resultsDir, "attachments/${fileName}").bytes = bytes
                return fileName
            }
        } catch (Throwable ignored) {
            // No active WebUI driver (API/mobile-only test, or driver already closed) - skip silently
        }
        return null
    }

    // ------------------------------------------------------------------
    // Step capture - Katalon keeps a test case's execution log open until
    // every AfterTestCase listener registered for it has returned, so
    // parsing is deferred to this suite's own AfterTestSuite, and every
    // currently-pending entry (not just this suite's own) is processed
    // there, since whichever suite's AfterTestSuite runs first ends up
    // doing the work for others too when suites run concurrently.
    // ------------------------------------------------------------------

    private static void queuePendingSteps(File resultsDir, String uuid, String testCaseId) {
        try {
            String logFolder = safeCall { RunConfiguration.getReportFolder() }
            if (!logFolder) {
                return
            }
            withRunLock(resultsDir) {
                new File(resultsDir, '.chaintest-pending-steps.txt').append("${uuid}|${testCaseId}|${logFolder}\n")
            }
        } catch (Throwable ignored) { }
    }

    private static void processPendingSteps(File resultsDir, boolean isFinalSuite) {
        List<String> claimedLines = withRunLock(resultsDir) {
            File pendingFile = new File(resultsDir, '.chaintest-pending-steps.txt')
            if (!pendingFile.isFile()) {
                return []
            }
            List<String> lines = pendingFile.readLines()
            pendingFile.delete()
            return lines
        } as List<String>

        if (!claimedLines) {
            return
        }
        List<String> stillPending = []
        claimedLines.each { line ->
            if (!line?.trim()) {
                return
            }
            String[] parts = line.split('\\|', 3)
            if (parts.length != 3) {
                return
            }
            String uuid = parts[0]
            String testCaseId = parts[1]
            String logFolder = parts[2]
            try {
                if (!new File(logFolder).isDirectory()) {
                    return
                }
                Map parsed = parseWithRetry(logFolder, testCaseId)
                if (parsed) {
                    patchRecordWithSteps(resultsDir, uuid, parsed)
                } else {
                    stillPending << line
                }
            } catch (Throwable ignored) {
                stillPending << line
            }
        }

        if (!stillPending) {
            return
        }
        if (isFinalSuite) {
            retryPendingStepsSynchronously(resultsDir, stillPending)
        } else {
            withRunLock(resultsDir) {
                new File(resultsDir, '.chaintest-pending-steps.txt').append(stillPending.collect { "${it}\n" }.join(''))
            }
        }
    }

    /**
     * Last resort for the final suite's own entries, which have no later
     * suite left to hand an unparsed entry to. Blocks rather than using a
     * background thread: a CI pipeline running one suite per process exits
     * almost immediately after this listener returns.
     */
    private static void retryPendingStepsSynchronously(File resultsDir, List<String> pendingLines) {
        pendingLines.each { line ->
            String[] parts = line.split('\\|', 3)
            if (parts.length != 3) {
                return
            }
            String uuid = parts[0]
            String testCaseId = parts[1]
            String logFolder = parts[2]
            Map parsed = null
            for (int attempt = 1; attempt <= 20 && !parsed; attempt++) {
                try {
                    parsed = parseStepsFromExecutionLog(logFolder, testCaseId)
                } catch (Throwable ignored) { }
                if (!parsed && attempt < 20) {
                    Thread.sleep(1000)
                }
            }
            if (parsed) {
                patchRecordWithSteps(resultsDir, uuid, parsed)
            }
        }
    }

    private static Map parseWithRetry(String logFolder, String testCaseId) {
        int maxAttempts = 5
        Map parsed = null
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                parsed = parseStepsFromExecutionLog(logFolder, testCaseId)
            } catch (Throwable ignored) { }
            if (parsed) {
                return parsed
            }
            if (attempt < maxAttempts) {
                Thread.sleep(300)
            }
        }
        return parsed
    }

    /** Patches both the step tree AND the real start/stop timestamps into an already-written record, replacing finishTestCase()'s rough same-process estimate with the timing Katalon's own execution log actually recorded. */
    private static void patchRecordWithSteps(File resultsDir, String uuid, Map parsed) {
        withRunLock(resultsDir) {
            File recordFile = new File(resultsDir, "${uuid}.json")
            if (!recordFile.isFile()) {
                return
            }
            Map record = new JsonSlurper().parse(recordFile) as Map
            record.autoSteps = parsed.steps
            if (parsed.start) {
                record.start = parsed.start
            }
            if (parsed.stop) {
                record.stop = parsed.stop
            }
            recordFile.text = new JsonBuilder(record).toPrettyString()
        }
    }

    @SuppressWarnings('deprecation')
    private static Map parseStepsFromExecutionLog(String logFolder, String testCaseId) {
        TestSuiteLogRecord suiteRecord = new TestSuiteXMLLogParser().readTestSuiteLogFromXMLFiles(logFolder, new org.eclipse.core.runtime.NullProgressMonitor())
        List<TestCaseLogRecord> testCases = suiteRecord.getAllTestCaseLogRecords() ?: []
        TestCaseLogRecord match = testCases.find { matchesTestCaseId(it.getName(), testCaseId) }
        if (match == null) {
            return null
        }
        List<Map> steps = convertLogRecordsToSteps(match.getChildRecords())
        if (!steps) {
            return null
        }
        return [
            steps: steps,
            start: match.getStartTime(),
            stop : match.getEndTime() > 0 ? match.getEndTime() : match.getStartTime(),
        ]
    }

    private static boolean matchesTestCaseId(String recordName, String testCaseId) {
        if (!recordName || !testCaseId) {
            return false
        }
        return recordName == testCaseId || recordName.endsWith(testCaseId) || testCaseId.endsWith(recordName)
    }

    @SuppressWarnings('deprecation')
    private static List<Map> convertLogRecordsToSteps(ILogRecord[] records) {
        List<Map> result = []
        if (!records) {
            return result
        }
        records.each { ILogRecord rec ->
            if (!(rec instanceof TestStepLogRecord)) {
                return
            }
            String name = rec.getName()
            if (isInternalStepName(name)) {
                return
            }
            TestStatus.TestStatusValue statusValue = rec.getStatus()?.getStatusValue()
            result << [
                name    : name,
                status  : (statusValue?.toString() ?: 'PASSED'),
                message : (statusValue?.isError() && rec.getMessage()) ? rec.getMessage() : null,
                start   : rec.getStartTime(),
                stop    : rec.getEndTime() > 0 ? rec.getEndTime() : rec.getStartTime(),
                children: convertLogRecordsToSteps(rec.getChildRecords()),
            ]
        }
        return result
    }

    private static boolean isInternalStepName(String stepName) {
        return stepName != null && (stepName.contains('ChainTestReportBridge.') || stepName.contains('ChainTestKeywords.'))
    }

    // ------------------------------------------------------------------
    // Cross-process coordination, run/collection detection - a real file
    // lock, not just a 'synchronized' block: a Test Suite Collection can
    // run member suites in genuinely separate OS processes at the same
    // time.
    // ------------------------------------------------------------------

    private static Object withRunLock(File resultsDir, Closure action) {
        synchronized (INTRA_JVM_LOCK) {
            RandomAccessFile raf = new RandomAccessFile(new File(resultsDir, '.chaintest-run.lock'), 'rw')
            try {
                FileLock lock = raf.getChannel().lock()
                try {
                    return action.call()
                } finally {
                    lock.release()
                }
            } finally {
                raf.close()
            }
        }
    }

    private static Map readRunMarker(File resultsDir) {
        File markerFile = new File(resultsDir, '.chaintest-run-marker.txt')
        if (!markerFile.isFile()) {
            return [runDir: '', reportPath: '']
        }
        try {
            List<String> lines = markerFile.readLines()
            return [runDir: lines.size() > 0 ? lines[0] : '', reportPath: lines.size() > 1 ? lines[1] : '']
        } catch (Throwable ignored) {
            return [runDir: '', reportPath: '']
        }
    }

    private static void writeRunMarker(File resultsDir, String runDir, String reportPath) {
        try {
            new File(resultsDir, '.chaintest-run-marker.txt').text = "${runDir ?: ''}\n${reportPath ?: ''}\n"
        } catch (Throwable ignored) { }
    }

    private static void clearPreviousResults(File resultsDir) {
        resultsDir.listFiles({ File f -> f.isFile() && f.name.endsWith('.json') } as FileFilter)?.each { it.delete() }
        File attachments = new File(resultsDir, 'attachments')
        if (attachments.isDirectory()) {
            deleteRecursively(attachments)
            attachments.mkdirs()
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return
        }
        if (f.isDirectory()) {
            f.listFiles()?.each { deleteRecursively(it) }
        }
        f.delete()
    }

    /**
     * Whether report generation should actually run now, or whether other
     * sub-suites in the same run are still going - count sub-suites
     * planned for this run from Reports/<run-ts>/plan.jsonl (written
     * before any sub-suite starts), and compare against how many distinct
     * suite instances have reported themselves complete so far. Falls
     * back to true (generate every time) whenever either signal can't be
     * determined.
     */
    private static boolean shouldGenerateReportNow(File resultsDir, File runDir) {
        if (runDir == null) {
            return true
        }
        Integer expected = expectedSuiteCountForRun(runDir)
        String key = suiteInstanceKey(runDir)
        if (expected == null || key == null) {
            return true
        }
        return withRunLock(resultsDir) {
            File progressFile = new File(resultsDir, '.chaintest-collection-progress.txt')
            Set<String> completed = progressFile.isFile() ?
                (progressFile.readLines().findAll { it?.trim() } as Set) : ([] as Set)
            completed << key
            progressFile.text = completed.join('\n') + '\n'
            return completed.size() >= expected
        } as boolean
    }

    private static Integer expectedSuiteCountForRun(File runDir) {
        try {
            File planFile = new File(runDir, 'plan.jsonl')
            if (!planFile.isFile()) {
                return null
            }
            String firstLine
            planFile.withReader('UTF-8') { reader -> firstLine = reader.readLine() }
            if (!firstLine?.trim()) {
                return null
            }
            Map root = new JsonSlurper().parseText(firstLine) as Map
            Map execution = root.execution as Map
            String kind = execution?.kind
            if (kind == 'TEST_SUITE_COLLECTION') {
                int count = ((execution.children as List) ?: []).count { (it as Map).kind == 'TEST_SUITE' }
                return count > 0 ? count : null
            }
            if (kind == 'TEST_SUITE' || kind == 'TEST_CASE') {
                return 1
            }
            return null
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Keeps the trailing per-suite-run timestamp segment as part of a
     * suite occurrence's identity, rather than stopping before it: Katalon
     * does not reliably add a disambiguating hash suffix to the report
     * folder NAME for a repeated suite in console-mode execution - two
     * occurrences of the same suite can both write to
     * ".../<suite name>/<own timestamp>", identical folder name, distinct
     * only by the timestamp subfolder.
     */
    private static String suiteInstanceKey(File runDir) {
        List<String> segments = suiteInstancePathSegments(runDir)
        if (segments == null) {
            return null
        }
        int timestampIndex = segments.findIndexOf { it ==~ /\d{8}_\d{6}/ }
        return timestampIndex > 0 ? segments[0..timestampIndex].join('/') : null
    }

    private static List<String> suiteInstancePathSegments(File runDir) {
        try {
            String reportFolder = RunConfiguration.getReportFolder()
            if (!reportFolder?.trim()) {
                return null
            }
            String runDirCanonical = runDir.canonicalPath
            String reportFolderCanonical = new File(reportFolder).canonicalPath
            if (!reportFolderCanonical.startsWith(runDirCanonical + File.separator)) {
                return null
            }
            String relative = reportFolderCanonical.substring(runDirCanonical.length() + 1).replace('\\', '/')
            return relative.split('/') as List
        } catch (Throwable ignored) {
            return null
        }
    }

    private static File currentRunDir() {
        try {
            String reportFolder = safeCall { RunConfiguration.getReportFolder() }
            if (!reportFolder?.trim()) {
                return null
            }
            File cursor = new File(reportFolder)
            int guard = 0
            while (cursor != null && !new File(cursor, 'plan.jsonl').isFile() && guard++ < 20) {
                cursor = cursor.parentFile
            }
            if (cursor != null && new File(cursor, 'plan.jsonl').isFile()) {
                return cursor
            }
            return runRootFromTimestampFolders(new File(reportFolder))
        } catch (Throwable ignored) {
            return null
        }
    }

    private static File runRootFromTimestampFolders(File reportFolder) {
        File cursor = reportFolder
        boolean pastOwnTimestamp = false
        int guard = 0
        while (cursor != null && guard++ < 20) {
            if (cursor.name ==~ /\d{8}_\d{6}/) {
                if (pastOwnTimestamp) {
                    return cursor
                }
                pastOwnTimestamp = true
            }
            cursor = cursor.parentFile
        }
        return null
    }

    /**
     * RunConfiguration.getExecutionSourceName() only ever returns the
     * individual member suite's own name for a Test Suite Collection -
     * recovered instead from the run's own folder structure: Katalon
     * writes a sibling directory directly under the run's report root,
     * named after the collection itself, alongside each member suite's
     * own folder.
     */
    private static String resolveCollectionName(File targetRunDir, String ownSuiteName) {
        if (targetRunDir == null) {
            return null
        }
        String viaMetadata = resolveCollectionNameFromMetadata(targetRunDir, ownSuiteName)
        return viaMetadata ?: resolveCollectionNameFromSiblingFolder(targetRunDir, ownSuiteName)
    }

    private static String resolveCollectionNameFromMetadata(File targetRunDir, String ownSuiteName) {
        File metadataDir = new File(targetRunDir, '.metadata')
        if (!new File(metadataDir, '.collection').isFile()) {
            return null
        }
        File cursor = metadataDir
        int guard = 0
        while (guard++ < 20) {
            File[] entries = cursor.listFiles({ File f -> f.isDirectory() } as FileFilter)
            if (!entries || entries.length == 0) {
                return null
            }
            File chosen = entries.find { it.name != ownSuiteName } ?: entries[0]
            File[] deeper = chosen.listFiles({ File f -> f.isDirectory() } as FileFilter)
            if (!deeper || deeper.length == 0) {
                return chosen.name
            }
            cursor = chosen
        }
        return null
    }

    private static String resolveCollectionNameFromSiblingFolder(File targetRunDir, String ownSuiteName) {
        File[] siblingDirs = targetRunDir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!siblingDirs) {
            return null
        }
        List<File> candidates = siblingDirs.findAll { it.name != ownSuiteName && it.name != 'requests' && it.name != '.metadata' }
        String runRootName = targetRunDir.name
        List<String> matches = candidates.collect { descendToCollectionFolder(it, runRootName, 0) }.findAll { it != null }
        return matches.size() == 1 ? matches[0] : null
    }

    private static String descendToCollectionFolder(File dir, String runRootName, int depth) {
        if (depth > 20) {
            return null
        }
        File matchingSubDir = new File(dir, runRootName)
        if (matchingSubDir.isDirectory() && !new File(matchingSubDir, 'execution0.log').isFile()) {
            return dir.name
        }
        File[] children = dir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!children) {
            return null
        }
        List<String> matches = children.collect { descendToCollectionFolder(it, runRootName, depth + 1) }.findAll { it != null }
        return matches.size() == 1 ? matches[0] : null
    }

    // ------------------------------------------------------------------
    // Suite naming, CI detection, misc helpers
    // ------------------------------------------------------------------

    private static String resolveSuiteNameQuiet() {
        String executionSourceName = safeCall { RunConfiguration.getExecutionSourceName() }
        if (executionSourceName?.trim()) {
            return executionSourceName
        }
        String executionSource = safeCall { RunConfiguration.getExecutionSource() }
        if (executionSource?.trim()) {
            return readableName(executionSource.replace('\\', '/')).replaceAll(/(?i)\.(ts|tc|tsc)$/, '')
        }
        return null
    }

    private static String resolveSuiteName(TestSuiteContext testSuiteContext) {
        String testSuiteId = safeCall { testSuiteContext?.getTestSuiteId() }
        String executionSourceName = safeCall { RunConfiguration.getExecutionSourceName() }
        String executionSource = safeCall { RunConfiguration.getExecutionSource() }

        String resolved
        if (executionSourceName?.trim()) {
            resolved = executionSourceName
        } else if (executionSource?.trim()) {
            resolved = readableName(executionSource.replace('\\', '/')).replaceAll(/(?i)\.(ts|tc|tsc)$/, '')
        } else if (testSuiteId?.trim()) {
            resolved = readableName(testSuiteId)
        } else {
            resolved = 'Suite'
        }

        String collectionName = safeCall { resolveCollectionName(currentRunDir(), resolved) }
        if (collectionName?.trim() && collectionName != resolved) {
            resolved = collectionName
        }
        return resolved
    }

    private static String detectActiveBrowser() {
        try {
            return DriverFactory.getWebDriver() != null ? DriverFactory.getExecutedBrowser()?.toString() : null
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Map detectCI() {
        Map<String, String> e = System.getenv()
        if (e['JENKINS_URL']) {
            return [name: 'Jenkins', buildUrl: e['BUILD_URL']]
        }
        if (e['TF_BUILD'] || e['SYSTEM_TEAMFOUNDATIONCOLLECTIONURI']) {
            String collectionUri = e['SYSTEM_TEAMFOUNDATIONCOLLECTIONURI'] ?: ''
            String project = e['SYSTEM_TEAMPROJECT'] ?: ''
            String buildId = e['BUILD_BUILDID'] ?: ''
            String buildUrl = (collectionUri && project && buildId) ?
                "${collectionUri}${project}/_build/results?buildId=${buildId}" : ''
            return [name: 'Azure Pipelines', buildUrl: buildUrl]
        }
        if (e['GITHUB_ACTIONS']) {
            String serverUrl = e['GITHUB_SERVER_URL'] ?: 'https://github.com'
            String repo = e['GITHUB_REPOSITORY'] ?: ''
            String runId = e['GITHUB_RUN_ID'] ?: ''
            return [name: 'GitHub Actions', buildUrl: "${serverUrl}/${repo}/actions/runs/${runId}"]
        }
        if (e['GITLAB_CI']) {
            return [name: 'GitLab CI', buildUrl: e['CI_JOB_URL']]
        }
        return [name: 'Local Run', buildUrl: null]
    }

    private static String safeCall(Closure<String> supplier) {
        try {
            return supplier.call()
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String readableName(String id) {
        if (!id) {
            return 'Unknown'
        }
        String[] parts = id.split('/')
        return parts[parts.length - 1]
    }

    private static String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? 'N/A' : value
    }

    private static String sanitizeForFilename(String name) {
        return (name ?: 'Suite').replaceAll('[^a-zA-Z0-9 _-]', '_').trim()
    }
}
