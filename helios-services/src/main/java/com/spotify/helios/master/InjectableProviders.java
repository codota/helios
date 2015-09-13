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

package com.spotify.helios.master;

import com.spotify.helios.authentication.InjectableProviderFactory;
import com.spotify.helios.authentication.InjectableProviderLoader;
import com.spotify.helios.authentication.InjectableProviderLoadingException;
import com.spotify.helios.authentication.NopInjectableProviderFactory;
import com.sun.jersey.api.model.Parameter;
import com.sun.jersey.spi.inject.InjectableProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import io.dropwizard.auth.Auth;

import static com.google.common.base.Strings.isNullOrEmpty;

public class InjectableProviders {

  private static final Logger log = LoggerFactory.getLogger(InjectableProviders.class);

  /**
   * Create an auth provider.
   * Attempts to load it from a plugin if path is not null or using the app
   * class loader otherwise. If no authenticator plugin was found, returns a nop auth provider.
   *
   * @param path Path to plugin jar.
   * @param secret String representing the secret for the auth
   * @return The InjectableProvider object.
   */
  public static InjectableProvider<Auth, Parameter>
  createInjectableProvider(final Path path, final String secret) {
    // Get an auth provider factory
    final InjectableProviderFactory factory;
    if (path == null) {
      factory = createFactory();
    } else {
      factory = createFactory(path);
    }

    // Create the authenticator
    log.info("Creating injectable provider.");
    final InjectableProvider<Auth, Parameter> injectableProvider =
        isNullOrEmpty(secret) ? factory.create() : factory.createWithSecret(secret);
    log.info("CREATED INJECTABLE");
    return injectableProvider;
  }

  /**
   * Get an authenticator factory from a plugin.
   *
   * @param path The path to the plugin jar.
   * @return The InjectableProviderFactory object.
   */
  private static InjectableProviderFactory createFactory(final Path path) {
    final InjectableProviderFactory factory;
    final Path absolutePath = path.toAbsolutePath();
    try {
      factory = InjectableProviderLoader.load(absolutePath);
      final String name = factory.getClass().getName();
      log.info("Loaded authenticator plugin: {} ({})", name, absolutePath);
    } catch (InjectableProviderLoadingException e) {
      throw new RuntimeException("Unable to load authenticator plugin: " + absolutePath, e);
    }
    return factory;
  }

  /**
   * Get an authenticator factory from the application class loader.
   *
   * @return The InjectableProviderFactory object.
   */
  private static InjectableProviderFactory createFactory() {
    final InjectableProviderFactory factory;
    final InjectableProviderFactory installed;
    try {
      installed = InjectableProviderLoader.load();
    } catch (InjectableProviderLoadingException e) {
      throw new RuntimeException("Unable to load authenticator", e);
    }
    if (installed == null) {
      log.debug("No authenticator plugin configured");
      factory = new NopInjectableProviderFactory();
    } else {
      factory = installed;
      final String name = factory.getClass().getName();
      log.info("Loaded installed authenticator: {}", name);
    }
    return factory;
  }
}
