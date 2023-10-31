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

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * Description: This is the RPC client implementation for interfacing with 
 *  Pandora's servers. At the moment it uses Pandora's JSON API, but will
 *  hopefully be useful for whatever Pandora throws at us in the future.
 */
public class RPC {
    private final OkHttpClient client2;
    //private OkHttpClient client;
    private final String request_url;
    private final String user_agent;

    /**
     * Description: Our constructor class. This will set our default parameters
     *  for subsequent http requests, along with the MIME type for our entity
     *  (i.e. 'text/plain' for the current JSON protocol), and the partial URL 
     *  for the server (i.e. 'tuner.pandora.com/path/to/request/').
     */
    public RPC(String default_url,
               String default_entity_type,
               String default_user_agent){
        client2 = new OkHttpClient();
        request_url = default_url;
        user_agent = default_user_agent;
    }

    /**
     * Description: This function contacts the remote server with a string
     *  type data package (could be JSON), and returns the remote server's 
     *  response in a string.
     * @throws Exception if url_params or entity_data is empty/null.
     * @throws IOException if a connection to the remote server can't be made.
     */
    public String call(Map<String, String> url_params,
                       String entity_data,
                       boolean require_secure) throws Exception,
            IOException{

        if (url_params == null || url_params.size() == 0){
            throw new Exception("Missing URL paramaters");
        }
        if (entity_data == null){
            throw new Exception("Missing data for HTTP entity.");
        }

        String full_url;

        if (require_secure){
            full_url = "https://" + request_url;
        }
        else{
            full_url = "https://" + request_url;
        }

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(entity_data, JSON);
        //OkHttpClient client = new OkHttpClient.Builder().protocols(Arrays.asList(
        //        Protocol.HTTP_3,
        //        Protocol.HTTP_2,
        //        Protocol.H2_PRIOR_KNOWLEDGE
        //        //Protocol.HTTP_1_1
        //        )
        //).build();
        //if (!require_secure) {
        //    client = new OkHttpClient.Builder().protocols(List.of(
        //            Protocol.H2_PRIOR_KNOWLEDGE)
        //    ).build();
        //}else {
        //    client = new OkHttpClient.Builder().protocols(Arrays.asList(
        //            Protocol.HTTP_3,
        //            Protocol.HTTP_2,
        //            //Protocol.H2_PRIOR_KNOWLEDGE
        //            Protocol.HTTP_1_1
        //            )
        //    ).build();
        //}
        Request request = new Request.Builder()
                .url(full_url.concat(makeUrlParamString(url_params)))
                .post(body)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                //.addHeader("User-Agent", user_agent) //Notice this request has header if you don't need to send a header just erase this part
                .build();

        Log.i("pandoroid", "URI: " + full_url.concat(makeUrlParamString(url_params)));

        //Send to the server and get our response
        //Call call = client2.newCall(request);
        //Response response = call.execute();
        try (Response response = client2.newCall(request).execute()) {


            int status_code = response.code();
            if (status_code != 200) {
                throw new Exception("HTTP status code: "
                        + status_code + " != "
                        + 200);
            }
            Log.i("pandoroid", "Responce Body: " + response.body().byteStream());
            Log.i("pandoroid", "Responce Code: " + response.code());

            int BUFFER_BYTE_SIZE = 512;
            StringBuilder ret_data = new StringBuilder();
            byte[] bytes = new byte[BUFFER_BYTE_SIZE];

            //Check the entity type (usually 'text/plain'). Probably doesn't need
            //to be checked.
//      if (response_entity.getContentType().getValue().equals(entity_type)){
            InputStream content = response.body().byteStream();
            int bytes_read = BUFFER_BYTE_SIZE;

            //Rather than read an arbitrary amount of bytes, lets be sure to get
            //it all.
            while ((bytes_read = content.read(bytes, 0, BUFFER_BYTE_SIZE)) != -1) {
                ret_data.append(new String(bytes, 0, bytes_read));
            }
//      }
//      else{
//          throw new Exception("Improper server response entity type: " +
//                              response_entity.getContentType().getValue());
//      }

            return ret_data.toString();
        }
    }

    /**
     * Description: Here we create a URL method string with the parameters
     *  given. It automatically applies the '?' character to the beginning
     *  of strings, so multiple calls to this function will create an invalid 
     *  URL request string.
     */
    private String makeUrlParamString(Map<String, String> mapped_url_params) throws UnsupportedEncodingException {
        String url_string = "?";
        boolean first_loop = true;
        for (Map.Entry<String, String> entry : mapped_url_params.entrySet()){
            if (!first_loop){
                url_string += "&";
            }
            else{
                first_loop = false;
            }
            url_string += URLEncoder.encode(entry.getKey(), "UTF-8") + "="
                    + URLEncoder.encode(entry.getValue() , "UTF-8");
        }

        return url_string;
    }
}