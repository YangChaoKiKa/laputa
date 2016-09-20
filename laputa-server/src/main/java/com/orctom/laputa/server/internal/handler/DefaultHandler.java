package com.orctom.laputa.server.internal.handler;

import com.orctom.laputa.server.annotation.Path;
import org.springframework.stereotype.Controller;

/**
 * Default handler
 * Created by hao on 11/17/15.
 */
@Controller
public class DefaultHandler {

  @Path("/favicon.ico")
  public String _favicon() {
    return null;
  }

  @Path("/404")
  public String _404() {
    return "The requested resource does not exist.";
  }

  @Path("/500")
  public String _500() {
    return "The server can not process your last request, please try again later.";
  }
}
