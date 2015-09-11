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

import com.google.common.io.CharStreams;

import com.spotify.crtauth.CrtAuthServer;
import com.spotify.crtauth.keyprovider.InMemoryKeyProvider;
import com.spotify.crtauth.utils.TraditionalKeyParser;

import java.io.FileReader;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

public class CrtAuthServerFactory {

  private static final String PUBLIC_KEY;
  static {
    try {
      PUBLIC_KEY = CharStreams.toString(
          new FileReader("/Users/david/src/helios/id_rsa.pub")).trim();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static CrtAuthServer makeCrtAuthServer() {
    InMemoryKeyProvider keyProvider = new InMemoryKeyProvider();

    try {
      RSAPublicKeySpec publicKeySpec = TraditionalKeyParser.parsePemPublicKey(PUBLIC_KEY);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");

      RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
      keyProvider.putKey("test", publicKey);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return new CrtAuthServer.Builder()
        .setServerName("localhost")
        .setKeyProvider(keyProvider)
        .setSecret(new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef})
        .build();
  }
}
