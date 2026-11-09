/* This file is part of Pandoroid
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.pandoroid.pandora;

import android.util.Log;
import android.webkit.CookieManager;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Description: RPC client implementation for interfacing with Pandora's REST API endpoints over HTTPS.
 */
public class RPC {

    private final OkHttpClient client;
    private final String request_url;
    private final String user_agent;
    private String csrfToken;

    private final Map<String, List<Cookie>> cookieStore =
            new HashMap<>();

    public RPC(String default_url,
               String default_entity_type,
               String default_user_agent) {

        client = new OkHttpClient.Builder()
                .cookieJar(new CookieJar() {

                    @Override
                    public void saveFromResponse(
                            HttpUrl url,
                            List<Cookie> cookies) {

                        Log.i(
                                "Pandoroid",
                                "Saving cookies: " + cookies);

                        List<Cookie> existing =
                                cookieStore.getOrDefault(
                                        url.host(),
                                        new ArrayList<>());

                        Map<String, Cookie> merged =
                                new HashMap<>();

                        for (Cookie cookie : existing) {
                            merged.put(cookie.name(), cookie);
                        }

                        for (Cookie cookie : cookies) {
                            merged.put(cookie.name(), cookie);
                        }

                        cookieStore.put(
                                url.host(),
                                new ArrayList<>(merged.values()));

                        for (Cookie cookie : cookies) {

                            Log.i(
                                    "Pandoroid",
                                    "COOKIE: "
                                            + cookie.name()
                                            + "="
                                            + cookie.value());

                            if ("csrftoken".equalsIgnoreCase(
                                    cookie.name())) {

                                csrfToken = cookie.value();

                                Log.i(
                                        "Pandoroid",
                                        "CSRF TOKEN FOUND: "
                                                + csrfToken);
                            }
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(
                            HttpUrl url) {

                        List<Cookie> cookies =
                                cookieStore.get(url.host());

                        Log.i(
                                "Pandoroid",
                                "Sending cookies: " + cookies);

                        return cookies != null
                                ? cookies
                                : List.of();
                    }
                })
                .build();

        request_url = default_url;
        user_agent = default_user_agent;
    }

    public void initializeCsrf() throws IOException {

        Request request = new Request.Builder()
                .url("https://www.pandora.com/")
                .head()
                .build();

        try (Response response =
                     client.newCall(request).execute()) {

            Log.i(
                    "Pandoroid",
                    "CSRF bootstrap headers:\n"
                            + response.headers());

            List<String> cookies =
                    response.headers("Set-Cookie");

            for (String cookie : cookies) {

                Log.i(
                        "Pandoroid",
                        "BOOTSTRAP COOKIE: "
                                + cookie);
            }
        }
    }

    /**
     * Description: Legacy call overload.
     */
    public String call(Map<String, String> url_params,
                       String entity_data,
                       boolean require_secure) throws Exception, IOException {
        String method = url_params != null ? url_params.get("method") : "";
        String endpoint = method != null ? method.replace(".", "/") : "";
        return callEndpoint(endpoint, entity_data, null);
    }

    /**
     * Description: Overload for callEndpoint without auth tokens.
     */
    public String callEndpoint(String endpoint,
                               Map<String, String> url_params,
                               String entity_data) throws Exception, IOException {
        return callEndpoint(endpoint, entity_data, null);
    }

    /**
     * Description: Calls a Pandora REST API endpoint with JSON payload and REST authentication headers.
     */
    public String callEndpoint(
            String endpoint,
            String jsonBody,
            String authToken)
            throws Exception, IOException {

        String fullUrl =
                "https://" +
                        request_url +
                        endpoint;

        MediaType JSON =
                MediaType.parse(
                        "application/json; charset=utf-8");

        RequestBody requestBody =
                RequestBody.create(jsonBody, JSON);

        Request.Builder requestBuilder =
                new Request.Builder()
                        .url(fullUrl)
                        .post(requestBody)
                        .addHeader(
                                "Content-Type",
                                "application/json; charset=utf-8")
                        .addHeader(
                                "User-Agent",
                                user_agent);

        if (authToken != null) {
            requestBuilder.addHeader(
                    "X-AuthToken",
                    authToken);
        }
        if (csrfToken != null) {
            requestBuilder.addHeader(
                    "X-CsrfToken",
                    csrfToken);
        }
        Log.i(
                "Pandoroid",
                "CSRF Token: " + csrfToken);
        String cookies =
                CookieManager.getInstance()
                        .getCookie("https://www.pandora.com");

        Log.i("Pandoroid", "cookies=" + cookies);

        Log.i("Pandoroid", "URL: " + fullUrl);
        Log.i("Pandoroid", "Body: " + jsonBody);
        Log.i("Pandoroid", "AuthToken: " + authToken);
        Log.i(
                "Pandoroid",
                "Headers = " + requestBuilder.build().headers()
        );
        Request request = requestBuilder.build();

        try (Response response =
                     client.newCall(request).execute()) {

            String responseBody =
                    response.body() != null
                            ? response.body().string()
                            : "";

            Log.i(
                    "Pandoroid",
                    "Response Headers = " + response.headers()
            );

            Log.i("Pandoroid",
                    "Response Body: " + responseBody);

            if (!response.isSuccessful()) {
                throw new Exception(
                        "HTTP " +
                                response.code() +
                                ": " +
                                responseBody);
            }

            return responseBody;
        }
    }

    private String makeUrlParamString(Map<String, String> mapped_url_params) throws UnsupportedEncodingException {
        StringBuilder url_string = new StringBuilder("?");
        boolean first_loop = true;
        for (Map.Entry<String, String> entry : mapped_url_params.entrySet()){
            if (!first_loop){
                url_string.append("&");
            } else {
                first_loop = false;
            }
            url_string.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                      .append("=")
                      .append(URLEncoder.encode(entry.getValue() , "UTF-8"));
        }
        return url_string.toString();
    }
}
