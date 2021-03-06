/*******************************************************************************
 *
 * Pentaho Big Data
 *
 * Copyright (C) 2002-2012 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.hadoop.shim;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSelectInfo;
import org.apache.commons.vfs.FileSelector;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.impl.DefaultFileSystemManager;
import org.pentaho.hadoop.shim.api.ActiveHadoopConfigurationLocator;
import org.pentaho.hadoop.shim.api.HadoopConfigurationFileSystemManager;
import org.pentaho.hadoop.shim.spi.HadoopConfigurationProvider;
import org.pentaho.hadoop.shim.spi.HadoopShim;
import org.pentaho.hadoop.shim.spi.PigShim;
import org.pentaho.hadoop.shim.spi.SnappyShim;
import org.pentaho.hadoop.shim.spi.SqoopShim;

/**
 * Singleton that knows how to load Hadoop configurations from disk. This class
 * is not thread-safe. This loads Hadoop configurations from a VFS FileSystem.
 */
public class HadoopConfigurationLocator implements HadoopConfigurationProvider {
  private static final String CONFIG_PROPERTIES_FILE = "config.properties";

  private static final String CONFIG_PROPERTY_IGNORE_CLASSES = "ignore.classes";

  private static final String CONFIG_PROPERTY_CLASSPATH = "classpath";

  private static final String CONFIG_PROPERTY_LIBRARY_PATH = "library.path";

  private static final String CONFIG_PROPERTY_NAME = "name";

  private static final URL[] EMPTY_URL_ARRAY = new URL[0];

  /**
   * Currently known shim configurations
   */
  private Map<String, HadoopConfiguration> configurations;

  /**
   * Flag indicating we've been initialized. We require initialization to know
   * where to look for Hadoop configurations on disk.
   */
  private boolean initialized;

  /**
   * Used to determine the active Hadoop configuration at runtime
   */
  private ActiveHadoopConfigurationLocator activeLocator;

  /**
   * The file system manager used to provide shims a way to register their {@link FileProvider} implementations.
   */
  private HadoopConfigurationFileSystemManager fsm;

  /**
   * Initialize this factory with a directory of where to look for cluster 
   * configurations.
   * 
   * @param baseDir Directory to look for Hadoop configurations in
   * @param activeLocator A locator for resolving the current active Hadoop configuration
   * @param fsm A file system manager to inject VFS file providers into from any loaded Hadoop configuration
   */
  public void init(FileObject baseDir, ActiveHadoopConfigurationLocator activeLocator, DefaultFileSystemManager fsm)
      throws ConfigurationException {
    if (baseDir == null) {
      throw new NullPointerException(FileObject.class.getSimpleName() + " is required");
    }
    if (activeLocator == null) {
      throw new NullPointerException(ActiveHadoopConfigurationLocator.class.getSimpleName() + " is required");
    }
    if (fsm == null) {
      throw new NullPointerException(DefaultFileSystemManager.class.getSimpleName() + " is required");
    }
    this.fsm = new HadoopConfigurationFileSystemManager(this, fsm);
    findHadoopConfigurations(baseDir);
    this.activeLocator = activeLocator;
    initialized = true;
  }

  /**
   * Attempt to find any Hadoop configuration as a direct descendant of the provided
   * directory.
   * 
   * @param baseDir Directory to look for Hadoop configurations in
   * @throws ConfigurationException 
   */
  private void findHadoopConfigurations(FileObject baseDir) throws ConfigurationException {
    configurations = new HashMap<String, HadoopConfiguration>();
    try {
      if (!baseDir.exists()) {
        throw new ConfigurationException("Hadoop configurations directory does not exist: " + baseDir.getURL());
      }
      for (FileObject f : baseDir.findFiles(new FileSelector() {
        @Override
        public boolean includeFile(FileSelectInfo info) throws Exception {
          return info.getDepth() == 1 && FileType.FOLDER.equals(info.getFile().getType());
        }

        @Override
        public boolean traverseDescendents(FileSelectInfo info) throws Exception {
          return info.getDepth() == 0;
        }
      })) {
        try {
          HadoopConfiguration config = loadHadoopConfiguration(f);
          if (config != null) {
            configurations.put(config.getIdentifier(), config);
          }
        } catch (ConfigurationException ex) {
          System.err.println("Unable to load Hadoop Configuration from " + f.getURL());
          ex.printStackTrace();
        }
      }
    } catch (FileSystemException ex) {
      throw new ConfigurationException(
          "Error loading Hadoop configurations from " + baseDir.getName().getFriendlyURI(), ex);
    }
  }

  /**
   * Find all jar files in the path provided.
   * 
   * @param path Path to search for jar files within
   * @param maxdepth Maximum traversal depth (1-based)
   * @return All jars found within {@code path} in at most {@code maxdepth} subdirectories.
   * @throws FileSystemException
   */
  private List<URL> findJarsIn(FileObject path, final int maxdepth) throws FileSystemException {
    FileObject[] jars = path.findFiles(new FileSelector() {
      @Override
      public boolean includeFile(FileSelectInfo info) throws Exception {
        return info.getFile().getName().getBaseName().endsWith(".jar");
      }

      @Override
      public boolean traverseDescendents(FileSelectInfo info) throws Exception {
        return info.getDepth() <= maxdepth;
      }
    });

    List<URL> jarUrls = new ArrayList<URL>();
    for (FileObject jar : jars) {
      jarUrls.add(jar.getURL());
    }
    return jarUrls;
  }

  private void checkInitialized() {
    if (!initialized) {
      throw new RuntimeException("locator not initialized");
    }
  }

  /**
   * Locates an implementation of {@code service} using the {@link ServiceLoader}.
   *
   * @param cl Class loader to look for implementations in
   * @return The first implementation found.
   */
  protected <T> T locateServiceImpl(ClassLoader cl, Class<T> service) {
    ServiceLoader<T> loader = ServiceLoader.load(service, cl);
    Iterator<T> iter = loader.iterator();
    if (iter.hasNext()) {
      return iter.next();
    }
    return null;
  }

  /**
   * Create a ClassLoader to load resources for a {@code HadoopConfiguration}.
   * 
   * @param root Configuration root directory
   * @param parent Parent class loader to delegate to if resources cannot be found in the configuration's directory or provided classpath
   * @param classpathUrls Additional URLs to add to the class loader. These will be added before any internal resources.
   * @param ignoredClasses Classes (or packages) that should not be loaded by the class loader
   * @return A class loader capable of loading a Hadoop configuration located at {@code root}.
   * @throws ConfigurationException Error creating a class loader for the Hadoop configuration located at {@code root}
   */
  protected ClassLoader createConfigurationLoader(FileObject root, ClassLoader parent, List<URL> classpathUrls,
      String... ignoredClasses) throws ConfigurationException {
    try {
      if (root == null || !FileType.FOLDER.equals(root.getType())) {
        throw new IllegalArgumentException("root must be a folder: " + root);
      }
      // Find all jar files in the 
      List<URL> jars = findJarsIn(root, 2);

      // Add the root of the configuration
      jars.add(0, new URL(root.getURL().toExternalForm() + "/"));
      // Inject any overriding URLs before all other paths
      if (classpathUrls != null) {
        jars.addAll(0, classpathUrls);
      }

      return new HadoopConfigurationClassLoader(jars.toArray(EMPTY_URL_ARRAY), parent, ignoredClasses);
    } catch (Exception ex) {
      throw new ConfigurationException("Could not create class loader", ex);
    }
  }

  /**
   * Parse a set of URLs from a comma-separated list of URLs. If the URL points 
   * to a directory all jar files within that directory will be returned as well.
   * 
   * @param urlString Comma-separated list of URLs (relative or absolute)
   * @return List of URLs resolved from {@code urlString}
   */
  protected List<URL> parseURLs(FileObject root, String urlString) {
    if (urlString == null || urlString.trim().isEmpty()) {
      return Collections.emptyList();
    }
    String[] paths = urlString.split(",");
    List<URL> urls = new ArrayList<URL>();
    for (String path : paths) {
      try {
        FileObject file = root.resolveFile(path.trim());
        if (FileType.FOLDER.equals(file.getType())) {
          // Add directories with a trailing / so the URL ClassLoader interprets them as directories
          urls.add(new URL(file.getURL().toExternalForm() + "/"));
          // Also add all jars within this directory
          urls.addAll(findJarsIn(file, 1));
        } else {
          urls.add(file.getURL());
        }
      } catch (Exception e) {
        // Log invalid path
        System.err.println("Invalid classpath entry, ignoring '" + path + "'");
      }
    }
    return urls;
  }

  /**
   * Attempt to discover a valid Hadoop configuration from the provided folder.
   * 
   * @param folder Folder that may represent a Hadoop configuration
   * @return A Hadoop configuration for the folder provided or null if none is found.
   * @throws ConfigurationException Error when loading the Hadoop configuration.
   */
  private HadoopConfiguration loadHadoopConfiguration(FileObject folder) throws ConfigurationException {
    Properties configurationProperties = new Properties();
    try {
      FileObject configFile = folder.getChild(CONFIG_PROPERTIES_FILE);
      if (configFile != null) {
        configurationProperties.putAll(loadProperties(configFile));
      }
    } catch (Exception ex) {
      throw new ConfigurationException("Unable to load configuration properties from " + CONFIG_PROPERTIES_FILE);
    }

    try {
      registerNativeLibraryPaths(configurationProperties.getProperty(CONFIG_PROPERTY_LIBRARY_PATH));
      // Parse all URLs from an optional classpath from the configuration file
      List<URL> classpathElements = parseURLs(folder, configurationProperties.getProperty(CONFIG_PROPERTY_CLASSPATH));

      // Allow external configuration of classes to ignore 
      String ignoredClassesProperty = configurationProperties.getProperty(CONFIG_PROPERTY_IGNORE_CLASSES);
      String[] ignoredClasses = null;
      if (ignoredClassesProperty != null) {
        ignoredClasses = ignoredClassesProperty.split(",");
      }

      // Pass our class loader in to the configurations' CL as its parent so it can find the same
      // API classes we're using
      ClassLoader cl = createConfigurationLoader(folder, getClass().getClassLoader(), classpathElements, ignoredClasses);

      // Attempt to locate a shim within this folder
      HadoopShim hadoopShim = locateServiceImpl(cl, HadoopShim.class);
      if (hadoopShim == null) {
        // No shim, no plugin!
        return null;
      }
      SqoopShim sqoopShim = locateServiceImpl(cl, SqoopShim.class);
      PigShim pigShim = locateServiceImpl(cl, PigShim.class);
      SnappyShim snappyShim = locateServiceImpl(cl, SnappyShim.class);
      String id = folder.getName().getBaseName();
      String name = configurationProperties.getProperty(CONFIG_PROPERTY_NAME, id);

      HadoopConfiguration config = new HadoopConfiguration(id, name, hadoopShim, sqoopShim, pigShim, snappyShim);
      hadoopShim.onLoad(config, fsm);
      return config;
    } catch (Throwable t) {
      throw new ConfigurationException("Error loading cluster configuration", t);
    }
  }

  /**
   * Register a comma-separated list of native library paths. 
   * @param paths Comma-separated list of libraries
   */
  protected void registerNativeLibraryPaths(String paths) {
    if (paths == null) {
      return;
    }
    for (String path : paths.split(",")) {
      boolean successful = registerNativeLibraryPath(path);
      if (!successful) {
        System.err.println("Unable to register library path: " + path);
      }
    }
  }

  /**
   * Dynamically register a native library path. This relies on a specific 
   * implementation detail of ClassLoader: it's usr_paths property.
   *  
   * @param path Library path to add
   * @return {@code true} if the library path could be added successfully
   */
  protected boolean registerNativeLibraryPath(String path) {
    if (path == null) {
      throw new NullPointerException();
    }
    path = path.trim();
    try {
      Field f = ClassLoader.class.getDeclaredField("usr_paths");
      boolean accessible = f.isAccessible();
      f.setAccessible(true);
      try {
        String[] paths = (String[]) f.get(null);

        // Make sure the path isn't already registered
        for (String p : paths) {
          if (p.equals(path)) {
            return true; // Success, it's already there!
          }
        }

        String[] newPaths = new String[paths.length + 1];
        System.arraycopy(paths, 0, newPaths, 0, paths.length);
        newPaths[paths.length] = path;
        f.set(null, newPaths);
        // Success!
        return true;
      } finally {
        f.setAccessible(accessible);
      }
    } catch (Exception ex) {
      // Something went wrong, definitely not successful
      return false;
    }
  }

  /**
   * Load the properties file located at {@code file}
   * @param file Location of a properties file to load
   * @return Loaded properties file
   * @throws IOException Error loading properties from file
   * @throws FileSystemException Error locating input stream for file
   */
  protected Properties loadProperties(FileObject file) throws FileSystemException, IOException {
    Properties p = new Properties();
    p.load(file.getContent().getInputStream());
    return p;
  }

  public List<HadoopConfiguration> getConfigurations() {
    checkInitialized();
    List<HadoopConfiguration> configs = new ArrayList<HadoopConfiguration>();
    for (Map.Entry<String, HadoopConfiguration> entry : configurations.entrySet()) {
      configs.add(entry.getValue());
    }
    return configs;
  }

  public boolean hasConfiguration(String id) {
    checkInitialized();
    return configurations.containsKey(id);
  }

  public HadoopConfiguration getConfiguration(String id) throws ConfigurationException {
    checkInitialized();
    HadoopConfiguration config = configurations.get(id);
    if (config == null) {
      throw new ConfigurationException("Unknown Hadoop configuration: " + id);
    }
    return config;
  }

  @Override
  public HadoopConfiguration getActiveConfiguration() throws ConfigurationException {
    return getConfiguration(activeLocator.getActiveConfigurationId());
  }
}
