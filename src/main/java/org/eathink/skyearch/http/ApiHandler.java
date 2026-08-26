package org.eathink.skyearch.http;

@FunctionalInterface
public interface ApiHandler {

    ApiResponse handle(ApiRequest request) throws Exception;
}
