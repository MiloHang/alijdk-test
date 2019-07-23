package com.alibaba.thanos.alijdk.httpclient.test;

import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Description here
 *
 * @author: JinHeng
 * @version: 1.0
 * @since: 2019-07-23
 */
@Controller
public class MainController {

    @GetMapping("/get")
    @ResponseBody
    public String getMapping() {
        return "hello world";
    }

    @PostMapping("/post")
    @ResponseBody
    public String postMapping(@RequestBody Map<String, Object> requestBody) {
        return JSONObject.toJSONString(requestBody);
    }
}
