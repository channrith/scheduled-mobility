package com.mobility.gateway.edge;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxRequestSize largest request body accepted (by Content-Length); two-sided document uploads need ~24 MB
 * @param internalHeaders request headers only the gateway may set; removed from every incoming request
 * @param internalHeaderPrefix every incoming header with this prefix is removed too
 */
@ConfigurationProperties("gateway.edge")
public record EdgeProperties(@DefaultValue("24MB") DataSize maxRequestSize,
		@DefaultValue({ "X-User-Id", "X-User-Roles", "X-Roles", "X-Client-Id", "X-Partner-Id" }) List<String> internalHeaders,
		@DefaultValue("X-Internal-") String internalHeaderPrefix) {
}
