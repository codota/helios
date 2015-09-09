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

package com.spotify.helios.master.auth;

import com.google.common.base.Optional;

import com.spotify.crtauth.CrtAuthServer;
import com.spotify.crtauth.keyprovider.InMemoryKeyProvider;

import io.dropwizard.auth.AuthenticationException;
import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.basic.BasicCredentials;

public class CrtAuthenticator implements Authenticator<BasicCredentials, User> {

  private final CrtAuthServer crtAuthServer;

  public CrtAuthenticator(final String serverName) {
    final InMemoryKeyProvider keyProvider = new InMemoryKeyProvider();

    this.crtAuthServer = new CrtAuthServer.Builder()
        .setServerName(serverName)
        .setKeyProvider(keyProvider)
        .setSecret(new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef})
        .build();
  }

  @Override
  public Optional<User> authenticate(final BasicCredentials credentials) throws AuthenticationException {
    if ("secret".equals(credentials.getPassword())) {
      return Optional.of(new User(credentials.getUsername()));
    }
    return Optional.absent();
  }
}
