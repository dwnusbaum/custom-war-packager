package io.jenkins.tools.warpackager.lib.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jenkins.tools.warpackager.lib.model.bom.BOM;
import io.jenkins.tools.warpackager.lib.model.bom.ComponentReference;
import io.jenkins.tools.warpackager.lib.model.bom.Environment;
import io.jenkins.tools.warpackager.lib.model.bom.Metadata;
import io.jenkins.tools.warpackager.lib.model.bom.Specification;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Oleg Nenashev
 * @since TODO
 */
@SuppressFBWarnings(value = "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD", justification = "JSON Deserialization")
public class Config {

    private static final Logger LOGGER = Logger.getLogger(Config.class.getName());

    public BuildSettings buildSettings;
    public PackageInfo bundle;
    // Nonnull after the build starts
    public DependencyInfo war;
    @CheckForNull
    public Collection<DependencyInfo> plugins;
    @CheckForNull
    public Collection<DependencyInfo> libPatches;
    @CheckForNull
    public Collection<DependencyInfo> libExcludes;
    @CheckForNull
    public Map<String, String> systemProperties;
    @CheckForNull
    public Collection<GroovyHookInfo> groovyHooks;
    @CheckForNull
    public Collection<CasCConfig> casc;

    private static Config load(@Nonnull InputStream istream, boolean isEssentialsYML) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        final Config loaded;
        if (isEssentialsYML) {
            LOGGER.log(Level.INFO, "Loading from the essentials.yml format");
            EssentialsYMLConfig cfg = mapper.readValue(istream, EssentialsYMLConfig.class);
            if (cfg.packaging == null) {
                throw new IOException("essentials.yml does not have the packaging section");
            }
            if(cfg.packaging.config != null) {
                loaded = cfg.packaging.config;
            } else if (cfg.packaging.configFile != null){
                LOGGER.log(Level.INFO, "Loading config from external file defined in essentials.yml: {0}",
                        cfg.packaging.configFile);
                loaded = loadConfig(new File(cfg.packaging.configFile));
            } else {
                throw new IOException("essentials.yml does not have `packaging.config` or `packaging.configFile`");
            }
        } else {
            loaded = mapper.readValue(istream, Config.class);
        }
        if (loaded.buildSettings == null) {
            loaded.buildSettings = new BuildSettings();
        }
        return loaded;
    }

    public static Config loadDemoConfig() throws IOException {
        LOGGER.log(Level.WARNING, "Loading the default configuration sample from resource {0}/sample.yml", Config.class);
        try (InputStream istream = Config.class.getResourceAsStream("sample.yml")) {
            if (istream == null) {
                throw new FileNotFoundException(String.format("Cannot load the demo config: %s/sample.yml", Config.class));
            }
            return load(istream, false);
        }
    }

    /**
     * Loads configuration file.
     * Both standard and {@code essentials.yml} formats are supported.
     * The format is determined by the name.
     * @param configPath Path to the configuration file.
     * @return Loaded configuration
     * @throws IOException Loading error
     */
    public static Config loadConfig(@Nonnull File configPath) throws IOException {
        if (configPath.exists() && configPath.isFile()) {
            try (FileInputStream istream = new FileInputStream(configPath)) {
                return load(istream, "essentials.yml".equals(configPath.getName()));
            }
        }
        throw new FileNotFoundException("Cannot find the configuration file " + configPath);
    }

    // TODO: make the destination configurable
    public File getOutputWar() {
        return new File(buildSettings.getTmpDir(), "/output/target/" + bundle.artifactId + "-" + buildSettings.getVersion() + ".war");
    }

    public File getOutputBOM() {
        return new File(buildSettings.getTmpDir(), "/output/target/" + bundle.artifactId + "-" + buildSettings.getVersion() + ".bom.yml");
    }

    @CheckForNull
    public GroovyHookInfo getHookById(@Nonnull String id) {
        if (groovyHooks == null) {
            return null;
        }

        for (GroovyHookInfo hook : groovyHooks) {
            if (id.equals(hook.id)) {
                return hook;
            }
        }
        return null;
    }

    public List<WARResourceInfo> getAllExtraResources() {
        final List<WARResourceInfo> list = new ArrayList<>();
        if (groovyHooks != null) {
            list.addAll(groovyHooks);
        }
        if (casc != null) {
            list.addAll(casc);
        }
        return list;
    }

    @CheckForNull
    public WARResourceInfo findResourceById(@Nonnull String id) {
        for (WARResourceInfo hook : getAllExtraResources()) {
            if (id.equals(hook.id)) {
                return hook;
            }
        }
        return null;
    }

    @CheckForNull
    public DependencyInfo findPlugin(@Nonnull String artifactId) {
        if (plugins == null) {
            return null;
        }

        for (DependencyInfo plugin : plugins) {
            if (artifactId.equals(plugin.artifactId)) {
                return plugin;
            }
        }
        return null;
    }

    public void overrideByBOM(@Nonnull BOM bom, @CheckForNull String environmentName) throws IOException {
        final Specification spec = bom.getSpec();
        war = spec.getCore().toWARDependencyInfo();

        // Bundle information
        // TODO: better merge logic?
        PackageInfo bomPackageInfo = null;
        Metadata metadata = bom.getMetadata();
        if (metadata != null) {
            bomPackageInfo = metadata.toPackageInfo();
        }
        if (bomPackageInfo != null) {
            this.bundle = bomPackageInfo;
        }

        Environment env = null;
        if (environmentName != null) {
            env = spec.getEnvironment(environmentName);
        }

        // Plugins
        plugins = new ArrayList<>();
        for (ComponentReference ref : spec.getPlugins()) {
            plugins.add(ref.toDependencyInfo());
        }
        if (env != null) {
            for (ComponentReference ref : env.getPlugins()) {
                plugins.add(ref.toDependencyInfo());
            }
        }

        // TODO(oleg_nenashev): I cannot determine component type.
        // We assume that all components are libs, but it will block roundtrips since BOMBuilder also injects scripts
        libPatches = new ArrayList<>();
        for (ComponentReference ref : spec.getComponents()) {
            libPatches.add(ref.toDependencyInfo());
        }
        if (env != null) {
            for (ComponentReference ref : env.getComponents()) {
                libPatches.add(ref.toDependencyInfo());
            }
        }
    }
}
