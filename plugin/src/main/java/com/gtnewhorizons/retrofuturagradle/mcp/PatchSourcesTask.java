package com.gtnewhorizons.retrofuturagradle.mcp;

import com.cloudbees.diff.PatchException;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.gtnewhorizons.retrofuturagradle.util.patching.ContextualPatch;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.apache.commons.collections4.iterators.IteratorIterable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class PatchSourcesTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPatches();

    /**
     * Defines directories from which to inject (overwrite if aleady present) source files/resources as-is.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInjectionDirectories();

    @Input
    public abstract Property<Integer> getMaxFuzziness();

    @Inject
    public abstract FileOperations getFileOperations();

    private final Map<String, byte[]> loadedResources = new HashMap<>();
    private final Map<String, String> loadedSources = new HashMap<>();

    @Inject
    public PatchSourcesTask() {
        getMaxFuzziness().convention(0);
    }

    @TaskAction
    public void patchSources() throws IOException, PatchException {
        loadedResources.clear();
        loadedSources.clear();
        Utilities.loadMemoryJar(getInputJar().get().getAsFile(), loadedResources, loadedSources);
        getLogger()
                .lifecycle(
                        "Patching sources: {} patch archives, {} injection directories",
                        getPatches().getFiles().size(),
                        getInjectionDirectories().getFiles().size());

        injectFiles();

        patchFiles();

        Utilities.saveMemoryJar(
                loadedResources, loadedSources, getOutputJar().get().getAsFile());
    }

    private void injectFiles() throws IOException {
        for (File injectDir : getInjectionDirectories()) {
            final Path rootPath = Paths.get(injectDir.toURI());
            for (File toInject : new IteratorIterable<>(FileUtils.iterateFiles(injectDir, null, true))) {
                final Path absPath = Paths.get(toInject.toURI());
                final Path relativePath = rootPath.relativize(absPath);
                final String relPathStr = relativePath.toString().replace('\\', '/');
                if (relPathStr.endsWith(".java")) {
                    loadedSources.put(relPathStr, FileUtils.readFileToString(toInject, StandardCharsets.UTF_8));
                } else {
                    loadedResources.put(relPathStr, FileUtils.readFileToByteArray(toInject));
                }
            }
        }
    }

    private void patchFiles() throws IOException, PatchException {
        final Utilities.InMemoryJarContextProvider contextProvider =
                new Utilities.InMemoryJarContextProvider(loadedSources, 3);
        final File logFile = new File(getTemporaryDir(), "patching.log");
        Throwable failure = null;
        int patchCount = 0;
        try (final FileOutputStream fos = new FileOutputStream(logFile);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                final PrintWriter logStream = new PrintWriter(bos)) {
            for (File patchSpec : getPatches()) {
                final FileCollection patchFiles;
                if (patchSpec.isDirectory()) {
                    patchFiles = getFileOperations().fileTree(patchSpec);
                } else if (patchSpec.getName().endsWith(".zip")
                        || patchSpec.getName().endsWith(".jar")) {
                    patchFiles = getFileOperations().zipTree(patchSpec);
                } else {
                    patchFiles = getFileOperations().immutableFiles(patchSpec);
                }
                for (File patchFile : patchFiles) {
                    logStream.printf("Applying patch %s from bundle %s%n", patchFile.getPath(), patchSpec.getPath());
                    patchCount++;
                    final ContextualPatch patch = ContextualPatch.create(
                            FileUtils.readFileToString(patchFile, StandardCharsets.UTF_8), contextProvider);
                    patch.setAccessC14N(true);
                    patch.setMaxFuzz(getMaxFuzziness().get());
                    final List<ContextualPatch.PatchReport> reports = patch.patch(false);
                    for (ContextualPatch.PatchReport report : reports) {
                        if (!report.getStatus().isSuccess()) {
                            logStream.printf(
                                    "Patch %s failed: %s%n",
                                    contextProvider.strip(report.getTarget()),
                                    report.getFailure().getMessage());
                            failure = report.getFailure();
                            for (ContextualPatch.HunkReport hunk : report.getHunks()) {
                                if (hunk.getStatus() == ContextualPatch.PatchStatus.Fuzzed) {
                                    logStream.printf(" - Hunk %d fuzzed %d%n", hunk.getHunkID(), hunk.getFuzz());
                                } else if (!hunk.getStatus().isSuccess()
                                        && getLogger().isErrorEnabled()) {
                                    logStream.printf(
                                            " - Hunk %d failed (%d+%d -> %d+%d): %n %s%n",
                                            hunk.getHunkID(),
                                            hunk.hunk.baseStart,
                                            hunk.hunk.baseCount,
                                            hunk.hunk.modifiedStart,
                                            hunk.hunk.modifiedCount,
                                            StringUtils.join(hunk.hunk.lines, "\n"));
                                }
                            }
                        } else if (report.getStatus() == ContextualPatch.PatchStatus.Fuzzed) {
                            logStream.printf("Patch fuzzed: %s%n", contextProvider.strip(report.getTarget()));
                            for (ContextualPatch.HunkReport hunk : report.getHunks()) {
                                if (hunk.getStatus() == ContextualPatch.PatchStatus.Fuzzed) {
                                    logStream.printf(" - Hunk %d fuzzed %d%n", hunk.getHunkID(), hunk.getFuzz());
                                }
                            }
                        }
                    }
                }
            }
        }
        if (failure != null) {
            getLogger().error("Patching errors occured, check the logfile at {} for details", logFile.getPath());
            throw new RuntimeException(failure);
        }
        getLogger().lifecycle("Applied {} patches", patchCount);
    }
}
