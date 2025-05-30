/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package com.gtnewhorizons.retrofuturagradle;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.zip.ZipFile;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A simple functional test for the 'retrofuturagradle.greeting' plugin.
 */
class UserDevPluginFunctionalTest {

    public static final String SIMPLE_SETTINGS = """
            plugins {
              id("org.gradle.toolchains.foojay-resolver-convention") version "0.4.0"
            }
            """;

    public static final String SIMPLE_BUILDSCRIPT = """
            plugins {
                id('com.gtnewhorizons.retrofuturagradle')
            }
            minecraft {
                mcVersion = '1.7.10'
            }
            """;

    @TempDir
    File projectDir;

    private File getBuildFile() {
        return new File(projectDir, "build.gradle");
    }

    private File getSettingsFile() {
        return new File(projectDir, "settings.gradle");
    }

    private File getBuildDir() {
        return new File(projectDir, "build");
    }

    private File getLocalCacheDir() {
        return new File(getBuildDir(), "rfg");
    }

    @Test
    void canFetchData() throws IOException {
        writeString(getSettingsFile(), SIMPLE_SETTINGS);
        writeString(getBuildFile(), SIMPLE_BUILDSCRIPT);

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("--stacktrace", "--", "downloadVanillaJars", "downloadVanillaAssets");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();
        BuildResult secondResult = runner.build();
        Assertions.assertArrayEquals(
                new BuildTask[] {},
                secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0]));
    }

    @Test
    void canObtainDevPackages() throws IOException {
        writeString(getSettingsFile(), SIMPLE_SETTINGS);
        writeString(getBuildFile(), SIMPLE_BUILDSCRIPT);

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("--stacktrace", "--", "downloadFernflower");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();
        BuildResult secondResult = runner.build();
        Assertions.assertArrayEquals(
                new BuildTask[] {},
                secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0]));
    }

    @Test
    void canMergeVanillaSidedJars() throws IOException {
        writeString(getSettingsFile(), SIMPLE_SETTINGS);
        writeString(getBuildFile(), SIMPLE_BUILDSCRIPT);

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("--stacktrace", "--", "mergeVanillaSidedJars");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();
        BuildResult secondResult = runner.build();
        Assertions.assertArrayEquals(
                new BuildTask[] {},
                secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0]));
    }

    @Test
    void canDecompile() throws IOException {
        writeString(getSettingsFile(), SIMPLE_SETTINGS);
        writeString(getBuildFile(), SIMPLE_BUILDSCRIPT);

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("--stacktrace", "--", "decompressDecompiledSources");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();
        BuildResult secondResult = runner.build();
        Assertions.assertArrayEquals(
                new BuildTask[] {},
                secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0]));
    }

    @Test
    void canRecompile() throws IOException {
        writeString(getSettingsFile(), SIMPLE_SETTINGS);
        writeString(getBuildFile(), SIMPLE_BUILDSCRIPT);

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("--stacktrace", "--", "packagePatchedMc");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();
        BuildResult secondResult = runner.build();
        Assertions.assertArrayEquals(
                new BuildTask[] {},
                secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0]),
                Arrays.toString(secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0])));
    }

    @Test
    void canSetupWithoutForge() throws IOException {
        writeString(getSettingsFile(), SIMPLE_SETTINGS);
        String buildscript = """
                plugins {
                    id('com.gtnewhorizons.retrofuturagradle')
                }
                minecraft {
                    mcVersion = '1.12.2'
                    usesForge = false
                }
                """;
        writeString(getBuildFile(), buildscript);

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("--stacktrace", "--", "packagePatchedMc");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();
        BuildResult secondResult = runner.build();
        Assertions.assertArrayEquals(
                new BuildTask[] {},
                secondResult.tasks(TaskOutcome.SUCCESS).toArray(new BuildTask[0]));

        // Check for the absence of net.minecraftforge packages except net.minecraftforge.fml.relauncher
        try (final ZipFile jar = new ZipFile(new File(getLocalCacheDir(), "mcp_patched_ated_minecraft-sources.jar"))) {
            Assertions.assertEquals(
                    0,
                    jar.stream().filter(ze -> ze.getName().startsWith("net/minecraftforge"))
                            .filter(ze -> !ze.getName().startsWith("net/minecraftforge/fml/relauncher")).count());
        }
    }

    private void writeString(File file, String string) throws IOException {
        try (Writer writer = new FileWriter(file)) {
            writer.write(string);
        }
    }
}
