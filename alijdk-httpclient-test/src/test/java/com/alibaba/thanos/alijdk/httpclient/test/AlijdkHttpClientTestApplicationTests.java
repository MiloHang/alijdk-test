package com.alibaba.thanos.alijdk.httpclient.test;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.thanos.alijdk.httpclient.test.util.HttpClientTool;
import org.apache.http.HttpResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.RestTemplate;
import sun.net.www.http.HttpClient;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class AlijdkHttpClientTestApplicationTests {

	private final int port = 8080;

	private URI uri;

	@Before
	public void init() throws URISyntaxException {
		uri = new URI("http://localhost:" + port + "/");
	}

	@Test
	public void getMappingTest() {
		String url = uri.toString() + "get";
		String response = HttpClientTool.doGetWithStringResult(url, null);
		Assert.assertEquals("hello world", response);
	}

	@Test
	public void postMappingTest() {
		String url = uri.toString() + "post";
		Map<String, Object> map = new HashMap<>();
		map.put("name", "milo");
		map.put("age", 18);
		JSONObject jsonObject = HttpClientTool.doPost(url, map, false);
		Assert.assertEquals(JSONObject.toJSONString(map), jsonObject.toJSONString());
	}
}
