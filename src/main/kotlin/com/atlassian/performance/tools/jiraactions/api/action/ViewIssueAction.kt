package com.atlassian.performance.tools.jiraactions.api.action

import com.atlassian.performance.tools.jiraactions.api.VIEW_ISSUE
import com.atlassian.performance.tools.jiraactions.api.WebJira
import com.atlassian.performance.tools.jiraactions.api.measure.ActionMeter
import com.atlassian.performance.tools.jiraactions.api.memories.Issue
import com.atlassian.performance.tools.jiraactions.api.memories.IssueKeyMemory
import com.atlassian.performance.tools.jiraactions.api.memories.IssueMemory
import com.atlassian.performance.tools.jiraactions.api.memories.JqlMemory
import com.atlassian.performance.tools.jiraactions.api.page.IssuePage
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import javax.json.Json
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import java.io.File
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.util.*

private data class Res(
    val page: IssuePage,
    val navigationDuration: Duration,
    val actionDuration: Duration,
    val dumpDirectory: String
)

class ViewIssueAction(
    private val jira: WebJira,
    private val meter: ActionMeter,
    private val issueKeyMemory: IssueKeyMemory,
    private val issueMemory: IssueMemory,
    private val jqlMemory: JqlMemory
) : Action {
    private val logger: Logger = LogManager.getLogger(this::class.java)
    private val clock: Clock = Clock.systemUTC()

    override fun run() {
        val issueKey = issueKeyMemory.recall()
        if (issueKey == null) {
            logger.debug("Skipping View Issue action. I have no knowledge of issue keys.")
            return
        }
        val screenshotLocation = "diagnoses/" + UUID.randomUUID().toString()
        val dumpDirectory = Paths.get(screenshotLocation)
            .resolve(issueKey)
            .toFile()
            .ensureDirectory()
        val result = meter.measure(
            key = VIEW_ISSUE,
            action = {
                val start = clock.instant()
                // navigate
                val issuePage = jira.goToIssue(issueKey)
                val navigationEnded = clock.instant()
                val navigationDuration = Duration.between(start, navigationEnded)
                logger.debug("Navigation duration: ${navigationDuration.toMillis()} ms")
                // take screenshot in 800 ms
                saveScreenshot(dumpDirectory, Duration.ofMillis(800))
                // block and wait
                issuePage.waitForSummary()
                val summaryVisible = clock.instant()
                val actionDuration = Duration.between(start, summaryVisible)
                logger.debug("Action duration: ${actionDuration.toMillis()} ms")
                Res(issuePage, navigationDuration, actionDuration, dumpDirectory.path)
            },
            observation = { res -> Json.createObjectBuilder()
                .add("issueKey", issueKey)
                .add("issueId", res.page.getIssueId())
                .add("navigationDuration", res.navigationDuration.toMillis())
                .add("actionDuration", res.actionDuration.toMillis())
                .add("screenshotLocation", screenshotLocation)
                .build()
            }
        )
        val issue = Issue(
            key = issueKey,
            editable = result.page.isEditable(),
            id = result.page.getIssueId(),
            type = result.page.getIssueType()
        )
        issueMemory.remember(setOf(issue))
        jqlMemory.observe(result.page)
    }

    private fun saveScreenshot(dumpDirectory: File, delay: Duration) {
        val runnable = {
            Thread.sleep(delay.toMillis())
            val screenshot = File(dumpDirectory, "screenshot.png")
            logger.debug("Taking a screenshot")
            val start = clock.instant()
            try {
                val temporaryScreenshot = (jira.driver as TakesScreenshot).getScreenshotAs(OutputType.FILE)
                val moved = temporaryScreenshot.renameTo(screenshot)
                when {
                    moved -> logger.debug("screenshot saved to ${screenshot.path}")
                    else -> logger.error("screenshot failed to migrate from ${temporaryScreenshot.path}")
                }
            } catch (e: Exception) {
                logger.error("Failed taking a screenshot", e)
            }
            logger.debug("Sreenshot duration: ${Duration.between(start, clock.instant()).toMillis()} ms")
        }
        Thread(runnable).start()
    }

    fun File.ensureDirectory(): File {
        if (!this.isDirectory) {
            if (this.exists()) {
                throw RuntimeException("$this already exists and is not a directory")
            }
            val creationSuccessful = this.mkdirs()
            if (!creationSuccessful) {
                if (!this.isDirectory) {
                    throw RuntimeException("Failed to ensure that $this is a directory")
                }
            }
        }
        return this
    }
}
