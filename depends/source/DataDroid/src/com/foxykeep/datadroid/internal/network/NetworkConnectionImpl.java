/**
 * 2011 Foxykeep (http://datadroid.foxykeep.com)
 * <p>
 * Licensed under the Beerware License : <br />
 * As long as you retain this notice you can do whatever you want with this stuff. If we meet some
 * day, and you think this stuff is worth it, you can buy me a beer in return
 */

/*Reviewed and Updated 3/12/25*/

/*WHEN SETTING SERVER AND APP UP TOGETHER:
Make sure to replace "password" with the actual passwords for your key and trust stores, 
and adjust the resource IDs (R.raw.client and R.raw.server) to point to your actual certificate files. 
This implementation will ensure that mTLS is used for authentication in the NetworkConnectionImpl.java file.*/

package com.foxykeep.datadroid.internal.network;

import com.foxykeep.datadroid.exception.ConnectionException;
import com.foxykeep.datadroid.network.HttpUrlConnectionHelper;
import com.foxykeep.datadroid.network.NetworkConnection.ConnectionResult;
import com.foxykeep.datadroid.network.NetworkConnection.Method;
import com.foxykeep.datadroid.network.UserAgentUtils;
import com.foxykeep.datadroid.util.DataDroidLog;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.apache.http.HttpStatus;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;


public final class NetworkConnectionImpl {

    private static final String TAG = NetworkConnectionImpl.class.getSimpleName();

    private static final String ACCEPT_CHARSET_HEADER = "Accept-Charset";
    private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String LOCATION_HEADER = "Location";

    private static final String UTF8_CHARSET = "UTF-8";

    private static final int OPERATION_TIMEOUT = 60 * 1000;

    private NetworkConnectionImpl() {
        // No public constructor
    }


        HttpURLConnection connection = null;
        try {
            if (userAgent == null) {
                userAgent = UserAgentUtils.get(context);
            }
            if (headerMap == null) {
                headerMap = new HashMap<String, String>();
            }
            headerMap.put(HTTP.USER_AGENT, userAgent);
            if (isGzipEnabled) {
                headerMap.put(ACCEPT_ENCODING_HEADER, "gzip");
            }
            headerMap.put(ACCEPT_CHARSET_HEADER, UTF8_CHARSET);

            StringBuilder paramBuilder = new StringBuilder();
            if (parameterList != null && !parameterList.isEmpty()) {
                for (BasicNameValuePair parameter : parameterList) {
                    String name = parameter.getName();
                    String value = parameter.getValue();
                    if (TextUtils.isEmpty(name)) {
                        continue;
                    }
                    if (value == null) {
                        value = "";
                    }
                    paramBuilder.append(URLEncoder.encode(name, UTF8_CHARSET));
                    paramBuilder.append("=");
                    paramBuilder.append(URLEncoder.encode(value, UTF8_CHARSET));
                    paramBuilder.append("&");
                }
            }

            if (DataDroidLog.canLog(Log.DEBUG)) {
                DataDroidLog.d(TAG, "Request url: " + urlValue);
                DataDroidLog.d(TAG, "Method: " + method.toString());

                if (parameterList != null && !parameterList.isEmpty()) {
                    DataDroidLog.d(TAG, "Parameters:");
                    for (BasicNameValuePair parameter : parameterList) {
                        DataDroidLog.d(TAG, "- \"" + parameter.getName() + "\" = \"" + parameter.getValue() + "\"");
                    }
                    DataDroidLog.d(TAG, "Parameters String: \"" + paramBuilder.toString() + "\"");
                }
                if (postText != null) {
                    DataDroidLog.d(TAG, "Post data: " + postText);
                }
                if (headerMap != null && !headerMap.isEmpty()) {
                    DataDroidLog.d(TAG, "Headers:");
                    for (Entry<String, String> header : headerMap.entrySet()) {
                        DataDroidLog.d(TAG, "- " + header.getKey() + " = " + header.getValue());
                    }
                }
            }

            URL url = null;
            String outputText = null;
            switch (method) {
                case GET:
                case DELETE:
                    String fullUrlValue = urlValue;
                    if (paramBuilder.length() > 0) {
                        fullUrlValue += "?" + paramBuilder.toString();
                    }
                    url = new URL(fullUrlValue);
                    connection = HttpUrlConnectionHelper.openUrlConnection(url);
                    break;
                case PUT:
                case POST:
                    url = new URL(urlValue);
                    connection = HttpUrlConnectionHelper.openUrlConnection(url);
                    connection.setDoOutput(true);
                    if (paramBuilder.length() > 0) {
                        outputText = paramBuilder.toString();
                        headerMap.put(HTTP.CONTENT_TYPE, "application/x-www-form-urlencoded");
                        headerMap.put(HTTP.CONTENT_LEN, String.valueOf(outputText.getBytes().length));
                    } else if (postText != null) {
                        outputText = postText;
                    }
                    break;
            }

            connection.setRequestMethod(method.toString());

            if (url.getProtocol().equals("https") && !isSslValidationEnabled) {
                throw new ConnectionException("SSL validation must be enabled", connection.getResponseCode());
            }

            SSLContext sslContext = getSslContext(context);
            HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());

            if (!headerMap.isEmpty()) {
                for (Entry<String, String> header : headerMap.entrySet()) {
                    connection.addRequestProperty(header.getKey(), header.getValue());
                }
            }


