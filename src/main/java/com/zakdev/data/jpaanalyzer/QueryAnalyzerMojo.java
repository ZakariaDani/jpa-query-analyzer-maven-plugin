package com.zakdev.data.jpaanalyzer;

import com.zakdev.data.jpaanalyzer.analyzer.ConfigAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.EntityAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.JpqlAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.SpringDataMethodAnalyzer;
import com.zakdev.data.jpaanalyzer.model.ScanReport;
import com.zakdev.data.jpaanalyzer.reporter.Baseline;
import com.zakdev.data.jpaanalyzer.reporter.JsonReporter;
import com.zakdev.data.jpaanalyzer.reporter.SarifReporter;
import com.zakdev.data.jpaanalyzer.reporter.TerminalReporter;
import com.zakdev.data.jpaanalyzer.scanner.ProjectScanner;
import com.zakdev.data.jpaanalyzer.scanner.TypeResolution;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Static-analyzes JPA queries in a Spring Boot project and prints a
 * performance report. Bind to a phase or run directly:
 *
 *   mvn jpa-analyzer:analyze
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY, requiresProject = true, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class QueryAnalyzerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.sourceDirectory}", required = true, readonly = true)
    private File sourceDirectory;

    /** Resources directory scanned for application.properties / application.yml. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources", readonly = true)
    private File resourcesDirectory;

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

    /**
     * Resolve types against the project's classpath so repositories that reach a Spring Data
     * supertype through a custom base interface are detected. Disable to fall back to name-only
     * matching (faster, no dependency resolution needed).
     */
    @Parameter(property = "jpa.analyzer.resolveTypes", defaultValue = "true")
    private boolean resolveTypes;

    /** Output format: {@code terminal} (default), {@code json}, or {@code sarif}. */
    @Parameter(property = "jpa.analyzer.format", defaultValue = "terminal")
    private String format;

    /** Where to write json/sarif output. When unset, the report is printed to stdout. */
    @Parameter(property = "jpa.analyzer.outputFile")
    private File outputFile;

    /** Baseline file of accepted issues; matching findings are suppressed so CI fails only on new ones. */
    @Parameter(property = "jpa.analyzer.baseline")
    private File baseline;

    /** Regenerate the baseline file from the current findings and do not fail the build. */
    @Parameter(property = "jpa.analyzer.writeBaseline", defaultValue = "false")
    private boolean writeBaseline;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("JPA Query Analyzer skipped (jpa.analyzer.skip=true).");
            return;
        }

        getLog().info("Analyzing JPA queries in: " + sourceDirectory);

        TypeResolution typeResolution = buildTypeResolution();
        getLog().info("Type resolution: " + (typeResolution.solvingEnabled() ? "on" : "off"));

        ProjectScanner scanner = new ProjectScanner(
                new JpqlAnalyzer(), new EntityAnalyzer(), new SpringDataMethodAnalyzer(),
                new ConfigAnalyzer(), typeResolution);
        ScanReport report = scanner.scan(sourceDirectory);
        scanner.scanConfig(resourcesDirectory, report);

        // Regenerate the baseline and stop: snapshot current findings as accepted, never fail.
        if (writeBaseline) {
            if (baseline == null) {
                throw new MojoFailureException("writeBaseline=true requires jpa.analyzer.baseline to be set.");
            }
            try {
                long n = Baseline.size(report);
                Baseline.write(baseline, report);
                getLog().info("Wrote baseline with " + n + " fingerprint(s) to " + baseline);
            } catch (IOException e) {
                throw new MojoFailureException("Failed to write baseline: " + e.getMessage(), e);
            }
            return;
        }

        // Apply an existing baseline so only new issues are reported and can fail the build.
        if (baseline != null && baseline.exists()) {
            long suppressed = Baseline.apply(baseline, report);
            if (suppressed > 0) getLog().info("Suppressed " + suppressed + " baselined issue(s).");
        }

        render(report);

        if (failOnWarning && (report.totalWarnings() > 0 || report.totalCritical() > 0)) {
            throw new MojoFailureException(
                    (report.totalCritical() + report.totalWarnings()) + " JPA issue(s) found (failOnWarning).");
        }
        if (failOnCritical && report.totalCritical() > 0) {
            throw new MojoFailureException(
                    report.totalCritical() + " critical JPA issue(s) found (failOnCritical).");
        }
    }

    private TypeResolution buildTypeResolution() {
        if (!resolveTypes) {
            return TypeResolution.create(List.of(), List.of(), false);
        }
        List<File> sourceRoots = project.getCompileSourceRoots().stream().map(File::new).toList();
        List<String> classpath;
        try {
            classpath = project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            // Without a resolved classpath we can still resolve the project's own sources.
            getLog().warn("Compile classpath unavailable; type resolution limited to project sources.");
            classpath = List.of();
        }
        return TypeResolution.create(sourceRoots, classpath, true);
    }

    private void render(ScanReport report) throws MojoFailureException {
        switch (format == null ? "terminal" : format.toLowerCase()) {
            case "json"  -> emit(new JsonReporter().render(report), "JSON");
            case "sarif" -> emit(new SarifReporter().render(report), "SARIF");
            default      -> {
                // Even without a console (CI), keep colour unless explicitly disabled,
                // since Maven output is usually captured by a colour-aware terminal.
                boolean useColor = !noColor && System.getenv("NO_COLOR") == null;
                new TerminalReporter(useColor).report(report);
            }
        }
    }

    private void emit(String content, String label) throws MojoFailureException {
        if (outputFile == null) {
            System.out.println(content);
            return;
        }
        try {
            if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
            Files.writeString(outputFile.toPath(), content, StandardCharsets.UTF_8);
            getLog().info("Wrote " + label + " report to " + outputFile);
        } catch (IOException e) {
            throw new MojoFailureException("Failed to write " + label + " report: " + e.getMessage(), e);
        }
    }
}
