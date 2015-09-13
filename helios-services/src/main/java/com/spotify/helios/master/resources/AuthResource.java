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

package com.spotify.helios.master.resources;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

//import com.spotify.crtauth.CrtAuthServer;
//import com.spotify.crtauth.exceptions.InvalidInputException;

@Path("/_auth")
public class AuthResource {

  private static final Logger log = LoggerFactory.getLogger(JobsResource.class);

//  private CrtAuthServer crtAuthServer;
//
//  public AuthResource(final CrtAuthServer crtAuthServer) {
//    this.crtAuthServer = crtAuthServer;
//  }
//
//  @SuppressWarnings("UnusedDeclaration")
//  @Inject
//  public void setCrtAuthServer(CrtAuthServer crtAuthServer) {
//    this.crtAuthServer = crtAuthServer;
//  }

  /**
   * TBA
   * @return A Response containing CRT auth challenge or token
   */
  @GET
  @Timed
  @ExceptionMetered
  public Response handleAuthentication(@HeaderParam("X-CHAP") String xChap) {
    return Response.ok().build();
//    final String[] xChapParts = xChap.split(":");
//
//    if (xChapParts.length < 2) {
//      return Response.status(400).entity(
// "X-CHAP header must be of the form <type>:<foo>").build();
//    }
//
//    try {
//      if (xChapParts[0].equals("request")) {
//        try {
//          String challenge = crtAuthServer.createChallenge(xChapParts[1]);
//          return Response.ok().header("X-CHAP", "challenge:" + challenge).build();
//        } catch (IllegalArgumentException ignored) {
//          log.info("Failed to deserialize CRT auth request string '{}'. "
//                   + "Client is probably not following the CRT auth protocol or version.", xChap);
//          return Response.status(400).entity("You did something wrong.").build();
//        }
//      } else if (xChapParts[0].equals("response")) {
//        String token = crtAuthServer.createToken(xChapParts[1]);
//        return Response.ok().header("X-CHAP", "token:" + token).build();
//      } else {
//        return Response.status(400).entity("Unknown action " + xChapParts[0]).build();
//      }
//    } catch (InvalidInputException e) {
//      throw new RuntimeException(e);
//    }
  }
}
