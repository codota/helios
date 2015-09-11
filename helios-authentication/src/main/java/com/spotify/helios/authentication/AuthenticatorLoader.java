/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.authentication;

import org.slf4j.Logger;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import static java.util.Arrays.asList;

/**
 * Helpers for loading {@link AuthenticatorFactory} instances.
 */
public class AuthenticatorLoader {

  private static final List<Package> PROVIDED = asList(Logger.class.getPackage(),
                                                       AuthenticatorLoader.class.getPackage());

  private static final ClassLoader CURRENT = AuthenticatorLoader.class.getClassLoader();

  /**
   * Load a {@link AuthenticatorFactory} using the current class loader.
   *
   * @return A {@link AuthenticatorFactory}, null if none could be found.
   * @throws AuthenticatorLoadingException if loading failed.
   */
  public static AuthenticatorFactory load() throws AuthenticatorLoadingException {
    return load("classpath", CURRENT);
  }

  /**
   * Load a {@link AuthenticatorFactory} from a plugin jar file with a parent class loader that
   * will not load classes from the jvm classpath. Any dependencies of the plugin must be included
   * in the plugin jar.
   *
   * @param plugin The plugin jar file to load.
   * @return A {@link AuthenticatorFactory}, null if none could be found.
   * @throws AuthenticatorLoadingException if loading failed.
   */
  public static AuthenticatorFactory load(final Path plugin)
      throws AuthenticatorLoadingException {
    return load(plugin, CURRENT, extensionClassLoader(CURRENT));
  }

  /**
   * Load a {@link AuthenticatorFactory} from a plugin jar file with a specified parent class
   * loader and a list of exposed classes.
   *
   * @param plugin      The plugin jar file to load.
   * @param environment The class loader to use for providing plugin interface dependencies.
   * @param parent      The parent class loader to assign to the class loader of the jar.
   * @return A {@link AuthenticatorFactory}, null if none could be found.
   * @throws AuthenticatorLoadingException if loading failed.
   */
  public static AuthenticatorFactory load(final Path plugin,
                                             final ClassLoader environment,
                                             final ClassLoader parent)
      throws AuthenticatorLoadingException {
    return load("plugin jar file: " + plugin, pluginClassLoader(plugin, environment, parent));
  }

  /**
   * Load a {@link AuthenticatorFactory} using a class loader.
   *
   * @param source      The source of the class loader.
   * @param classLoader The class loader to load from.
   * @return A {@link AuthenticatorFactory}, null if none could be found.
   * @throws AuthenticatorLoadingException if loading failed.
   */
  public static AuthenticatorFactory load(final String source, final ClassLoader classLoader)
      throws AuthenticatorLoadingException {
    final ServiceLoader<AuthenticatorFactory> loader;
    try {
      loader = ServiceLoader.load(AuthenticatorFactory.class, classLoader);
    } catch (ServiceConfigurationError e) {
      throw new AuthenticatorLoadingException(
          "Failed to load service registrar from " + source, e);
    }
    final Iterator<AuthenticatorFactory> iterator = loader.iterator();
    if (iterator.hasNext()) {
      return iterator.next();
    } else {
      return null;
    }
  }

  /**
   * Attempt to get the extension class loader, which can only load jdk classes and classes from
   * jvm
   * extensions. Adapted from {@link ServiceLoader#loadInstalled(Class)}
   */
  private static ClassLoader extensionClassLoader(final ClassLoader environment) {
    ClassLoader cl = environment;
    ClassLoader prev = null;
    while (cl != null) {
      prev = cl;
      cl = cl.getParent();
    }
    return prev;
  }

  /**
   * Create a class loader for a plugin jar.
   */
  private static ClassLoader pluginClassLoader(final Path plugin,
                                               final ClassLoader environment,
                                               final ClassLoader parent) {
    final URL url;
    try {
      url = plugin.toFile().toURI().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException("Failed to load plugin jar " + plugin, e);
    }
    final ClassLoader providedClassLoader = new FilteringClassLoader(PROVIDED, environment, parent);
    return new URLClassLoader(new URL[]{url}, providedClassLoader);
  }

  /**
   * A class loader that exposes a specific list of packages from another class loader.
   */
  private static class FilteringClassLoader extends ClassLoader {

    private final List<Package> packages;
    private final ClassLoader environment;

    public FilteringClassLoader(final List<Package> packages,
                                final ClassLoader environment,
                                final ClassLoader parent) {
      super(parent);
      this.packages = packages;
      this.environment = environment;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
      for (final Package pkg : packages) {
        if (name.startsWith(pkg.getName())) {
          return environment.loadClass(name);
        }
      }
      return super.findClass(name);
    }
  }
}
