package chaintest

import com.kms.katalon.core.configuration.RunConfiguration

/**
 * Reads Include/config/chaintest/chaintest.properties, with every key
 * overridable via a CHAINTEST_<KEY_IN_UPPER_SNAKE_CASE> environment
 * variable so CI systems can redirect output without editing files checked
 * into the repo.
 *
 * Two families of keys live in the same file:
 *  - "chaintest.bridge.*"    this bridge's own settings (never seen by the
 *                            ChainTest library itself).
 *  - "chaintest.project.*"/ "chaintest.generator.*"
 *                            forwarded to ChainTest's own generators
 *                            (Test/ChainPluginService/Generator classes),
 *                            using the exact property names ChainTest
 *                            itself defines. Kept flat rather than
 *                            reshaped, so anyone already familiar with
 *                            ChainTest's own Config.md recognizes them.
 */
class ChainTestConfig {

    private static final String CONFIG_RELATIVE_PATH = 'Include/config/chaintest/chaintest.properties'

    private static final String DEFAULT_TAGS_RELATIVE_PATH = 'Include/config/chaintest/failure-tags.json'

    private static final String DEFAULT_RESULTS_DIR_NAME = 'chaintest-results'

    private static final String DEFAULT_REPORT_DIR_NAME = 'chaintest-report'

    private static Properties fileProps

    private static synchronized Properties fileProperties() {
        if (fileProps == null) {
            fileProps = new Properties()
            File configFile = new File(RunConfiguration.getProjectDir(), CONFIG_RELATIVE_PATH)
            if (configFile.exists()) {
                configFile.withInputStream { stream -> fileProps.load(stream) }
            }
        }
        return fileProps
    }

    /**
     * Every key here already starts with "chaintest." (e.g.
     * "chaintest.generator.simple.output-file"), so upper-casing and
     * folding both dots and hyphens into underscores alone already
     * produces "CHAINTEST_GENERATOR_SIMPLE_OUTPUT_FILE" - no extra
     * "CHAINTEST_" prefix needed on top of that. Hyphens are folded too
     * alongside dots: several of ChainTest's own key names use them
     * (output-file, dark-theme, request-timeout-s), not just dots.
     */
    private static String read(String key, String defaultValue) {
        String envKey = key.toUpperCase().replaceAll(/[.\-]/, '_')
        String envValue = System.getenv(envKey)
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim()
        }
        return fileProperties().getProperty(key, defaultValue)
    }

    private static File resolvePath(String configuredPath) {
        File file = new File(configuredPath)
        return file.isAbsolute() ? file : new File(RunConfiguration.getProjectDir(), configuredPath)
    }

    // ------------------------------------------------------------------
    // Bridge-only settings
    // ------------------------------------------------------------------

    static boolean isEnabled() {
        return Boolean.parseBoolean(read('chaintest.bridge.enabled', 'true'))
    }

    static File getResultsDir() {
        return resolvePath(read('chaintest.bridge.results.dir', DEFAULT_RESULTS_DIR_NAME))
    }

    static boolean cleanResultsBeforeRun() {
        return Boolean.parseBoolean(read('chaintest.bridge.clean.results.before.run', 'true'))
    }

    static boolean attachScreenshotOnFailure() {
        return Boolean.parseBoolean(read('chaintest.bridge.attach.screenshot.on.failure', 'true'))
    }

    static boolean attachScreenshotAlways() {
        return Boolean.parseBoolean(read('chaintest.bridge.attach.screenshot.always', 'false'))
    }

    static boolean captureSteps() {
        return Boolean.parseBoolean(read('chaintest.bridge.capture.steps', 'true'))
    }

    static File getTagsFile() {
        return resolvePath(read('chaintest.bridge.tags.file', DEFAULT_TAGS_RELATIVE_PATH))
    }

    static File getReportDir() {
        return resolvePath(read('chaintest.bridge.report.dir', DEFAULT_REPORT_DIR_NAME))
    }

    // ------------------------------------------------------------------
    // ChainTest-native settings - key names match Config.md / the actual
    // property constants in chaintest-core exactly.
    // ------------------------------------------------------------------

    /** Falls back to the real Katalon project name (not a placeholder) whenever chaintest.project.name is left unset in both config and environment. */
    static String getProjectName() {
        String configured = read('chaintest.project.name', '')
        if (configured?.trim()) {
            return configured.trim()
        }
        String detected = null
        try {
            detected = RunConfiguration.getProjectName()
        } catch (Throwable ignored) { }
        return detected?.trim() ? detected.trim() : 'Katalon Project'
    }

    static boolean simpleGeneratorEnabled() {
        return Boolean.parseBoolean(read('chaintest.generator.simple.enabled', 'true'))
    }

    static boolean simpleOffline() {
        return Boolean.parseBoolean(read('chaintest.generator.simple.offline', 'true'))
    }

    static boolean simpleDarkTheme() {
        return Boolean.parseBoolean(read('chaintest.generator.simple.dark-theme', 'false'))
    }

    static String simpleDatetimeFormat() {
        return read('chaintest.generator.simple.datetime-format', 'yyyy-MM-dd HH:mm:ss a')
    }

    /** Empty string, never null - com.aventstack.chaintest.generator.ChainTestSimpleGenerator.flush() builds a Map.of(...) that throws NullPointerException if this key is absent from the config it was started with. */
    static String simpleCustomJs() {
        return read('chaintest.generator.simple.js', '')
    }

    /** Empty string, never null - see simpleCustomJs() for why this can't be left unset. */
    static String simpleCustomCss() {
        return read('chaintest.generator.simple.css', '')
    }

    static boolean chainLPEnabled() {
        return Boolean.parseBoolean(read('chaintest.generator.chainlp.enabled', 'false'))
    }

    static String chainLPHostUrl() {
        return read('chaintest.generator.chainlp.host.url', 'http://localhost:8085/')
    }

    static boolean chainLPPersistEmbeds() {
        return Boolean.parseBoolean(read('chaintest.generator.chainlp.persist-embeds', 'true'))
    }

    static String chainLPRequestTimeoutS() {
        return read('chaintest.generator.chainlp.client.request-timeout-s', '30')
    }

    static String chainLPMaxRetries() {
        return read('chaintest.generator.chainlp.client.max-retries', '3')
    }

    static String chainLPRetryIntervalMs() {
        return read('chaintest.generator.chainlp.client.retry-interval-ms', '1000')
    }

    static boolean chainLPExpectContinue() {
        return Boolean.parseBoolean(read('chaintest.generator.chainlp.client.expect-continue', 'false'))
    }

    /** Defaulted to false regardless of ChainTest's own upstream default: a ChainLP transport failure must never be allowed to propagate into the real Katalon test result. */
    static boolean chainLPThrowAfterRetryAttemptsExceeded() {
        return Boolean.parseBoolean(read('chaintest.generator.chainlp.client.throw-after-retry-attempts-exceeded', 'false'))
    }

    // ------------------------------------------------------------------
    // Assembly helpers - build the exact Map<String,String> shape each
    // ChainTest Generator.start() expects, and mirror the same resolved
    // values into System properties. The two registered generators read
    // configuration differently under the hood (verified by reading
    // chaintest-core's own source, not assumed): ChainTestSimpleGenerator
    // uses only the Map handed to start(), while ChainLPGenerator's
    // start() builds its own ChainTestApiClient() internally, which
    // reloads configuration itself from classpath resources, environment
    // variables, and System properties - completely ignoring the Map
    // passed to start() except for its own enabled/persist-embeds flags.
    // System properties are the only layer both paths honour, so every
    // resolved value is pushed there too.
    // ------------------------------------------------------------------

    static void pushToSystemProperties(Map<String, String> resolved) {
        resolved.each { String key, String value ->
            if (value != null) {
                System.setProperty(key, value)
            }
        }
    }

    static Map<String, String> buildSimpleGeneratorConfig(String outputFilePath, String documentTitle) {
        Map<String, String> config = [
            'chaintest.project.name'                  : getProjectName(),
            'chaintest.generator.simple.enabled'       : 'true',
            'chaintest.generator.simple.output-file'   : outputFilePath,
            'chaintest.generator.simple.offline'       : simpleOffline().toString(),
            'chaintest.generator.simple.document-title': documentTitle,
            'chaintest.generator.simple.dark-theme'    : simpleDarkTheme().toString(),
            'chaintest.generator.simple.datetime-format': simpleDatetimeFormat(),
            'chaintest.generator.simple.js'            : simpleCustomJs(),
            'chaintest.generator.simple.css'           : simpleCustomCss(),
        ]
        pushToSystemProperties(config)
        return config
    }

    static Map<String, String> buildChainLPGeneratorConfig() {
        Map<String, String> config = [
            'chaintest.project.name'                                             : getProjectName(),
            'chaintest.generator.chainlp.enabled'                                : 'true',
            'chaintest.generator.chainlp.host.url'                               : chainLPHostUrl(),
            'chaintest.generator.chainlp.persist-embeds'                         : chainLPPersistEmbeds().toString(),
            'chaintest.generator.chainlp.client.request-timeout-s'               : chainLPRequestTimeoutS(),
            'chaintest.generator.chainlp.client.max-retries'                     : chainLPMaxRetries(),
            'chaintest.generator.chainlp.client.retry-interval-ms'               : chainLPRetryIntervalMs(),
            'chaintest.generator.chainlp.client.expect-continue'                 : chainLPExpectContinue().toString(),
            'chaintest.generator.chainlp.client.throw-after-retry-attempts-exceeded': chainLPThrowAfterRetryAttemptsExceeded().toString(),
        ]
        pushToSystemProperties(config)
        return config
    }
}
