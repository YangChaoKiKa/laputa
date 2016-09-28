package com.orctom.laputa.server.example.controller;

import com.orctom.laputa.server.annotation.DefaultValue;
import com.orctom.laputa.server.annotation.POST;
import com.orctom.laputa.server.annotation.Param;
import com.orctom.laputa.server.annotation.Path;
import com.orctom.laputa.server.example.model.SKU;
import com.orctom.laputa.server.model.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

@Controller
@Path("/product")
public class Product {

  private static final Logger LOGGER = LoggerFactory.getLogger(Product.class);

  //  @Path("/hello")
  public String hello() {
    return "hello";
  }

  @Path("/hello")
  public String hello2(@Param("hello") String hello,
                       @Param("id") @DefaultValue("12345") String id) {
    return "hello: " + hello + ", id=" + id;
  }

  @Path("/sku")
  public SKU sku() {
    return new SKU("315515", "folder", 123456, 100000);
  }

  // http://localhost:7000/product/sku/new?sku=sku&desc=desc&category=cate&stock=100
  @Path("/sku/new")
  public SKU addSKU(@Param("sku") SKU sku) {
    return sku;
  }

  // curl -F "file=@sent.txt" http://localhost:7000/product/upload | less
  @POST
  @Path("/upload")
  public String upload(@Param("file") MultipartFile uploadedFile) {
    LOGGER.debug(uploadedFile.toString());
    return "uploaded: " + uploadedFile.toString();
  }

  @Path("/hello/{name}")
  public String hello(@Param("name") String name) {
    return "hello " + name;
  }

  @Path("/hello/{name}/a")
  public String helloA(@Param("name") String name) {
    return "hello " + name;
  }

  @Path("/hello/{name}/b")
  public String helloB(@Param("name") String name) {
    return "hello " + name;
  }

  @Path("/hello/{name}/attribute/{attribute}")
  public String helloAttribute(@Param("name") String name,
                               @Param("attribute") String attribute) {
    return "hello: " + name + ", attribute: " + attribute;
  }
}
