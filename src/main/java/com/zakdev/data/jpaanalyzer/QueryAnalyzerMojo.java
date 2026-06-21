package com.zakdev.data.jpaanalyzer;

import com.zakdev.data.jpaanalyzer.analyzer.EntityAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.JpqlAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.SpringDataMethodAnalyzer;
import com.zakdev.data.jpaanalyzer.model.ScanReport;
import com.zakdev.data.jpaanalyzer.reporter.TerminalReporter;
import com.zakdev.data.jpaanalyzer.scanner.ProjectScanner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Static-analyzes JPA queries in a Spring Boot project and prints a
 * performance report. Bind to a phase or run directly:
 *
 *   mvn jpa-analyzer:analyze
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY, requiresProject = true, threadSafe = true)
public class QueryAnalyzerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.sourceDirectory}", required = true, readonly = true)
    private File sourceDirectory;

    /** Fail the build if any CRITICAL issue is found. */
    @Parameter(property = "jpa.analyzer.failOnCritical", defaultValue = "false")
    private boolean failOnCritical;

    /** Fail the build if any WARNING (or worse) is found. */
    @Parameter(property = "jpa.analyzer.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    /** Disable ANSI colours in the output. */
    @Parameter(property = "jpa.analyzer.noColor", defaultValue = "false")
    private boolean noColor;

    /** Skip analysis entirely. */
    @Parameter(property = "jpa.analyzer.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("JPA Query Analyzer skipped (jpa.analyzer.skip=true).");
            return;
        }

        getLog().info("Analyzing JPA queries in: " + sourceDirectory);

        ProjectScanner scanner = new ProjectScanner(
                new JpqlAnalyzer(), new EntityAnalyzer(), new SpringDataMethodAnalyzer());
        ScanReport report = scanner.scan(sourceDirectory);

        boolean useColor = !noColor
                && System.getenv("NO_COLOR") == null
                && System.console() != null;
        // Even without a console (CI), keep colour unless explicitly disabled,
        // since Maven output is usually captured by a colour-aware terminal.
        useColor = !noColor && System.getenv("NO_COLOR") == null;

        new TerminalReporter(useColor).report(report);

        if (failOnWarning && (report.totalWarnings() > 0 || report.totalCritical() > 0)) {
            throw new MojoFailureException(
                    (report.totalCritical() + report.totalWarnings()) + " JPA issue(s) found (failOnWarning).");
        }
        if (failOnCritical && report.totalCritical() > 0) {
            throw new MojoFailureException(
                    report.totalCritical() + " critical JPA issue(s) found (failOnCritical).");
        }
    }
}
