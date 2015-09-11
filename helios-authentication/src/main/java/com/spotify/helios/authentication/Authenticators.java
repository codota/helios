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

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static com.google.common.base.Strings.isNullOrEmpty;

public class Authenticators {

  private static final Logger log = LoggerFactory.getLogger(Authenticators.class);

  /**
   * Create an authenticator. Attempts to load it from a plugin if path is not null or using the app
   * class loader otherwise. If no authenticator plugin was found, returns a nop authenticator.
   *
   * @param path Path to plugin jar.
   * @param secret A secret for the authenticator.
   * @return The Authenticator object.
   */
  public static Authenticator createAuthenticator(final Path path, @Nullable final String secret) {
    // Get an authenticator factory
    final AuthenticatorFactory factory;
    if (path == null) {
      factory = createFactory();
    } else {
      factory = createFactory(path);
    }

    // Create the authenticator
    log.info("Creating authenticator.");
    return isNullOrEmpty(secret) ? factory.create() : factory.createWithSecret(secret);
  }

  /**
   * Get an authenticator factory from a plugin.
   *
   * @param path The path to the plugin jar.
   * @return The AuthenticatorFactory object.
   */
  private static AuthenticatorFactory createFactory(final Path path) {
    final AuthenticatorFactory factory;
    final Path absolutePath = path.toAbsolutePath();
    try {
      factory = AuthenticatorLoader.load(absolutePath);
      final String name = factory.getClass().getName();
      log.info("Loaded authenticator plugin: {} ({})", name, absolutePath);
    } catch (AuthenticatorLoadingException e) {
      throw new RuntimeException("Unable to load authenticator plugin: " + absolutePath, e);
    }
    return factory;
  }

  /**
   * Get an authenticator factory from the application class loader.
   *
   * @return The AuthenticatorFactory object.
   */
  private static AuthenticatorFactory createFactory() {
    final AuthenticatorFactory factory;
    final AuthenticatorFactory installed;
    try {
      installed = AuthenticatorLoader.load();
    } catch (AuthenticatorLoadingException e) {
      throw new RuntimeException("Unable to load authenticator", e);
    }
    if (installed == null) {
      log.debug("No authenticator plugin configured");
      factory = new NopAuthenticatorFactory();
    } else {
      factory = installed;
      final String name = factory.getClass().getName();
      log.info("Loaded installed authenticator: {}", name);
    }
    return factory;
  }
}
