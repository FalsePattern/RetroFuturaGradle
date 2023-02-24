package com.gtnewhorizons.retrofuturagradle.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import com.google.common.base.Joiner;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.mcp.RemapSourceJarTask;
import com.gtnewhorizons.retrofuturagradle.util.patching.ContextualPatch;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

public final class Utilities {

    public static final Gson GSON;

    static {
        GsonBuilder builder = new GsonBuilder();
        builder.enableComplexMapKeySerialization();
        builder.setPrettyPrinting();
        GSON = builder.create();
    }

    /**
     * A workaround for https://github.com/gradle/gradle/issues/6072
     */
    public static String fixWindowsProcessCmdline(String cmdlineArg) {
        if (SystemUtils.IS_OS_WINDOWS) {
            return cmdlineArg.replace("\"", "\\\"");
        } else {
            return cmdlineArg;
        }
    }

    public static File getRawCacheRoot(Project project) {
        return getRawCacheRoot(project.getGradle());
    }

    public static File getRawCacheRoot(Gradle gradle) {
        return FileUtils.getFile(gradle.getGradleUserHomeDir(), "caches");
    }

    public static File getCacheRoot(Gradle gradle) {
        return FileUtils.getFile(getRawCacheRoot(gradle), "retro_futura_gradle");
    }

    public static File getCacheRoot(Project project) {
        return getCacheRoot(project.getGradle());
    }

    public static File getRawCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getRawCacheRoot(project), paths);
    }

    public static File getCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getCacheRoot(project), paths);
    }

    public static CSVReader createCsvReader(URL url) throws IOException {
        final CSVParser csvParser = new CSVParserBuilder().withEscapeChar(CSVParser.NULL_CHARACTER)
                .withStrictQuotes(false).build();
        final String content = IOUtils.toString(url, StandardCharsets.UTF_8);
        return new CSVReaderBuilder(new StringReader(content)).withSkipLines(1).withCSVParser(csvParser).build();
    }

    public static CSVReader createCsvReader(File file) throws IOException {
        final CSVParser csvParser = new CSVParserBuilder().withEscapeChar(CSVParser.NULL_CHARACTER)
                .withStrictQuotes(false).build();
        return new CSVReaderBuilder(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)).withSkipLines(1)
                .withCSVParser(csvParser).build();
    }

    public static byte[] readZipEntry(ZipFile jar, ZipEntry entry) throws IOException {
        try (InputStream zis = jar.getInputStream(entry)) {
            return IOUtils.toByteArray(zis);
        }
    }

    public static byte[] getClassBytes(Class<?> klass) {
        final String resourcePath = String.format("/%s.class", klass.getName().replace('.', '/'));
        try (InputStream cis = klass.getResourceAsStream(resourcePath)) {
            return IOUtils.toByteArray(cis);
        } catch (IOException exc) {
            throw new RuntimeException(
                    "IO Exception caught when trying to get the class bytes for " + klass.getName(),
                    exc);
        }
    }

    public static ClassNode parseClassBytes(byte[] bytes, String debugName) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            return classNode;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse bytes of class " + debugName, e);
        }
    }

    public static byte[] emitClassBytes(ClassNode node, int writerFlags) {
        ClassWriter writer = new ClassWriter(writerFlags);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static String readEmbeddedResourceText(String path) {
        ClassLoader loader = Utilities.class.getClassLoader();
        assert loader != null;
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new FileNotFoundException("Resource not found: " + path);
            }
            return IOUtils.toString(stream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't read resource " + path, e);
        }
    }

    /**
     * Load a JAR file into in-memory hashmaps
     * 
     * @param jar             The JAR to load
     * @param loadedResources The map to populate with non-java file contents
     * @param loadedSources   The map to populate with java file contents
     * @throws IOException Forwarded IO errors from the JAR reading process
     */
    public static void loadMemoryJar(File jar, Map<String, byte[]> loadedResources, Map<String, String> loadedSources)
            throws IOException {
        try (final FileInputStream fis = new FileInputStream(jar);
                final BufferedInputStream bis = new BufferedInputStream(fis);
                final ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().contains("META-INF")) {
                    continue;
                }
                if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                    loadedResources.put(entry.getName(), IOUtils.toByteArray(zis));
                } else {
                    final String src = IOUtils.toString(zis, StandardCharsets.UTF_8);
                    loadedSources.put(entry.getName(), src);
                }
            }
        }
    }

    public static File saveMemoryJar(Map<String, byte[]> loadedResources, Map<String, String> loadedSources,
            File target, boolean isTemporary) throws IOException {
        if (isTemporary && !Constants.DEBUG_NO_TMP_CLEANUP) {
            return null;
        }
        try (FileOutputStream fos = new FileOutputStream(target);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> resource : loadedResources.entrySet()) {
                zos.putNextEntry(new ZipEntry(resource.getKey()));
                zos.write(resource.getValue());
                zos.closeEntry();
            }
            for (Map.Entry<String, String> srcFile : loadedSources.entrySet()) {
                zos.putNextEntry(new ZipEntry(srcFile.getKey()));
                zos.write(srcFile.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return target;
    }

    public static URL[] filesToURLArray(Collection<File> cpFiles) throws MalformedURLException {
        URL[] urls = new URL[cpFiles.size()];
        int idx = 0;
        for (File f : cpFiles) {
            urls[idx] = f.toURI().toURL();
            idx++;
        }
        if (idx != urls.length) {
            throw new IllegalStateException("Mismatched collection size on iteration and size() call");
        }
        return urls;
    }

    /**
     * Provides patching context from an in-memory jar
     */
    public static class InMemoryJarContextProvider implements ContextualPatch.IContextProvider {

        private Map<String, String> fileMap;

        private final int stripFrontComponents;

        public InMemoryJarContextProvider(Map<String, String> fileMap, int stripFrontComponents) {
            this.fileMap = fileMap;
            this.stripFrontComponents = stripFrontComponents;
        }

        public String strip(String target) {
            target = target.replace('\\', '/');
            int index = 0;
            for (int x = 0; x < stripFrontComponents; x++) {
                index = target.indexOf('/', index) + 1;
            }
            return target.substring(index);
        }

        @Override
        public List<String> getData(String target) {
            target = strip(target);

            if (fileMap.containsKey(target)) {
                String[] lines = fileMap.get(target).split("\r\n|\r|\n");
                return new ArrayList<>(Arrays.asList(lines));
            }

            return null;
        }

        @Override
        public void setData(String target, List<String> data) {
            fileMap.put(strip(target), Joiner.on(System.lineSeparator()).join(data));
        }
    }

    public static final class Mapping {

        public final String name;
        public final String javadoc;

        public Mapping(String name, String javadoc) {
            if (name == null) {
                throw new IllegalArgumentException("Null mapping name passed");
            }
            if (javadoc == null) {
                javadoc = "";
            }
            this.name = name;
            this.javadoc = javadoc;
        }
    }

    public static final class GenericMapping {

        public final String zipEntry;
        public final String param;
        public final String suffix;
        public final String type;
        public int uses = 0;

        public GenericMapping(String zipEntry, String param, String suffix, String type) {
            this.zipEntry = zipEntry;
            this.param = Objects.requireNonNull(param);
            this.suffix = Objects.requireNonNull(suffix);
            this.type = type;
        }

        @Override
        public String toString() {
            return "GenericMapping{" + "zipEntry='"
                    + zipEntry
                    + '\''
                    + ", param='"
                    + param
                    + '\''
                    + ", suffix='"
                    + suffix
                    + '\''
                    + ", type='"
                    + type
                    + '\''
                    + '}';
        }
    }

    public static final class GenericPatch {

        public final String zipEntry;
        public final String containsFilter;
        public final String toReplace;
        public final String replaceWith;

        public GenericPatch(String zipEntry, String containsFilter, String toReplace, String replaceWith) {
            this.zipEntry = zipEntry;
            this.containsFilter = containsFilter;
            this.toReplace = toReplace;
            this.replaceWith = replaceWith;
        }

        @Override
        public String toString() {
            return "GenericPatch{" + "zipEntry='"
                    + zipEntry
                    + '\''
                    + ", containsFilter='"
                    + containsFilter
                    + '\''
                    + ", toReplace='"
                    + toReplace
                    + '\''
                    + ", replaceWith='"
                    + replaceWith
                    + '\''
                    + '}';
        }
    }

    public static class MappingsSet {

        public final Map<String, Utilities.Mapping> methodMappings = new HashMap<>();
        public final Map<String, Utilities.Mapping> fieldMappings = new HashMap<>();
        public final Map<String, String> paramMappings = new HashMap<>();
        // srg name -> mapping
        public final ListMultimap<String, GenericMapping> genericMappings = MultimapBuilder.hashKeys().arrayListValues()
                .build();
        // zip entry -> patch list
        public final ListMultimap<String, Utilities.GenericPatch> genericPatches = MultimapBuilder.hashKeys()
                .arrayListValues().build();

        public String remapSimpleName(String name) {
            if (StringUtils.isBlank(name)) {
                return "";
            } else if (name.startsWith("field_")) {
                Mapping map = fieldMappings.get(name);
                return map == null ? name : map.name;
            } else if (name.startsWith("func_")) {
                Mapping map = methodMappings.get(name);
                return map == null ? name : map.name;
            } else if (name.startsWith("p_")) {
                return paramMappings.getOrDefault(name, name);
            } else {
                return name;
            }
        }
    }

    public static MappingsSet loadMappingCsvs(File methodsCsv, File fieldsCsv, File paramsCsv,
            @Nullable Collection<File> extraParamsCsvs, @Nullable String genericsFilename) {
        try {
            MappingsSet mappings = new MappingsSet();
            try (CSVReader methodReader = Utilities.createCsvReader(methodsCsv)) {
                for (String[] csvLine : methodReader) {
                    // func_100012_b,setPotionDurationMax,0,Toggle the isPotionDurationMax field.
                    mappings.methodMappings.put(csvLine[0], new Utilities.Mapping(csvLine[1], csvLine[3]));
                }
            }
            try (CSVReader fieldReader = Utilities.createCsvReader(fieldsCsv)) {
                for (String[] csvLine : fieldReader) {
                    // field_100013_f,isPotionDurationMax,0,"True if potion effect duration is at maximum, false
                    // otherwise."
                    mappings.fieldMappings.put(csvLine[0], new Utilities.Mapping(csvLine[1], csvLine[3]));
                }
            }
            try (CSVReader paramReader = Utilities.createCsvReader(paramsCsv)) {
                for (String[] csvLine : paramReader) {
                    // p_104055_1_,force,1
                    mappings.paramMappings.put(csvLine[0], csvLine[1]);
                }
            }
            if (extraParamsCsvs != null && !extraParamsCsvs.isEmpty()) {
                for (File extraParamsCsv : extraParamsCsvs) {
                    try (CSVReader paramReader = Utilities.createCsvReader(extraParamsCsv)) {
                        for (String[] csvLine : paramReader) {
                            mappings.paramMappings.put(csvLine[0], csvLine[1]);
                        }
                    }
                }
            }
            if (StringUtils.isNotBlank(genericsFilename)) {
                URL genericsUrl = RemapSourceJarTask.class.getResource(genericsFilename);
                URL genericPatchesUrl = RemapSourceJarTask.class
                        .getResource(genericsFilename.replace("Fields", "Patches"));
                try (CSVReader genReader = Utilities.createCsvReader(genericsUrl)) {
                    for (String[] genLine : genReader) {
                        // zipEntry, className, srg, mcp, param, type, suffix, comment
                        String srg = genLine[2];
                        int colon = srg.indexOf(':');
                        if (colon >= 0) {
                            srg = srg.substring(0, colon);
                        }
                        final String zipEntry = genLine[0];
                        final String param = genLine[4];
                        final String type = genLine[5];
                        final String suffix = genLine[6];
                        final String key = srg.equals("@init") ? (zipEntry + genLine[2]) : srg;
                        mappings.genericMappings.put(key, new Utilities.GenericMapping(zipEntry, param, suffix, type));
                    }
                }
                try (CSVReader genReader = Utilities.createCsvReader(genericPatchesUrl)) {
                    for (String[] genLine : genReader) {
                        // zipEntry, className, containsFilter, toReplace, replaceWith, reason
                        mappings.genericPatches.put(
                                genLine[0],
                                new Utilities.GenericPatch(genLine[0], genLine[2], genLine[3], genLine[4]));
                    }
                }
            }
            return mappings;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static UUID resolveUUID(String username, Gradle gradle) {
        final boolean isOffline = gradle.getStartParameter().isOffline();
        final File cacheFile = new File(Utilities.getCacheRoot(gradle), "auth_uuid_cache.properties");
        final Properties cacheProps = new Properties();
        if (cacheFile.isFile()) {
            try (InputStream fis = FileUtils.openInputStream(cacheFile)) {
                cacheProps.load(fis);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (cacheProps.containsKey(username)) {
            return UUID.fromString(cacheProps.getProperty(username));
        }
        UUID onlineUUID = null;
        if (!isOffline) {
            try {
                URL url = new URL(
                        Constants.URL_PLAYER_TO_UUID + URLEncoder.encode(username, StandardCharsets.UTF_8.name()));
                final String json = IOUtils.toString(url, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(json);
                if (root.isJsonObject()) {
                    JsonObject rootObj = root.getAsJsonObject();
                    if (rootObj.has("id")) {
                        String encid = rootObj.get("id").getAsString();
                        String dashed = encid.replaceFirst(
                                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                                "$1-$2-$3-$4-$5");
                        onlineUUID = UUID.fromString(dashed);
                        cacheProps.setProperty(username, onlineUUID.toString());
                        try (OutputStream fos = FileUtils.openOutputStream(cacheFile);
                                BufferedOutputStream bos = IOUtils.buffer(fos)) {
                            cacheProps.store(bos, "Cache of username->UUID mappings from Mojang servers");
                        }
                    }
                }
            } catch (IOException e) {
                // no-op
            }
        }
        if (onlineUUID != null) {
            return onlineUUID;
        }
        // Fallback if no cached UUID nor internet, this is wrong but at least deterministic
        return UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8));
    }
}
