package devoldefy;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.impl.MappingSetImpl;
import org.cadixdev.lorenz.impl.MappingSetModelFactoryImpl;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Devoldefy {
    private static final String CSV = "http://export.mcpbot.bspk.rs/mcp_{csv_type}_nodoc/{csv_build}-{mc_version}/mcp_{csv_type}_nodoc-{csv_build}-{mc_version}.zip";
    private static final String SRG = "https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/versions/{mc_version}/joined.tsrg";
    private static final String YARN = "http://maven.modmuss50.me/net/fabricmc/yarn/{target_minecraft_version}+build.{yarn_build}/yarn-{target_minecraft_version}+build.{yarn_build}.jar";
    private static final String FORGE_JAR = "{home_dir}/.gradle/caches/minecraft/net/minecraftforge/forge/{mc_version}-{forge_version}/{csv_type}/{csv_build}/forgeSrc-{mc_version}-{forge_version}.jar";

    public static void main(String[] args) throws Exception {
        File files = new File("files");

        String sourceMinecraftVersion = ask("Source Minecraft version", "1.13.2");
        String sourceMappingsVersion = ask("Source mappings version", "1.13.2");
        String sourceForgeVersion = ask("Source forge version", "14.23.4.2703");
        String sourceMappingsType = ask("Source mappings type", "snapshot");
        String sourceMappingsBuild = ask("Source mappings build", "20190424");

        String targetMinecraftVersion = ask("Target Minecraft version", "1.14");
        String targetYarnBuild = ask("Target Yarn build", "2");

        String sourceRoot = ask("Path to source root", "./src/main/java");
        String targetRoot = ask("Path to target root", "./updated_src/main/java");

        String csvUrl = CSV.replace("{mc_version}", sourceMappingsVersion).replace("{csv_type}", sourceMappingsType).replace("{csv_build}", sourceMappingsBuild);
        String srgUrl = SRG.replace("{mc_version}", targetMinecraftVersion);
        String yarnUrl = YARN.replace("{target_minecraft_version}", targetMinecraftVersion).replace("{yarn_build}", targetYarnBuild);
        String forgeLocation = FORGE_JAR.replace("{home_dir}", System.getProperty("user.home")).replace("{mc_version}", sourceMinecraftVersion).replace("{forge_version}", sourceForgeVersion).replace("{csv_type}", sourceMappingsType).replace("{csv_build}", sourceMappingsBuild);

        Mappings srg = readTsrg(
                new Scanner(download(srgUrl, files)),
                readCsv(new Scanner(extract(download(csvUrl, files), "fields.csv", files))),
                readCsv(new Scanner(extract(download(csvUrl, files), "methods.csv", files)))
        );

        Mappings yarn = readTiny(
                new Scanner(extract(download(yarnUrl, files), "mappings/mappings.tiny", files)),
                "official",
                "named"
        );

        Mappings mappings = srg.invert().chain(yarn);

        File sourceDir = new File(sourceRoot);
        File targetDir = new File(targetRoot);
        Files.walk(targetDir.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        targetDir.mkdirs();

        List<Path> classpath = new ArrayList<>();
        File forgeJar = new File(forgeLocation);
        if (!forgeJar.exists()) {
            throw new IllegalStateException("Forge jar not found at " + forgeJar.getCanonicalPath());
        }
        classpath.add(forgeJar.toPath());

        remap(sourceDir.toPath(), targetDir.toPath(), classpath, mappings);
    }

    private static String ask(String message, String fallback) {
        System.out.print(message + (fallback == null ? "" : " (or blank for " + fallback + ")") + ": ");
        String result = new Scanner(System.in).nextLine().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static File download(String url, File directory) throws IOException {
        directory.mkdirs();
        File file = new File(directory, hash(url));

        if (!file.exists()) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return file;
    }

    private static File extract(File zip, String path, File directory) throws IOException {
        directory.mkdirs();
        File file = new File(directory, hash(zip.getName() + path));

        try (ZipFile zipFile = new ZipFile(zip)) {
            InputStream is = null;

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (zipEntry.getName().equals(path)) {
                    is = zipFile.getInputStream(zipEntry);
                    break;
                }
            }

            if (is == null) {
                return null;
            }

            Files.copy(is, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return file;
    }

    private static String hash(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static Mappings readTsrg(Scanner s, Map<String, String> fieldNames, Map<String, String> methodNames) {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();

        String currentClassA = null;
        String currentClassB = null;
        while (s.hasNextLine()) {
            String line = s.nextLine();

            if (!line.startsWith("\t")) {
                String[] parts = line.split(" ");
                classes.put(parts[0], parts[1]);
                currentClassA = parts[0];
                currentClassB = parts[1];
                continue;
            }

            line = line.substring(1);

            String[] parts = line.split(" ");

            if (parts.length == 2) {
                fields.put(currentClassA + ":" + parts[0], currentClassB + ":" + fieldNames.getOrDefault(parts[1], parts[1]));
            } else if (parts.length == 3) {
                methods.put(currentClassA + ":" + parts[0] + parts[1], currentClassB + ":" + methodNames.getOrDefault(parts[2], parts[2]) + parts[1]);
            }
        }

        Mappings mappings = new Mappings();
        mappings.classes.putAll(classes);
        mappings.fields.putAll(fields);
        methods.forEach((a, b) -> mappings.methods.put(a, remapMethodDescriptor(b, classes)));

        s.close();
        return mappings;
    }

    private static Map<String, String> readCsv(Scanner s) {
        Map<String, String> mappings = new LinkedHashMap<>();

        try (Scanner r = s) {
            r.nextLine();
            while (r.hasNextLine()) {
                String[] parts = r.nextLine().split(",");
                mappings.put(parts[0], parts[1]);
            }
        }

        s.close();
        return mappings;
    }

    private static Mappings readTiny(Scanner s, String from, String to) {
        String[] header = s.nextLine().split("\t");
        Map<String, Integer> columns = new HashMap<>();

        for (int i = 1; i < header.length; i++) {
            columns.put(header[i], i - 1);
        }

        int fromColumn = columns.get(from);
        int toColumn = columns.get(to);

        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();

        while (s.hasNextLine()) {
            String[] line = s.nextLine().split("\t");
            switch (line[0]) {
                case "CLASS": {
                    classes.put(line[fromColumn + 1], line[toColumn + 1]);
                    break;
                }

                case "FIELD": {
                    fields.put(line[1] + ":" + line[fromColumn + 3], classes.get(line[1]) + ":" + line[toColumn + 3]);
                    break;
                }

                case "METHOD": {
                    methods.put(line[1] + ":" + line[fromColumn + 3] + line[2], classes.get(line[1]) + ":" + line[toColumn + 3] + line[2]);
                    break;
                }
            }
        }

        Mappings mappings = new Mappings();
        mappings.classes.putAll(classes);
        mappings.fields.putAll(fields);
        methods.forEach((a, b) -> mappings.methods.put(a, remapMethodDescriptor(b, classes)));

        s.close();
        return mappings;
    }

    private static void remap(Path source, Path target, List<Path> classpath, Mappings mappings) throws Exception {
        Mercury mercury = new Mercury();
        mercury.getClassPath().addAll(classpath);

        MappingSet mappingSet = new MappingSetImpl(new MappingSetModelFactoryImpl());
        mappings.classes.forEach((a, b) -> mappingSet
                .getOrCreateClassMapping(a)
                .setDeobfuscatedName(b)
        );

        mappings.fields.forEach((a, b) -> mappingSet
                .getOrCreateClassMapping(a.split(":")[0])
                .getOrCreateFieldMapping(a.split(":")[1])
                .setDeobfuscatedName(b.split(":")[1])
        );

        mappings.methods.forEach((a, b) -> mappingSet
                .getOrCreateClassMapping(a.split(":")[0])
                .getOrCreateMethodMapping(a.split(":")[1], getDescriptor(a.split(":")[1]))
                .setDeobfuscatedName(b.split(":")[1])
        );

        mercury.getProcessors().add(MercuryRemapper.create(mappingSet));

        mercury.rewrite(source, target);
    }

    private static String getDescriptor(String method) {
        try {
            StringBuilder result = new StringBuilder();

            Reader r = new StringReader(method);
            boolean started = false;
            while (true) {
                int c = r.read();

                if (c == -1) {
                    break;
                }

                if (c == '(') {
                    started = true;
                }

                if (started) {
                    result.append((char) c);
                }
            }

            return result.toString();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String remapMethodDescriptor(String method, Map<String, String> classMappings) {
        try {
            Reader r = new StringReader(method);
            StringBuilder result = new StringBuilder();
            boolean started = false;
            boolean insideClassName = false;
            StringBuilder className = new StringBuilder();
            while (true) {
                int c = r.read();
                if (c == -1) {
                    break;
                }

                if (c == ';') {
                    insideClassName = false;
                    result.append(classMappings.getOrDefault(className.toString(), className.toString()));
                }

                if (insideClassName) {
                    className.append((char) c);
                } else {
                    result.append((char) c);
                }

                if (c == '(') {
                    started = true;
                }

                if (started && c == 'L') {
                    insideClassName = true;
                    className.setLength(0);
                }
            }

            return result.toString();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static class Mappings {
        public final Map<String, String> classes = new LinkedHashMap<>();
        public final Map<String, String> fields = new LinkedHashMap<>();
        public final Map<String, String> methods = new LinkedHashMap<>();

        public Mappings chain(Mappings mappings) {
            Mappings result = new Mappings();

            classes.forEach((a, b) -> result.classes.put(a, mappings.classes.getOrDefault(b, b)));
            fields.forEach((a, b) -> result.fields.put(a, mappings.fields.getOrDefault(b, b)));
            methods.forEach((a, b) -> result.methods.put(a, mappings.methods.getOrDefault(b, b)));

            return result;
        }

        public Mappings invert() {
            Mappings result = new Mappings();

            classes.forEach((a, b) -> result.classes.put(b, a));
            fields.forEach((a, b) -> result.fields.put(b, a));
            methods.forEach((a, b) -> result.methods.put(b, a));

            return result;
        }
    }
}
