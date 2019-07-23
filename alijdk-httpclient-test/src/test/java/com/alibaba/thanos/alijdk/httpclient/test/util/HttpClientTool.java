package com.alibaba.thanos.alijdk.httpclient.test.util;

import com.alibaba.fastjson.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Description here
 *
 * @author: JinHeng
 * @version: 1.0
 * @since: 2019-07-23
 */
public class HttpClientTool {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientTool.class);

    private static String ENCODING = "UTF-8";

    private static String APPLICATION_JSON = "application/json";

    private static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";

    /**
     * HTTP GET请求
     *
     * @param url
     * @param params
     * @return String
     */
    public static String doGetWithStringResult(String url, Map<String, String> params) {
        HttpClient client = HttpClients.createDefault();
        URIBuilder builder = null;
        HttpGet httpGet = null;
        try {
            builder = new URIBuilder(url);
            if( params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    builder.addParameter(entry.getKey(), entry.getValue());
                }
            }
            httpGet = new HttpGet(builder.build());
            RequestConfig requestConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.DEFAULT).setSocketTimeout(5000).setConnectTimeout(5000).setConnectionRequestTimeout(5000).build();
            httpGet.setConfig(requestConfig);
            HttpResponse response = client.execute(httpGet);
            HttpEntity entity = response.getEntity();
            return EntityUtils.toString(entity);
        } catch (URISyntaxException e) {
            logger.error("GET request error, stack trace={}", e.getStackTrace());
        } catch (ClientProtocolException e) {
            logger.error("GET request error, stack trace={}", e.getStackTrace());
        } catch (IOException e) {
            logger.error("GET request error, stack trace={}", e.getStackTrace());
        }

        return null;
    }

    /**
     * HTTP GET请求
     *
     * @param url
     * @param params
     * @return String
     */
    public static JSONObject doGetWithJSONObjectResult(String url, Map<String, String> params) {
        HttpClient client = HttpClients.createDefault();
        URIBuilder builder = null;
        HttpGet httpGet = null;
        try {
            builder = new URIBuilder(url);
            if( params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    builder.addParameter(entry.getKey(), entry.getValue());
                }
            }
            httpGet = new HttpGet(builder.build());
            RequestConfig requestConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.DEFAULT).setSocketTimeout(5000).setConnectTimeout(5000).setConnectionRequestTimeout(5000).build();
            httpGet.setConfig(requestConfig);
            HttpResponse response = client.execute(httpGet);
            HttpEntity entity = response.getEntity();
            return jsonStringToJSONObject(EntityUtils.toString(entity));
        } catch (URISyntaxException e) {
            logger.error("GET request error, stack trace={}", e.getStackTrace());
        } catch (ClientProtocolException e) {
            logger.error("GET request error, stack trace={}", e.getStackTrace());
        } catch (IOException e) {
            logger.error("GET request error, stack trace={}", e.getStackTrace());
        }

        return null;
    }

    /**
     * HTTP POST请求
     *
     * @param url    请求链接
     * @param map    请求参数
     * @param isForm 是否为表单类型
     * @return JSONObject
     */
    public static JSONObject doPost(String url, Map<String, Object> map, boolean isForm) {
        HttpClient client = HttpClients.createDefault();
        // 声明httpPost请求
        HttpPost httpPost = new HttpPost(url);

        if (map != null) {
            List<NameValuePair> params = new ArrayList<NameValuePair>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                params.add(new BasicNameValuePair(entry.getKey(), entry.getValue().toString()));
            }

            try {
                if (isForm) {
                    UrlEncodedFormEntity formEntity = null;
                    formEntity = new UrlEncodedFormEntity(params, "utf-8");
                    formEntity.setContentType(APPLICATION_FORM_URLENCODED);
                    // 把表单对象设置到httpPost中
                    httpPost.setEntity(formEntity);
                } else {
                    HttpEntity httpEntity = new StringEntity(map2JsonString(map), "utf-8");
                    httpPost.setEntity(httpEntity);
                    httpPost.setHeader("Content-type", APPLICATION_JSON);
                }
                HttpResponse response = client.execute(httpPost);
                HttpEntity entity = response.getEntity();
                return jsonStringToJSONObject(EntityUtils.toString(entity));
            } catch (ClientProtocolException e) {
                logger.error("POST request error, stack trace={}", e.getStackTrace());
            } catch (IOException e) {
                logger.error("POST request error, stack trace={}", e.getStackTrace());
            }
        }
        return null;
    }

    public static String map2JsonString(Map<String, Object> map) {
        return JSONObject.toJSONString(map);
    }

    public static JSONObject jsonStringToJSONObject(String text) {
        return JSONObject.parseObject(text);
    }
}
