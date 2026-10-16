package com.mobility.location.driver;

import org.springframework.security.oauth2.jwt.Jwt;

public interface DriverAccessResolver {

    DriverIdentity resolve(Jwt jwt);
}