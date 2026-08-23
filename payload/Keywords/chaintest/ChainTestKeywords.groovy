package chaintest

import com.kms.katalon.core.annotation.Keyword

/**
 * Optional, opt-in enrichment API for ChainTest reporting from inside a
 * Test Case script or Cucumber glue code. ChainTestListener already gives
 * every test case a report entry with zero code changes; call these only
 * where extra detail is wanted (nested steps, extra screenshots, manual
 * log lines).
 *
 * Usage from a Test Case script:
 *   CustomKeywords.'chaintest.ChainTestKeywords.step'('Log in as admin', {
 *       WebUI.setText(findTestObject('Page/input_Username'), 'admin')
 *       WebUI.setText(findTestObject('Page/input_Password'), 'pwd')
 *       WebUI.click(findTestObject('Page/button_Login'))
 *   })
 */
class ChainTestKeywords {

    @Keyword
    static void step(String stepName, Closure body) {
        ChainTestReportBridge.step(stepName, body)
    }

    @Keyword
    static void info(String message) {
        ChainTestReportBridge.logManual('INFO', message, message)
    }

    @Keyword
    static void warning(String message) {
        ChainTestReportBridge.logManual('WARNING', message, message)
    }

    @Keyword
    static void pass(String message) {
        ChainTestReportBridge.logManual('PASS', message, message)
    }

    @Keyword
    static void fail(String message) {
        ChainTestReportBridge.logManual('FAIL', message, message)
    }

    /** Captures a screenshot of the active WebUI browser session, if any. No-op otherwise. */
    @Keyword
    static void attachScreenshot(String name = 'Screenshot') {
        ChainTestReportBridge.attachScreenshot(name)
    }
}
