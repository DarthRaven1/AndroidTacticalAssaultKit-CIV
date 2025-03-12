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
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

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

    public static ConnectionResult execute(Context context, String urlValue, Method method,
            ArrayList<BasicNameValuePair> parameterList, HashMap<String, String> headerMap,
            boolean isGzipEnabled, String userAgent, String postText,
            boolean isSslValidationEnabled) throws ConnectionException {
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

            connection.setConnectTimeout(OPERATION_TIMEOUT);
            connection.setReadTimeout(OPERATION_TIMEOUT);

            if ((method == Method.POST || method == Method.PUT) && outputText != null) {
                OutputStream output = null;
                try {
                    output = connection.getOutputStream();
                    output.write(outputText.getBytes());
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException e) {
                            // Already catching the first IOException so nothing to do here.
                        }
                    }
                }
            }

            String contentEncoding = connection.getHeaderField(HTTP.CONTENT_ENCODING);

            int responseCode = connection.getResponseCode();
            boolean isGzip = contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip");
            DataDroidLog.d(TAG, "Response code: " + responseCode);

            if (responseCode == HttpStatus.SC_MOVED_PERMANENTLY) {
                String redirectionUrl = connection.getHeaderField(LOCATION_HEADER);
                throw new ConnectionException("New location : " + redirectionUrl, redirectionUrl);
            }

            InputStream errorStream = connection.getErrorStream();
            if (errorStream != null) {
                String error = convertStreamToString(errorStream, isGzip);
                throw new ConnectionException(error, responseCode);
            }

            String body = convertStreamToString(connection.getInputStream(), isGzip);

            if (DataDroidLog.canLog(Log.VERBOSE)) {
                DataDroidLog.v(TAG, "Response body: ");
                int pos = 0;
                int bodyLength = body.length();
                while (pos < bodyLength) {
                    DataDroidLog.v(TAG, body.substring(pos, Math.min(bodyLength - 1, pos + 200)));
                    pos = pos + 200;
                }
            }

            return new ConnectionResult(connection.getHeaderFields(), body);
        } catch (IOException e) {
            DataDroidLog.e(TAG, "IOException", e);
            throw new ConnectionException(e);
        } catch (KeyManagementException e) {
            DataDroidLog.e(TAG, "KeyManagementException", e);
            throw new ConnectionException(e);
        } catch (NoSuchAlgorithmException e) {
            DataDroidLog.e(TAG, "NoSuchAlgorithmException", e);
            throw new ConnectionException(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static SSLContext getSslContext(Context context) throws NoSuchAlgorithmException, KeyManagementException {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            InputStream keyStoreStream = context.getResources().openRawResource(R.raw.client); // Load client certificate
            keyStore.load(keyStoreStream, "password".toCharArray()); // Client certificate password

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, "password".toCharArray());

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore trustStore = KeyStore.getInstance("BKS");
            InputStream trustStoreStream = context.getResources().openRawResource(R.raw.server); // Load CA certificate
            trustStore.load(trustStoreStream, "password".toCharArray()); // CA certificate password
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSLContext", e);
        }
    }

    private static String convertStreamToString(InputStream is, boolean isGzipEnabled) throws IOException {
        InputStream cleanedIs = is;
        if (isGzipEnabled) {
            cleanedIs = new GZIPInputStream(is);
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(cleanedIs, UTF8_CHARSET));
            StringBuilder sb = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) {
                sb.append(line);
                sb.append("\n");
            }

            return sb.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }

            cleanedIs.close();

            if (isGzipEnabled) {
                is.close();
            }
        }
    }
}
        }
    }
}
