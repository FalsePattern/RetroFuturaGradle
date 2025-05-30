package com.gtnewhorizons.retrofuturagradle.minecraft;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ComponentMetadataSupplier;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.IMinecraftyExtension;
import com.gtnewhorizons.retrofuturagradle.util.Distribution;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

import de.undercouch.gradle.tasks.download.Download;
import de.undercouch.gradle.tasks.download.DownloadExtension;

/**
 * Registers vanilla Minecraft-related gradle tasks
 */
public final class MinecraftTasks {

    public static final String MC_DOWNLOAD_PATH = "mc-vanilla";
    private static final String TASK_GROUP_INTERNAL = "Internal Vanilla Minecraft";
    private static final String TASK_GROUP_USER = "Vanilla Minecraft";
    private final Project project;
    private final IMinecraftyExtension mcExt;

    private final File allVersionsManifestLocation;

    private final Provider<RegularFile> assetManifestLocation;
    private final TaskProvider<Download> taskDownloadAssetManifest;

    private final Provider<RegularFile> vanillaClientLocation, vanillaServerLocation;
    private final TaskProvider<Download> taskDownloadVanillaJars;

    /**
     * The assets root (contains the indexes and objects directories)
     */
    private final File vanillaAssetsLocation;

    private final TaskProvider<DownloadAssetsTask> taskDownloadVanillaAssets;

    private final TaskProvider<DefaultTask> taskCleanVanillaAssets;

    private final TaskProvider<ExtractNativesTask> taskExtractNatives2, taskExtractNatives3;
    private final TaskProvider<RunMinecraftTask> taskRunVanillaClient;
    private final TaskProvider<RunMinecraftTask> taskRunVanillaServer;

    private final File runDirectory;
    private final File natives2Directory, natives3Directory;
    private final Configuration vanillaMcConfiguration;
    private final Configuration lwjgl2Configuration;
    private final Configuration lwjgl3Configuration;

    private static LauncherManifest getLauncherManifest(File cacheDir, File allVersionsManifestLocation,
            String mcVersion, DownloadExtension download) {
        final File location = new File(cacheDir, "manifest_" + mcVersion + ".json");
        if (!location.exists()) {
            final String allVersionsManifestJson;
            try {
                allVersionsManifestJson = FileUtils
                        .readFileToString(allVersionsManifestLocation, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            final String url = LauncherManifest.getVersionManifestUrl(allVersionsManifestJson, mcVersion);
            download.run(task -> {
                try {
                    task.src(url);
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
                task.overwrite(false);
                task.onlyIfModified(true);
                task.dest(location);
            });
        }
        return LauncherManifest.read(location);
    }

    @Deprecated
    public MinecraftTasks(Project project, IMinecraftyExtension mcExt) {
        this(project, mcExt, null);
    }

    public MinecraftTasks(Project project, IMinecraftyExtension mcExt, String registeredName) {
        this.project = project;
        this.mcExt = mcExt;
        if (registeredName != null) {
            project.getExtensions().add(MinecraftTasks.class, registeredName, this);
        }
        final DownloadExtension download = new DownloadExtension(project);
        allVersionsManifestLocation = Utilities.getCacheDir(project, MC_DOWNLOAD_PATH, "all_versions_manifest.json");
        final File allVersionsManifest = allVersionsManifestLocation;
        final File manifestCacheDir = Utilities.getCacheDir(project, MC_DOWNLOAD_PATH);

        if (!allVersionsManifestLocation.exists()) {
            project.getLogger().info("Downloading the all versions manifest: {}", allVersionsManifestLocation);
            download.run(task -> {
                try {
                    task.src(Constants.URL_LAUNCHER_VERSION_MANIFEST);
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
                task.overwrite(false);
                task.onlyIfModified(true);
                task.dest(allVersionsManifestLocation);
            });
        }

        assetManifestLocation = project.getLayout().file(
                mcExt.getMcVersion()
                        .map(mcVer -> Utilities.getCacheDir(project, "assets", "indexes", mcVer + ".json")));
        final ProviderFactory providers = mcExt.getProviderFactory();
        taskDownloadAssetManifest = project.getTasks().register("downloadAssetManifest", Download.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            final Property<String> mcVersion = mcExt.getMcVersion();
            task.src(
                    mcVersion.map(
                            ver -> getLauncherManifest(manifestCacheDir, allVersionsManifest, ver, download)
                                    .getAssetIndexUrl()));
            final Provider<RegularFile> assetManifest = assetManifestLocation;
            task.onlyIf(t -> !assetManifest.get().getAsFile().exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(assetManifest);
            task.doLast("parseAssetManifestJson", (_t) -> {
                final LauncherManifest manifest = getLauncherManifest(
                        manifestCacheDir,
                        allVersionsManifest,
                        mcVersion.get(),
                        null);
                final byte[] assetManifestJsonRaw;
                try {
                    assetManifestJsonRaw = FileUtils.readFileToByteArray(assetManifest.get().getAsFile());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                final String realSha1 = new DigestUtils(DigestUtils.getSha1Digest()).digestAsHex(assetManifestJsonRaw);
                if (!realSha1.equals(manifest.getAssetIndexSha1())) {
                    throw new RuntimeException(
                            "Mismached assets index sha1 sums: " + realSha1 + " != " + manifest.getAssetIndexSha1());
                }
            });
        });

        vanillaClientLocation = project.getLayout().file(
                mcExt.getMcVersion()
                        .map(mcVer -> Utilities.getCacheDir(project, MC_DOWNLOAD_PATH, mcVer, "client.jar")));
        vanillaServerLocation = project.getLayout().file(
                mcExt.getMcVersion()
                        .map(mcVer -> Utilities.getCacheDir(project, MC_DOWNLOAD_PATH, mcVer, "server.jar")));
        taskDownloadVanillaJars = project.getTasks().register("downloadVanillaJars", Download.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            final Provider<RegularFile> vanillaClient = vanillaClientLocation;
            final Provider<RegularFile> vanillaServer = vanillaServerLocation;
            final Property<String> mcVersion = mcExt.getMcVersion();
            task.doFirst((_t) -> { vanillaClient.get().getAsFile().getParentFile().mkdirs(); });
            task.src(mcVersion.map(ver -> {
                final LauncherManifest manifest = getLauncherManifest(
                        manifestCacheDir,
                        allVersionsManifest,
                        ver,
                        download);
                return new String[] { manifest.getClientUrl(), manifest.getServerUrl() };
            }));
            task.onlyIf(t -> !vanillaClient.get().getAsFile().exists() || !vanillaServer.get().getAsFile().exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(vanillaClient.map(f -> f.getAsFile().getParentFile()));
            task.doLast("verifyVanillaJars", (_t) -> {
                final LauncherManifest manifest = getLauncherManifest(
                        manifestCacheDir,
                        allVersionsManifest,
                        mcVersion.get(),
                        null);
                final String realClientSha1, realServerSha1;
                try {
                    realClientSha1 = new DigestUtils(DigestUtils.getSha1Digest())
                            .digestAsHex(vanillaClient.get().getAsFile());
                    realServerSha1 = new DigestUtils(DigestUtils.getSha1Digest())
                            .digestAsHex(vanillaServer.get().getAsFile());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (!realClientSha1.equals(manifest.getClientSha1())) {
                    throw new RuntimeException(
                            "Mismached client.jar sha1 sums: " + realClientSha1 + " != " + manifest.getClientSha1());
                }
                if (!realServerSha1.equals(manifest.getServerSha1())) {
                    throw new RuntimeException(
                            "Mismached server.jar sha1 sums: " + realServerSha1 + " != " + manifest.getServerSha1());
                }
            });
        });

        vanillaAssetsLocation = Utilities.getCacheDir(project, "assets");
        taskDownloadVanillaAssets = project.getTasks()
                .register("downloadVanillaAssets", DownloadAssetsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDownloadAssetManifest);
                    task.getManifest().set(assetManifestLocation);
                    task.getObjectsDir().set(new File(vanillaAssetsLocation, "objects"));
                });

        taskCleanVanillaAssets = project.getTasks().register("cleanVanillaAssets", DefaultTask.class, task -> {
            task.setDescription("Removes the cached game assets from your gradle cache");
            task.setGroup(TASK_GROUP_USER);
            task.doLast("cleanVanillaAssetFolders", (_t) -> {
                System.out.println("Cleaning asset folders at " + vanillaAssetsLocation.getAbsolutePath());
                try {
                    FileUtils.deleteDirectory(vanillaAssetsLocation);
                } catch (IOException e) {
                    System.out.println("Couldn't delete assets: " + e.toString());
                }
            });
        });

        runDirectory = new File(project.getProjectDir(), "run");
        natives2Directory = FileUtils.getFile(runDirectory, "natives", "lwjgl2");
        natives3Directory = FileUtils.getFile(runDirectory, "natives", "lwjgl3");

        this.vanillaMcConfiguration = project.getConfigurations().create("vanilla_minecraft");
        this.vanillaMcConfiguration.setCanBeConsumed(false);
        this.lwjgl2Configuration = project.getConfigurations().create("lwjgl2Classpath");
        this.lwjgl2Configuration.setCanBeConsumed(false);
        this.lwjgl3Configuration = project.getConfigurations().create("lwjgl3Classpath");
        this.lwjgl3Configuration.setCanBeConsumed(false);
        applyMcDependencies();

        taskExtractNatives2 = project.getTasks().register("extractNatives2", ExtractNativesTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.configureMe(project, natives2Directory, lwjgl2Configuration, vanillaMcConfiguration);
        });

        taskExtractNatives3 = project.getTasks().register("extractNatives3", ExtractNativesTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.configureMe(project, natives3Directory, lwjgl3Configuration, vanillaMcConfiguration);
        });

        taskRunVanillaClient = project.getTasks()
                .register("runVanillaClient", RunMinecraftTask.class, Distribution.CLIENT);
        taskRunVanillaClient.configure(task -> {
            task.setup(project);
            task.setDescription("Runs the vanilla (unmodified) game client, use --debug-jvm for debugging");
            task.setGroup(TASK_GROUP_USER);
            task.dependsOn(taskDownloadVanillaJars, taskDownloadVanillaAssets);

            task.getUsername().set(mcExt.getUsername());
            task.getUserUUID().set(mcExt.getUserUUID());
            task.getLwjglVersion().set(2);
            task.classpath(vanillaClientLocation);
            task.classpath(vanillaMcConfiguration);
            task.classpath(lwjgl2Configuration);
            task.getMainClass().set("net.minecraft.client.main.Main");
        });
        taskRunVanillaServer = project.getTasks()
                .register("runVanillaServer", RunMinecraftTask.class, Distribution.DEDICATED_SERVER);
        taskRunVanillaServer.configure(task -> {
            task.setup(project);
            task.setDescription("Runs the vanilla (unmodified) game server, use --debug-jvm for debugging");
            task.setGroup(TASK_GROUP_USER);
            task.dependsOn(taskDownloadVanillaJars);

            task.getUsername().set(mcExt.getUsername());
            task.getUserUUID().set(mcExt.getUserUUID());
            task.getLwjglVersion().set(2);
            task.classpath(vanillaServerLocation);
            task.getMainClass().set("net.minecraft.server.MinecraftServer");
        });
    }

    private void applyMcDependencies() {
        final RepositoryHandler repos = project.getRepositories();
        final ObjectFactory objects = project.getObjects();
        repos.maven(mvn -> {
            mvn.setName("mojang");
            mvn.setUrl(Constants.URL_MOJANG_LIBRARIES_MAVEN);
            mvn.mavenContent(content -> {
                content.includeGroup("ca.weblite");
                content.includeGroup("com.ibm.icu");
                content.includeGroup("com.mojang");
                content.includeGroup("com.paulscode");
                content.includeGroup("java3d");
                content.includeGroup("net.minecraft");
                content.includeGroup("org.lwjgl.lwjgl");
                content.includeGroup("oshi-project");
                content.includeGroup("tv.twitch");
            });
        });
        repos.maven(mvn -> {
            mvn.setName("forge");
            mvn.setUrl(Constants.URL_FORGE_MAVEN);
            mvn.mavenContent(content -> {
                content.includeGroup("cpw.mods");
                content.includeGroup("de.oceanlabs.mcp");
                content.includeGroup("net.minecraftforge");
                content.includeGroup("org.scala-lang");
            });
            // Allow pom-less artifacts (e.g. MCP data zips)
            mvn.getMetadataSources().artifact();
        });
        repos.maven(mvn -> {
            mvn.setName("sponge");
            mvn.setUrl(Constants.URL_SPONGEPOWERED_MAVEN);
            mvn.mavenContent(content -> { content.includeGroup("lzma"); });
            // Allow pom-less artifacts (e.g. MCP data zips)
            mvn.getMetadataSources().artifact();
        });
        repos.exclusiveContent(ec -> {
            ec.forRepository(() -> repos.ivy(ivy -> {
                ivy.setName("lwjgl2-osx-arm64-natives");
                ivy.artifactPattern(
                        "https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar");
                ivy.ivyPattern(
                        "https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.xml");
                ivy.getMetadataSources().artifact();
                ivy.setMetadataSupplier(((ComponentMetadataSupplier) cmsd -> {
                    AttributeContainer attrs = cmsd.getResult().getAttributes();
                    attrs.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
                    attrs.attribute(
                            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(LibraryElements.class, LibraryElements.JAR));
                    attrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_API));
                }).getClass());
            }));
            ec.filter(content -> { content.includeModule("org.lwjgl.lwjgl", "lwjgl-platform-natives-osx-arm64"); });
        });
        repos.exclusiveContent(ec -> {
            ec.forRepository(() -> repos.ivy(ivy -> {
                ivy.setName("jinput-osx-arm64-natives");
                ivy.artifactPattern(
                        "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar");
                ivy.ivyPattern(
                        "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.xml");
                ivy.getMetadataSources().artifact();
                ivy.setMetadataSupplier(((ComponentMetadataSupplier) cmsd -> {
                    AttributeContainer attrs = cmsd.getResult().getAttributes();
                    attrs.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
                    attrs.attribute(
                            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(LibraryElements.class, LibraryElements.JAR));
                    attrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_API));
                }).getClass());
            }));
            ec.filter(content -> { content.includeModule("net.java.jinput", "jinput-platform-natives-osx-arm64"); });
        });
        // Log4shell fix
        repos.exclusiveContent(ec -> {
            ec.forRepository(() -> repos.maven(maven -> {
                maven.setName("log4j2beta9fixed");
                maven.setUrl("https://files.prismlauncher.org/maven/");
                maven.getMetadataSources().artifact();
            }));
            ec.filter(content -> {
                content.includeVersion("org.apache.logging.log4j", "log4j-core", "2.0-beta9-fixed");
                content.includeVersion("org.apache.logging.log4j", "log4j-api", "2.0-beta9-fixed");
            });
        });
        project.getConfigurations().all(cfg -> {
            final DependencySubstitutions sub = cfg.getResolutionStrategy().getDependencySubstitution();
            sub.substitute(sub.module("org.apache.logging.log4j:log4j-core:2.0-beta9")).withoutClassifier()
                    .using(sub.module("org.apache.logging.log4j:log4j-core:2.0-beta9-fixed"))
                    .because("To fix log4shell");
            sub.substitute(sub.module("org.apache.logging.log4j:log4j-api:2.0-beta9")).withoutClassifier()
                    .using(sub.module("org.apache.logging.log4j:log4j-api:2.0-beta9-fixed"))
                    .because("To fix log4shell");
        });

        repos.mavenCentral().mavenContent(content -> {
            content.excludeGroup("com.mojang");
            content.excludeGroup("net.minecraftforge");
            content.excludeGroup("de.oceanlabs.mcp");
            content.excludeGroup("cpw.mods");
        });

        final String osArch = System.getProperty("os.arch");
        final String lwjgl2Natives;
        if (SystemUtils.IS_OS_WINDOWS) {
            lwjgl2Natives = "natives-windows";
        } else if (SystemUtils.IS_OS_MAC) {
            lwjgl2Natives = (osArch.startsWith("aarch64")) ? "natives-osx-arm64" : "natives-osx";
        } else {
            lwjgl2Natives = "natives-linux";
        }

        final String lwjgl3Natives;
        if (SystemUtils.IS_OS_MAC) {
            lwjgl3Natives = (osArch.startsWith("aarch64")) ? "natives-macos-arm64" : "natives-macos";
        } else if (SystemUtils.IS_OS_WINDOWS) {
            if (osArch.contains("64")) {
                lwjgl3Natives = osArch.startsWith("aarch64") ? "natives-windows-arm64" : "natives-windows";
            } else {
                lwjgl3Natives = "natives-windows-x86";
            }
        } else if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_UNIX) {
            if (osArch.startsWith("arm") || osArch.startsWith("aarch64")) {
                lwjgl3Natives = (osArch.contains("64") || osArch.startsWith("armv8")) ? "natives-linux-arm64"
                        : "natives-linux-arm32";
            } else {
                lwjgl3Natives = "natives-linux";
            }
        } else {
            throw new UnsupportedOperationException("Operating system not supported by lwjgl");
        }

        project.getExtensions().add("lwjglNatives", lwjgl2Natives); // deprecated
        project.getExtensions().add("lwjgl2Natives", lwjgl2Natives);
        project.getExtensions().add("lwjgl3Natives", lwjgl3Natives);

        project.afterEvaluate(_p -> {
            // At afterEvaluate minecraft version should be already set and stable
            final String minecraftVersion = mcExt.getMcVersion().get();
            final int mcMinor = mcExt.getMinorMcVersion().get();
            if (mcExt.getApplyMcDependencies().get()) {
                String lwjgl2Version = mcExt.getLwjgl2Version().get();
                final String lwjgl3Version = mcExt.getLwjgl3Version().get();
                if (lwjgl2Version.startsWith("3.")) {
                    lwjgl2Version = "2.9.4-nightly-20150209";
                    project.getLogger().warn("LWJGL 3 configuration has been split, update your minecraft block");
                }

                final String VANILLA_MC_CFG = vanillaMcConfiguration.getName();
                final DependencyHandler deps = project.getDependencies();

                // paulscode libraries depend on lwjgl only
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:codecjorbis:20101023"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:codecwav:20101023"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:libraryjavasound:20101123"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:librarylwjglopenal:20100824"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:soundsystem:20120107"))
                        .setTransitive(false);

                switch (minecraftVersion) {
                    case "1.7.10" -> {
                        deps.add(VANILLA_MC_CFG, "com.mojang:netty:1.8.8");
                        deps.add(VANILLA_MC_CFG, "com.mojang:realms:1.3.5");
                        deps.add(VANILLA_MC_CFG, "org.apache.commons:commons-compress:1.8.1");
                        deps.add(VANILLA_MC_CFG, "org.apache.httpcomponents:httpclient:4.3.3");
                        deps.add(VANILLA_MC_CFG, "commons-logging:commons-logging:1.1.3");
                        deps.add(VANILLA_MC_CFG, "org.apache.httpcomponents:httpcore:4.3.2");
                        deps.add(VANILLA_MC_CFG, "java3d:vecmath:1.3.1");
                        deps.add(VANILLA_MC_CFG, "net.sf.trove4j:trove4j:3.0.3");
                        deps.add(VANILLA_MC_CFG, "com.ibm.icu:icu4j-core-mojang:51.2");
                        deps.add(VANILLA_MC_CFG, "net.sf.jopt-simple:jopt-simple:4.5");
                        deps.add(VANILLA_MC_CFG, "io.netty:netty-all:4.0.10.Final");
                        deps.add(VANILLA_MC_CFG, "com.google.guava:guava:17.0");
                        deps.add(VANILLA_MC_CFG, "org.apache.commons:commons-lang3:3.1");
                        deps.add(VANILLA_MC_CFG, "commons-io:commons-io:2.4");
                        deps.add(VANILLA_MC_CFG, "commons-codec:commons-codec:1.9");
                        deps.add(VANILLA_MC_CFG, "net.java.jinput:jinput:2.0.5");
                        deps.add(VANILLA_MC_CFG, "net.java.jutils:jutils:1.0.0");
                        deps.add(VANILLA_MC_CFG, "com.google.code.gson:gson:2.2.4");
                        deps.add(VANILLA_MC_CFG, "com.mojang:authlib:1.5.21");
                        deps.add(VANILLA_MC_CFG, "org.apache.logging.log4j:log4j-api:2.0-beta9");
                        deps.add(VANILLA_MC_CFG, "org.apache.logging.log4j:log4j-core:2.0-beta9");
                    }
                    case "1.12.2" -> {
                        deps.add(VANILLA_MC_CFG, "com.mojang:patchy:1.3.9");
                        deps.add(VANILLA_MC_CFG, "oshi-project:oshi-core:1.1");
                        deps.add(VANILLA_MC_CFG, "net.java.dev.jna:jna:4.4.0");
                        deps.add(VANILLA_MC_CFG, "net.java.dev.jna:platform:3.4.0");
                        deps.add(VANILLA_MC_CFG, "com.ibm.icu:icu4j-core-mojang:51.2");
                        deps.add(VANILLA_MC_CFG, "net.sf.jopt-simple:jopt-simple:5.0.3");
                        deps.add(VANILLA_MC_CFG, "io.netty:netty-all:4.1.9.Final");
                        deps.add(VANILLA_MC_CFG, "com.google.guava:guava:21.0");
                        deps.add(VANILLA_MC_CFG, "org.apache.commons:commons-lang3:3.5");
                        deps.add(VANILLA_MC_CFG, "commons-io:commons-io:2.5");
                        deps.add(VANILLA_MC_CFG, "commons-codec:commons-codec:1.10");
                        deps.add(VANILLA_MC_CFG, "com.google.code.gson:gson:2.8.0");
                        deps.add(VANILLA_MC_CFG, "com.mojang:authlib:1.5.25");
                        deps.add(VANILLA_MC_CFG, "com.mojang:realms:1.10.22");
                        deps.add(VANILLA_MC_CFG, "org.apache.commons:commons-compress:1.8.1");
                        deps.add(VANILLA_MC_CFG, "org.apache.httpcomponents:httpclient:4.3.3");
                        deps.add(VANILLA_MC_CFG, "commons-logging:commons-logging:1.1.3");
                        deps.add(VANILLA_MC_CFG, "org.apache.httpcomponents:httpcore:4.3.2");
                        deps.add(VANILLA_MC_CFG, "it.unimi.dsi:fastutil:7.1.0");
                        deps.add(VANILLA_MC_CFG, "org.apache.logging.log4j:log4j-api:2.17.1");
                        deps.add(VANILLA_MC_CFG, "org.apache.logging.log4j:log4j-core:2.17.1");
                        deps.add(VANILLA_MC_CFG, "com.mojang:text2speech:1.10.3");
                        if (SystemUtils.IS_OS_WINDOWS) {
                            deps.add(VANILLA_MC_CFG, "com.mojang:text2speech:1.10.3:natives-windows");
                        } else if (SystemUtils.IS_OS_LINUX) {
                            deps.add(VANILLA_MC_CFG, "com.mojang:text2speech:1.10.3:natives-linux");
                        } else if (SystemUtils.IS_OS_MAC) {
                            deps.add(VANILLA_MC_CFG, "ca.weblite:java-objc-bridge:1.0.0");
                        }
                    }
                }

                final String LWJGL2_CFG = lwjgl2Configuration.getName();
                lwjgl2Configuration.setTransitive(false);
                deps.add(LWJGL2_CFG, "org.lwjgl.lwjgl:lwjgl:" + lwjgl2Version);
                deps.add(LWJGL2_CFG, "org.lwjgl.lwjgl:lwjgl_util:" + lwjgl2Version);
                // Hack to download arm64 macOS natives from a different repository
                if (lwjgl2Natives.equals("natives-osx-arm64")) {
                    deps.add(LWJGL2_CFG, "org.lwjgl.lwjgl:lwjgl-platform-natives-osx-arm64:" + lwjgl2Version);
                    deps.add(VANILLA_MC_CFG, "net.java.jinput:jinput-platform-natives-osx-arm64:2.0.5");
                } else {
                    deps.add(LWJGL2_CFG, "org.lwjgl.lwjgl:lwjgl-platform:" + lwjgl2Version + ":" + lwjgl2Natives);
                    deps.add(VANILLA_MC_CFG, "net.java.jinput:jinput-platform:2.0.5:" + lwjgl2Natives);
                }

                final String LWJGL3_CFG = lwjgl3Configuration.getName();
                deps.add(LWJGL3_CFG, deps.platform("org.lwjgl:lwjgl-bom:" + lwjgl3Version));
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-glfw:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-openal:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-opengl:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-jemalloc:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-stb:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-tinyfd:" + lwjgl3Version);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl:" + lwjgl3Version + ":" + lwjgl3Natives);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-glfw:" + lwjgl3Version + ":" + lwjgl3Natives);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-openal:" + lwjgl3Version + ":" + lwjgl3Natives);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-opengl:" + lwjgl3Version + ":" + lwjgl3Natives);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-jemalloc:" + lwjgl3Version + ":" + lwjgl3Natives);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-stb:" + lwjgl3Version + ":" + lwjgl3Natives);
                deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-tinyfd:" + lwjgl3Version + ":" + lwjgl3Natives);

                if (mcMinor <= 8) {
                    deps.add(VANILLA_MC_CFG, "tv.twitch:twitch:5.16");
                    if (SystemUtils.IS_OS_WINDOWS) {
                        deps.add(VANILLA_MC_CFG, "tv.twitch:twitch-platform:5.16:natives-windows-64");
                        deps.add(VANILLA_MC_CFG, "tv.twitch:twitch-external-platform:4.5:natives-windows-64");
                    }
                }
            }
        });
    }

    public File getAllVersionsManifestLocation() {
        return allVersionsManifestLocation;
    }

    public Provider<RegularFile> getAssetManifestLocation() {
        return assetManifestLocation;
    }

    public TaskProvider<Download> getTaskDownloadAssetManifest() {
        return taskDownloadAssetManifest;
    }

    public Provider<RegularFile> getVanillaClientLocation() {
        return vanillaClientLocation;
    }

    public Provider<RegularFile> getVanillaServerLocation() {
        return vanillaServerLocation;
    }

    public TaskProvider<Download> getTaskDownloadVanillaJars() {
        return taskDownloadVanillaJars;
    }

    public File getVanillaAssetsLocation() {
        return vanillaAssetsLocation;
    }

    public TaskProvider<DownloadAssetsTask> getTaskDownloadVanillaAssets() {
        return taskDownloadVanillaAssets;
    }

    public TaskProvider<DefaultTask> getTaskCleanVanillaAssets() {
        return taskCleanVanillaAssets;
    }

    public File getRunDirectory() {
        return runDirectory;
    }

    @Deprecated
    public File getNativesDirectory() {
        return natives2Directory;
    }

    public File getLwjgl2NativesDirectory() {
        return natives2Directory;
    }

    public File getLwjgl3NativesDirectory() {
        return natives3Directory;
    }

    public Configuration getVanillaMcConfiguration() {
        return vanillaMcConfiguration;
    }

    @Deprecated
    public TaskProvider<ExtractNativesTask> getTaskExtractNatives() {
        return taskExtractNatives2;
    }

    public Provider<ExtractNativesTask> getTaskExtractNatives(Provider<Integer> lwjglVersion) {
        return lwjglVersion.flatMap(ver -> {
            if (ver == 2) {
                return getTaskExtractLwjgl2Natives();
            } else if (ver == 3) {
                return getTaskExtractLwjgl3Natives();
            } else {
                throw new IllegalArgumentException("Lwjgl major version " + ver + " not supported");
            }
        });
    }

    public TaskProvider<ExtractNativesTask> getTaskExtractLwjgl2Natives() {
        return taskExtractNatives2;
    }

    public TaskProvider<ExtractNativesTask> getTaskExtractLwjgl3Natives() {
        return taskExtractNatives3;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunVanillaClient() {
        return taskRunVanillaClient;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunVanillaServer() {
        return taskRunVanillaServer;
    }

    @Deprecated
    public Configuration getLwjglCompileMcConfiguration() {
        return lwjgl2Configuration;
    }

    @Deprecated
    public Configuration getLwjglModConfiguration() {
        return lwjgl2Configuration;
    }

    public Configuration getLwjglConfiguration(int lwjglVersion) {
        if (lwjglVersion == 2) {
            return lwjgl2Configuration;
        } else if (lwjglVersion == 3) {
            return lwjgl3Configuration;
        } else {
            throw new IllegalArgumentException("Lwjgl major version " + lwjglVersion + " not supported");
        }
    }

    public Provider<Configuration> getLwjglConfiguration(Provider<Integer> lwjglVersion) {
        return lwjglVersion.map(this::getLwjglConfiguration);
    }

    public Configuration getLwjgl2Configuration() {
        return lwjgl2Configuration;
    }

    public Configuration getLwjgl3Configuration() {
        return lwjgl3Configuration;
    }
}
